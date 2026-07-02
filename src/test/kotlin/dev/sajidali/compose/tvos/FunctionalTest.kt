package dev.sajidali.compose.tvos

import com.sun.net.httpserver.HttpServer
import dev.sajidali.compose.tvos.fixtures.FixtureNativeTarget
import dev.sajidali.compose.tvos.fixtures.FixtureRepo
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * GradleTestKit functional tests for the `dev.sajidali.compose-tvos` settings plugin.
 *
 * Each test generates a consumer Kotlin Multiplatform project in a temp dir, points its
 * `pluginManagement` at the locally published plugin (`build/functional-test-repo`, see
 * build.gradle.kts) and its dependency repositories at a generated `file://` fixture repo
 * ([FixtureRepo]), then asserts on real dependency resolution results printed by a
 * `resolveGraph` task injected into the consumer build.
 *
 * A custom resolution task is used instead of `dependencyInsight` because its output is
 * machine-parseable (stable `RESOLVED>` / `ARTIFACT>` prefixes carrying exact component
 * ids and selected variant names) and it resolves both the graph and the artifacts,
 * failing loudly on unresolved dependencies.
 *
 * Isolation: the plugin hardcodes `System.getProperty("user.home")` for its disk caches,
 * so every consumer project sets `systemProp.user.home` to a fake home under
 * `build/functional-test/` — test runs never touch the real `~/.gradle` caches. The
 * TestKit Gradle home is shared (`build/functional-test/testkit`) to amortize the
 * one-time Kotlin plugin/stdlib downloads.
 */
class ComposeTvosFunctionalTest {

    @Test
    fun `tvOS configuration resolves through the injected variant to the fork platform module`(
        @TempDir projectDir: File
    ) {
        writeConsumerProject(projectDir, dependency = "org.jetbrains.compose.ui:ui:$COMPOSE_VERSION")

        val result = runResolve(projectDir, target = "tvosArm64")

        val resolved = result.resolvedLines()
        val umbrella = resolved.single { it.contains("org.jetbrains.compose.ui:ui:$COMPOSE_VERSION ") }
        assertContains(umbrella, "-injected", message = "umbrella must be selected through an injected variant: $umbrella")
        assertTrue(
            resolved.any { it.contains("dev.sajidali.compose.ui:ui-tvosarm64:$COMPOSE_VERSION") },
            "graph must contain the fork platform module; got:\n${resolved.joinToString("\n")}"
        )
        assertTrue(
            result.artifactLines().any {
                it.contains("dev.sajidali.compose.ui:ui-tvosarm64:$COMPOSE_VERSION") &&
                    it.contains("ui-tvosarm64-$COMPOSE_VERSION.klib")
            },
            "artifacts must come from the fork platform module; got:\n${result.artifactLines().joinToString("\n")}"
        )

        // user.home isolation: the override must reach the consumer's daemon (the plugin
        // hardcodes System.getProperty("user.home") for its caches) ...
        val userHome = result.output.lineSequence().single { it.startsWith("USERHOME> ") }.removePrefix("USERHOME> ")
        assertEquals(fakeHome.absolutePath, userHome, "systemProp.user.home must reach the consumer build")
        // ... and the variant-discovery cache must consequently land in the fake home, never
        // in the real ~/.gradle. (The fake home persists across runs on purpose: on a warm
        // rerun the daemon's in-memory discovery cache may legitimately skip re-writing it.)
        val cacheFile = fakeHome.resolve(".gradle/compose-tvos-redirect-cache-v2/dev_sajidali_compose_ui_ui_1_11_0.cache")
        assertTrue(cacheFile.exists(), "discovery cache expected under fake user.home: $cacheFile")
    }

    @Test
    fun `iOS configuration resolves the official platform module with no fork nodes`(
        @TempDir projectDir: File
    ) {
        writeConsumerProject(projectDir, dependency = "org.jetbrains.compose.ui:ui:$COMPOSE_VERSION")

        val result = runResolve(projectDir, target = "iosArm64")

        val resolved = result.resolvedLines()
        val artifacts = result.artifactLines()
        assertTrue(
            resolved.any { it.contains("org.jetbrains.compose.ui:ui-iosarm64:$COMPOSE_VERSION") },
            "iOS graph must contain the official platform module; got:\n${resolved.joinToString("\n")}"
        )
        assertTrue(
            artifacts.any {
                it.contains("org.jetbrains.compose.ui:ui-iosarm64:$COMPOSE_VERSION") &&
                    it.contains("ui-iosarm64-$COMPOSE_VERSION.klib")
            },
            "iOS artifacts must come from the official platform module; got:\n${artifacts.joinToString("\n")}"
        )
        (resolved + artifacts).forEach { line ->
            assertFalse(line.contains("dev.sajidali"), "iOS resolution must not contain fork nodes: $line")
        }
    }

    @Test
    fun `directly requested tvOS-suffixed coordinate is substituted to the fork`(
        @TempDir projectDir: File
    ) {
        writeConsumerProject(projectDir, dependency = "org.jetbrains.compose.ui:ui-tvosarm64:$COMPOSE_VERSION")

        val result = runResolve(projectDir, target = "tvosArm64")

        val resolved = result.resolvedLines()
        assertTrue(
            resolved.any { it.contains("dev.sajidali.compose.ui:ui-tvosarm64:$COMPOSE_VERSION") },
            "direct tvOS dependency must be substituted to the fork; got:\n${resolved.joinToString("\n")}"
        )
        assertFalse(
            resolved.any { it.contains("org.jetbrains.compose.ui:ui-tvosarm64") },
            "the original org.jetbrains coordinate must no longer appear once substituted; got:\n${resolved.joinToString("\n")}"
        )
    }

    @Test
    fun `versionMappings translate the requested version for the injected dependency`(
        @TempDir projectDir: File
    ) {
        writeConsumerProject(
            projectDir,
            dependency = "org.jetbrains.compose.ui:ui:$UNMAPPED_VERSION",
            composeTvosConfig = """
                manifestUrl.set("")
                versionMappings.put("org.jetbrains.compose.ui:9.9.*", "$COMPOSE_VERSION")
            """.trimIndent()
        )

        val result = runResolve(projectDir, target = "tvosArm64")

        val resolved = result.resolvedLines()
        val umbrella = resolved.single { it.contains("org.jetbrains.compose.ui:ui:$UNMAPPED_VERSION ") }
        assertContains(umbrella, "-injected", message = "umbrella at $UNMAPPED_VERSION must receive injected variants: $umbrella")
        assertTrue(
            resolved.any { it.contains("dev.sajidali.compose.ui:ui-tvosarm64:$COMPOSE_VERSION") },
            "injected dependency must use the mapped version $COMPOSE_VERSION; got:\n${resolved.joinToString("\n")}"
        )
        assertFalse(
            resolved.any { it.contains("dev.sajidali") && it.contains(UNMAPPED_VERSION) },
            "no fork node may leak the unmapped version $UNMAPPED_VERSION; got:\n${resolved.joinToString("\n")}"
        )
    }

    @Test
    fun `additionalGroups redirect third-party umbrellas to their fork group`(
        @TempDir projectDir: File
    ) {
        writeConsumerProject(
            projectDir,
            dependency = "com.example.tv:widgets:$COMPOSE_VERSION",
            composeTvosConfig = """
                manifestUrl.set("")
                additionalGroups.put("com.example.tv", "dev.sajidali.example.tv")
            """.trimIndent()
        )

        val result = runResolve(projectDir, target = "tvosArm64")

        val resolved = result.resolvedLines()
        val umbrella = resolved.single { it.contains("com.example.tv:widgets:$COMPOSE_VERSION ") }
        assertContains(umbrella, "-injected", message = "additional-group umbrella must receive injected variants: $umbrella")
        assertTrue(
            resolved.any { it.contains("dev.sajidali.example.tv:widgets-tvosarm64:$COMPOSE_VERSION") },
            "graph must contain the mapped group's platform module; got:\n${resolved.joinToString("\n")}"
        )
    }

    @Test
    fun `manifest loaded over HTTP drives version mapping end-to-end`(
        @TempDir projectDir: File
    ) {
        val manifest = """{"schema": 1, "mappings": {"org.jetbrains.compose.ui:9.9.*": "$COMPOSE_VERSION"}}"""
        withManifestServer(manifest) { manifestUrl, hits ->
            writeConsumerProject(
                projectDir,
                dependency = "org.jetbrains.compose.ui:ui:$UNMAPPED_VERSION",
                composeTvosConfig = """manifestUrl.set("$manifestUrl")"""
            )

            val result = runResolve(projectDir, target = "tvosArm64")

            val resolved = result.resolvedLines()
            assertTrue(
                resolved.any { it.contains("dev.sajidali.compose.ui:ui-tvosarm64:$COMPOSE_VERSION") },
                "manifest mapping must translate $UNMAPPED_VERSION -> $COMPOSE_VERSION; got:\n${resolved.joinToString("\n")}"
            )
            assertTrue(hits.get() >= 1, "the consumer build must have fetched the manifest from the local server")
        }
    }

    // -- consumer project scaffolding ----------------------------------------------------

    private fun writeConsumerProject(
        projectDir: File,
        dependency: String,
        composeTvosConfig: String = """manifestUrl.set("")"""
    ) {
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    maven { url = uri("${pluginRepo.toURI()}") }
                    gradlePluginPortal()
                    mavenCentral()
                }
            }

            plugins {
                id("dev.sajidali.compose-tvos") version "$pluginVersion"
            }

            composeTvos {
                verbose.set(true)
                ${composeTvosConfig.prependIndent("                ").trimStart()}
            }

            dependencyResolutionManagement {
                repositories {
                    maven {
                        url = uri("${fixtureRepo.url}")
                        metadataSources { gradleMetadata() }
                    }
                    mavenCentral()
                }
            }

            rootProject.name = "consumer"
            """.trimIndent()
        )

        projectDir.resolve("build.gradle.kts").writeText(
            """
            import org.gradle.api.artifacts.result.UnresolvedDependencyResult

            plugins {
                kotlin("multiplatform") version "$KOTLIN_VERSION"
            }

            kotlin {
                tvosArm64()
                tvosSimulatorArm64()
                iosArm64()

                sourceSets.commonMain.dependencies {
                    implementation("$dependency")
                }
            }

            tasks.register("resolveGraph") {
                doLast {
                    println("USERHOME> " + System.getProperty("user.home"))
                    val targetName = project.property("resolveTarget") as String
                    val config = configurations.getByName("${'$'}{targetName}CompileKlibraries")
                    val result = config.incoming.resolutionResult
                    result.allDependencies.forEach { d ->
                        if (d is UnresolvedDependencyResult) {
                            throw GradleException("UNRESOLVED: ${'$'}{d.attempted.displayName}: ${'$'}{d.failure}")
                        }
                    }
                    result.allComponents.forEach { c ->
                        println("RESOLVED> ${'$'}{c.id.displayName} :: ${'$'}{c.variants.joinToString(",") { it.displayName }}")
                    }
                    config.incoming.artifacts.artifacts.forEach { a ->
                        println("ARTIFACT> ${'$'}{a.id.componentIdentifier.displayName} :: ${'$'}{a.file.name}")
                    }
                }
            }
            """.trimIndent()
        )

        projectDir.resolve("gradle.properties").writeText(
            """
            systemProp.user.home=${fakeHome.absolutePath}
            org.gradle.jvmargs=-Xmx1g
            """.trimIndent()
        )
    }

    private fun runResolve(projectDir: File, target: String): BuildResult =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withTestKitDir(testKitDir)
            .withArguments("resolveGraph", "-PresolveTarget=$target", "--console=plain")
            .build()

    private fun BuildResult.resolvedLines(): List<String> =
        output.lineSequence().filter { it.startsWith("RESOLVED> ") }.toList()

    private fun BuildResult.artifactLines(): List<String> =
        output.lineSequence().filter { it.startsWith("ARTIFACT> ") }.toList()

    companion object {
        private const val KOTLIN_VERSION = "2.0.21"
        private const val COMPOSE_VERSION = "1.11.0"

        /** A version with no fixture fork published at it — only reachable via mapping. */
        private const val UNMAPPED_VERSION = "9.9.9"

        private val pluginRepo = File(requireNotNull(System.getProperty("functionalTest.pluginRepo")))
        private val pluginVersion = requireNotNull(System.getProperty("functionalTest.pluginVersion"))
        private val testKitDir = File(requireNotNull(System.getProperty("functionalTest.testKitDir")))

        /** Working area next to the shared TestKit home: build/functional-test/. */
        private val workRoot = testKitDir.parentFile

        private val fakeHome = workRoot.resolve("fake-home")
        private lateinit var fixtureRepo: FixtureRepo

        @JvmStatic
        @BeforeAll
        fun setUpSharedFixtures() {
            // The fake home deliberately persists across runs: a warm TestKit daemon may keep
            // the plugin's static in-memory discovery cache alive, in which case the disk
            // cache is legitimately not re-written. Wiping it here would flake the isolation
            // assertion. If FixtureRepo variant generation changes, run `./gradlew clean` (or
            // delete build/functional-test/fake-home) to drop stale discovery caches.
            fakeHome.mkdirs()

            // The fixture repo IS regenerated from scratch every run: deterministic content.
            val fixtureDir = workRoot.resolve("fixture-repo")
            fixtureDir.deleteRecursively()
            fixtureDir.mkdirs()

            fixtureRepo = FixtureRepo(fixtureDir).apply {
                // Upstream umbrella: iOS only, like the official artifact.
                publishUmbrellaWithPlatforms(
                    "org.jetbrains.compose.ui", "ui", COMPOSE_VERSION,
                    listOf(FixtureNativeTarget.IOS_ARM64)
                )
                // Fork umbrella + platform modules discovered by the plugin.
                publishUmbrellaWithPlatforms(
                    "dev.sajidali.compose.ui", "ui", COMPOSE_VERSION,
                    listOf(FixtureNativeTarget.TVOS_ARM64, FixtureNativeTarget.TVOS_SIMULATOR_ARM64)
                )
                // Umbrella at the unmapped version for the version-mapping scenarios; the
                // metadata rule fires on the requested module@version, so it must exist.
                publishUmbrellaWithPlatforms(
                    "org.jetbrains.compose.ui", "ui", UNMAPPED_VERSION,
                    listOf(FixtureNativeTarget.IOS_ARM64)
                )
                // Third-party pair for the additionalGroups scenario.
                publishUmbrellaWithPlatforms(
                    "com.example.tv", "widgets", COMPOSE_VERSION,
                    listOf(FixtureNativeTarget.IOS_ARM64)
                )
                publishUmbrellaWithPlatforms(
                    "dev.sajidali.example.tv", "widgets", COMPOSE_VERSION,
                    listOf(FixtureNativeTarget.TVOS_ARM64, FixtureNativeTarget.TVOS_SIMULATOR_ARM64)
                )
            }
        }
    }
}

/**
 * Direct tests for [VersionManifestLoader.load] against a local JDK [HttpServer]:
 * fetch, TTL caching, forced refresh, stale-cache fallback and the no-cache failure path.
 *
 * Kept in this file (same package gives access to the internal loader); the loader is a
 * self-contained unit with an explicit `cacheDir`, so no TestKit build is needed here.
 */
class VersionManifestLoaderHttpTest {

    private val manifestBody =
        """{"schema": 1, "mappings": {"org.jetbrains.compose.material3:1.10.*": "1.10.0-alpha05"}}"""

    private val expectedMappings =
        mapOf("org.jetbrains.compose.material3:1.10.*" to "1.10.0-alpha05")

    @Test
    fun `fresh fetch parses the manifest and writes the disk cache`(@TempDir cacheDir: File) {
        withManifestServer(manifestBody) { url, hits ->
            val mappings = VersionManifestLoader.load(url, cacheDir, refreshDependencies = false)

            assertEquals(expectedMappings, mappings)
            assertEquals(1, hits.get())
            assertEquals(1, manifestCacheFiles(cacheDir).size, "one cache file expected")
        }
    }

    @Test
    fun `fresh cache is served without a second HTTP request`(@TempDir cacheDir: File) {
        withManifestServer(manifestBody) { url, hits ->
            VersionManifestLoader.load(url, cacheDir, refreshDependencies = false)
            val second = VersionManifestLoader.load(url, cacheDir, refreshDependencies = false)

            assertEquals(expectedMappings, second)
            assertEquals(1, hits.get(), "TTL-fresh cache must prevent a second fetch")
        }
    }

    @Test
    fun `refreshDependencies forces a refetch despite a fresh cache`(@TempDir cacheDir: File) {
        withManifestServer(manifestBody) { url, hits ->
            VersionManifestLoader.load(url, cacheDir, refreshDependencies = false)
            val second = VersionManifestLoader.load(url, cacheDir, refreshDependencies = true)

            assertEquals(expectedMappings, second)
            assertEquals(2, hits.get(), "--refresh-dependencies must bypass the fresh cache")
        }
    }

    @Test
    fun `stale cache is used when the server is unreachable`(@TempDir cacheDir: File) {
        var url = ""
        withManifestServer(manifestBody) { serverUrl, _ ->
            url = serverUrl
            VersionManifestLoader.load(serverUrl, cacheDir, refreshDependencies = false)
        } // server stopped here

        val cacheFile = manifestCacheFiles(cacheDir).single()
        val twentyFiveHoursAgo = System.currentTimeMillis() - 25L * 60 * 60 * 1000
        assertTrue(cacheFile.setLastModified(twentyFiveHoursAgo), "failed to age cache file")

        val mappings = VersionManifestLoader.load(url, cacheDir, refreshDependencies = false)
        assertEquals(expectedMappings, mappings, "stale cache must be served when the network is down")
    }

    @Test
    fun `unreachable server with no cache yields empty mappings`(@TempDir cacheDir: File) {
        var url = ""
        withManifestServer(manifestBody) { serverUrl, _ -> url = serverUrl } // stopped, never fetched

        val mappings = VersionManifestLoader.load(url, cacheDir, refreshDependencies = false)
        assertTrue(mappings.isEmpty(), "no cache + no network must degrade to an empty map")
    }

    private fun manifestCacheFiles(cacheDir: File): List<File> =
        cacheDir.resolve("compose-tvos-redirect-cache-v2/version-manifest")
            .listFiles()?.toList().orEmpty()
}

/**
 * Serves [body] at `/manifest.json` on an ephemeral localhost port, counting requests.
 * The server is always stopped afterwards, so URLs captured inside the block can be
 * reused to exercise "server down" paths.
 */
private fun withManifestServer(body: String, block: (url: String, hits: AtomicInteger) -> Unit) {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val hits = AtomicInteger()
    server.createContext("/manifest.json") { exchange ->
        hits.incrementAndGet()
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    try {
        block("http://127.0.0.1:${server.address.port}/manifest.json", hits)
    } finally {
        server.stop(0)
    }
}
