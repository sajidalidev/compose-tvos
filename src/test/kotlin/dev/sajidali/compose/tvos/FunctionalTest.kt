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
 * Isolation: the plugin resolves its disk caches from `settings.startParameter.gradleUserHomeDir`
 * — the Gradle user home TestKit already isolates via `withTestKitDir` (shared at
 * `build/functional-test/testkit` to amortize the one-time Kotlin plugin/stdlib downloads) —
 * so test runs never touch the real `~/.gradle` caches. Each consumer project additionally
 * sets `systemProp.user.home` to a fake home under `build/functional-test/`, exercised here
 * only to confirm that override still reaches the consumer daemon (some other tooling may
 * rely on it), independent of the plugin's own cache-root resolution.
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

        // systemProp.user.home still reaches the consumer's daemon (unrelated consumers may
        // rely on it), but the plugin's disk caches no longer key off it: they resolve their
        // cache root from `settings.startParameter.gradleUserHomeDir`, which TestKit points
        // at `testKitDir` (`withTestKitDir`), not at the fake `user.home`.
        val userHome = result.output.lineSequence().single { it.startsWith("USERHOME> ") }.removePrefix("USERHOME> ")
        assertEquals(fakeHome.absolutePath, userHome, "systemProp.user.home must reach the consumer build")
        // The variant-discovery cache must land under the TestKit Gradle user home ...
        // (it persists across runs on purpose: on a warm rerun the daemon's in-memory
        // discovery cache may legitimately skip re-writing it.)
        val cacheFile = testKitDir.resolve("compose-tvos-redirect-cache-v3/dev_sajidali_compose_ui_ui_1_11_0.cache")
        assertTrue(cacheFile.exists(), "discovery cache expected under the TestKit gradle user home: $cacheFile")
        // ... and never under the fake user.home ...
        assertFalse(
            fakeHome.resolve(".gradle/compose-tvos-redirect-cache-v3").exists(),
            "discovery cache must not land under the fake user.home"
        )
        // ... nor under the real ~/.gradle.
        assertFalse(
            File(System.getProperty("user.home"), ".gradle/compose-tvos-redirect-cache-v3").exists(),
            "discovery cache must never touch the real ~/.gradle"
        )
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
    fun `a newly-covered ComposeModules group redirects end-to-end`(
        @TempDir projectDir: File
    ) {
        writeConsumerProject(
            projectDir,
            dependency = "org.jetbrains.androidx.lifecycle:lifecycle-runtime:$COMPOSE_VERSION"
        )

        val result = runResolve(projectDir, target = "tvosArm64")

        val resolved = result.resolvedLines()
        val umbrella = resolved.single { it.contains("org.jetbrains.androidx.lifecycle:lifecycle-runtime:$COMPOSE_VERSION ") }
        assertContains(umbrella, "-injected", message = "umbrella must be selected through an injected variant: $umbrella")
        assertTrue(
            resolved.any { it.contains("dev.sajidali.androidx.lifecycle:lifecycle-runtime-tvosarm64:$COMPOSE_VERSION") },
            "graph must contain the fork platform module for the newly-covered androidx.lifecycle group; got:\n${resolved.joinToString("\n")}"
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

    @Test
    fun `--offline resolves from warm caches`(
        @TempDir projectDir: File
    ) {
        writeConsumerProject(projectDir, dependency = "org.jetbrains.compose.ui:ui:$COMPOSE_VERSION")

        // Warm: an online resolve (idempotent even if another test already warmed the same
        // coordinate on the shared daemon) populates both the plugin's on-disk discovery
        // cache and, on whichever daemon services it, the in-memory one.
        val warm = runResolve(projectDir, target = "tvosArm64")
        assertTrue(
            warm.resolvedLines().any { it.contains("dev.sajidali.compose.ui:ui-tvosarm64:$COMPOSE_VERSION") },
            "warm run must resolve the fork platform module before testing --offline; got:\n${warm.output}"
        )

        // Warm + --offline: same shared daemon/testKitDir as the warm run above, so both the
        // in-memory and on-disk caches are already populated — resolution must still
        // succeed purely from those caches, with no network access.
        val warmOffline = GradleRunner.create()
            .withProjectDir(projectDir)
            .withTestKitDir(testKitDir)
            .withArguments("resolveGraph", "-PresolveTarget=tvosArm64", "--console=plain", "--offline")
            .build()
        assertTrue(
            warmOffline.resolvedLines().any { it.contains("dev.sajidali.compose.ui:ui-tvosarm64:$COMPOSE_VERSION") },
            "warm --offline run must still resolve the fork platform module from caches; got:\n${warmOffline.output}"
        )
    }

    @Test
    fun `--offline fails gracefully when the plugin has never seen the coordinate before`(
        @TempDir warmupProjectDir: File,
        @TempDir projectDir: File
    ) {
        // Warm up Gradle's own tooling classpath (Kotlin Gradle plugin, stdlib, our plugin's
        // marker jars) with the well-established COMPOSE_VERSION coordinate, so this test is
        // independent of JUnit's execution order relative to the other functional tests for
        // the shared testKitDir to already have that classpath cached.
        writeConsumerProject(warmupProjectDir, dependency = "org.jetbrains.compose.ui:ui:$COMPOSE_VERSION")
        runResolve(warmupProjectDir, target = "tvosArm64")

        // COLD_VERSION is published (upstream-only, no fork counterpart) and never requested
        // by any other test in the suite: neither the plugin's on-disk discovery cache nor
        // the shared daemon's in-memory `TvosVariantInjectionRule`/`TvosVariantDiscovery`
        // companion-object cache — nor, it turns out, Gradle's OWN per-target module
        // resolution cache — has ever seen it, so --offline resolution fails cleanly at the
        // base dependency itself (confirmed: an online warm-up resolve of this exact
        // coordinate for a DIFFERENT target, iosArm64, does not make it offline-resolvable
        // for tvosArm64's separate resolvable configuration). That is still exactly the
        // graceful, non-crashing degrade the brief asks for: resolution fails for the tvOS
        // dependency rather than the plugin throwing or silently fabricating a variant.
        writeConsumerProject(projectDir, dependency = "org.jetbrains.compose.ui:ui:$COLD_VERSION")

        val cold = GradleRunner.create()
            .withProjectDir(projectDir)
            .withTestKitDir(testKitDir)
            .withArguments("resolveGraph", "-PresolveTarget=tvosArm64", "--console=plain", "--offline")
            .buildAndFail()

        // No RESOLVED/ARTIFACT line may ever name a fork coordinate: the base org.jetbrains
        // dependency fails to resolve before any graph walking/printing happens (see the
        // UNRESOLVED assertion below), so nothing is ever actually injected or substituted.
        // The build's output DOES legitimately mention "dev.sajidali" once, in the Task 5
        // empty-discovery WARN block (offline discovery for this never-before-seen coordinate
        // correctly comes back empty, and this consumer declares tvOS targets) -- that WARN is
        // the intended new signal for exactly this "would have injected but found nothing"
        // case, not a regression, so it is asserted on explicitly rather than forbidden.
        assertTrue(
            (cold.resolvedLines() + cold.artifactLines()).none { it.contains("dev.sajidali") },
            "cold --offline run must not inject or resolve any fork coordinate; got:\n${cold.output}"
        )
        assertTrue(
            cold.output.contains("[ComposeTvos] WARNING") && cold.output.contains("dev.sajidali.compose.ui:ui:$COLD_VERSION"),
            "cold --offline run must WARN about the empty-discovery module it would have injected; got:\n${cold.output}"
        )
        assertTrue(
            cold.output.contains("Could not resolve"),
            "cold --offline run must fail with a normal, non-crashing Gradle resolution error " +
                "(no network access was available to discover a tvOS variant), not a plugin crash; " +
                "got:\n${cold.output}"
        )
    }

    @Test
    fun `--configuration-cache is reused across runs and still resolves the injected coordinate`(
        @TempDir projectDir: File
    ) {
        writeConsumerProject(projectDir, dependency = "org.jetbrains.compose.ui:ui:$COMPOSE_VERSION")

        fun run(): BuildResult = GradleRunner.create()
            .withProjectDir(projectDir)
            .withTestKitDir(testKitDir)
            .withArguments("resolveGraph", "-PresolveTarget=tvosArm64", "--console=plain", "--configuration-cache")
            .build()

        val first = run()
        assertTrue(
            first.output.contains("Configuration cache entry stored"),
            "first run must store a configuration cache entry; got:\n${first.output}"
        )
        assertTrue(
            first.resolvedLines().any { it.contains("dev.sajidali.compose.ui:ui-tvosarm64:$COMPOSE_VERSION") },
            "first run must resolve the fork platform module; got:\n${first.output}"
        )

        val second = run()
        assertTrue(
            second.output.contains("Reusing configuration cache") ||
                second.output.contains("Configuration cache entry reused"),
            "second run must reuse the stored configuration cache entry; got:\n${second.output}"
        )
        assertTrue(
            second.resolvedLines().any { it.contains("dev.sajidali.compose.ui:ui-tvosarm64:$COMPOSE_VERSION") },
            "second (cache-reused) run must still resolve the fork platform module; got:\n${second.output}"
        )
    }

    // -- Task 5 (defect D1) diagnostics summary / warn / strictMode ----------------------

    @Test
    fun `strictMode fails the build listing every empty-discovery module`(
        @TempDir warmupProjectDir: File,
        @TempDir projectDir: File
    ) {
        // Same warm-up rationale as the pre-existing cold-offline test: prime the shared
        // TestKit daemon's tooling classpath with the well-established coordinate first, so
        // this test does not depend on JUnit's execution order relative to the others.
        writeConsumerProject(warmupProjectDir, dependency = "org.jetbrains.compose.ui:ui:$COMPOSE_VERSION")
        runResolve(warmupProjectDir, target = "tvosArm64")

        writeConsumerProject(
            projectDir,
            dependency = "org.jetbrains.compose.ui:ui:$COLD_VERSION",
            composeTvosConfig = """
                manifestUrl.set("")
                strictMode.set(true)
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withTestKitDir(testKitDir)
            .withArguments("resolveGraph", "-PresolveTarget=tvosArm64", "--console=plain", "--offline")
            .buildAndFail()

        assertTrue(
            result.output.contains("[ComposeTvos] strictMode"),
            "strictMode must fail the build with its own diagnostic message; got:\n${result.output}"
        )
        assertTrue(
            result.output.contains("org.jetbrains.compose.ui:ui:$COLD_VERSION -> dev.sajidali.compose.ui:ui:$COLD_VERSION"),
            "strictMode failure must list the empty-discovery module and its probed target coordinate; got:\n${result.output}"
        )
    }

    @Test
    fun `successful discovery prints the summary line and never warns`(
        @TempDir projectDir: File
    ) {
        writeConsumerProject(projectDir, dependency = "org.jetbrains.compose.ui:ui:$COMPOSE_VERSION")

        val result = runResolve(projectDir, target = "tvosArm64")

        assertTrue(
            result.output.contains("[ComposeTvos] Injected tvOS variants into 1 module(s) (0 skipped)"),
            "a fully-successful discovery must print the end-of-build summary lifecycle line; got:\n${result.output}"
        )
        assertFalse(
            result.output.contains("[ComposeTvos] WARNING"),
            "a fully-successful discovery must never print the empty-discovery WARN block; got:\n${result.output}"
        )
    }

    @Test
    fun `a consumer with no tvOS Kotlin targets is never warned even when discovery is empty`(
        @TempDir warmupProjectDir: File,
        @TempDir projectDir: File
    ) {
        writeConsumerProject(warmupProjectDir, dependency = "org.jetbrains.compose.ui:ui:$COMPOSE_VERSION")
        runResolve(warmupProjectDir, target = "tvosArm64")

        // COLD_VERSION has no dev.sajidali fork counterpart published at all (see its
        // declaration below) -- exactly the "would have injected but found nothing" shape --
        // but this consumer declares ONLY an iOS target, so the tvOS-target-detection guard
        // (ComposeTvosRedirectPlugin.detectTvosTargets) must never flip its flag, and the
        // WARN must stay suppressed even though the rule still runs (and still records an
        // empty discovery) for the org.jetbrains.compose.ui:ui module in general.
        writeConsumerProject(
            projectDir,
            dependency = "org.jetbrains.compose.ui:ui:$COLD_VERSION",
            kotlinTargets = listOf("iosArm64")
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withTestKitDir(testKitDir)
            .withArguments("resolveGraph", "-PresolveTarget=iosArm64", "--console=plain", "--offline")
            .build()

        assertTrue(
            result.resolvedLines().any { it.contains("org.jetbrains.compose.ui:ui-iosarm64:$COLD_VERSION") },
            "an iOS-only consumer must still resolve the upstream iOS module normally; got:\n${result.output}"
        )
        assertFalse(
            result.output.contains("[ComposeTvos] WARNING"),
            "a consumer declaring no tvOS Kotlin target must never see the empty-discovery WARN, " +
                "even though the module is otherwise redirect-eligible and discovery is genuinely " +
                "empty -- this is the warning-quality guard the WARN block would otherwise spuriously " +
                "fire for every android/iOS-only Compose consumer; got:\n${result.output}"
        )
    }

    // -- consumer project scaffolding ----------------------------------------------------

    private fun writeConsumerProject(
        projectDir: File,
        dependency: String,
        composeTvosConfig: String = """manifestUrl.set("")""",
        kotlinTargets: List<String> = listOf("tvosArm64", "tvosSimulatorArm64", "iosArm64")
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
            import org.gradle.api.artifacts.result.ResolvedComponentResult
            import org.gradle.api.artifacts.result.ResolvedDependencyResult
            import org.gradle.api.artifacts.result.UnresolvedDependencyResult

            plugins {
                kotlin("multiplatform") version "$KOTLIN_VERSION"
            }

            kotlin {
                ${kotlinTargets.joinToString("\n                ") { "$it()" }}

                sourceSets.commonMain.dependencies {
                    implementation("$dependency")
                }
            }

            // Configuration-cache-compatible resolution reporting: the target configuration
            // name and user.home are resolved at CONFIGURATION time (plain local values, not
            // read from `project`/`Task.project` inside `doLast`). The dependency graph itself
            // is captured via `resolutionResult.rootComponent` (a lazy `Provider`) instead of
            // the eager `ResolutionResult.allComponents`/`allDependencies`, and artifacts via
            // the `ArtifactCollection` reference itself (`config.incoming.artifacts`) rather
            // than its already-resolved `Set<ResolvedArtifactResult>` -- capturing either the
            // raw `Configuration` or a realized `Set<ResolvedArtifactResult>` in the task fails
            // configuration cache storage (`DefaultUnlockedConfiguration` /
            // `DefaultResolvedArtifactResult ... not supported`). The graph is walked manually
            // from the root to reproduce the previous `allComponents`/`allDependencies` semantics.
            tasks.register("resolveGraph") {
                val targetName = providers.gradleProperty("resolveTarget").get()
                val config = configurations.getByName("${'$'}{targetName}CompileKlibraries")
                val rootComponent = config.incoming.resolutionResult.rootComponent
                val artifactCollection = config.incoming.artifacts
                val userHome = System.getProperty("user.home")
                doLast {
                    println("USERHOME> ${'$'}userHome")

                    val allComponents = linkedSetOf<ResolvedComponentResult>()
                    fun walk(component: ResolvedComponentResult) {
                        if (!allComponents.add(component)) return
                        component.dependencies.forEach { d ->
                            if (d is UnresolvedDependencyResult) {
                                throw GradleException("UNRESOLVED: ${'$'}{d.attempted.displayName}: ${'$'}{d.failure}")
                            }
                            if (d is ResolvedDependencyResult) walk(d.selected)
                        }
                    }
                    walk(rootComponent.get())

                    allComponents.forEach { c ->
                        println("RESOLVED> ${'$'}{c.id.displayName} :: ${'$'}{c.variants.joinToString(",") { it.displayName }}")
                    }
                    artifactCollection.forEach { a ->
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

        /**
         * A version with only an upstream (iOS-only) umbrella published, never requested by
         * any other test — used to exercise a genuinely cold plugin discovery cache (both
         * on-disk and the shared daemon's in-memory `TvosVariantInjectionRule`/
         * `TvosVariantDiscovery` companion-object cache) under `--offline`.
         */
        private const val COLD_VERSION = "1.11.0-cold-offline"

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
                // D14 redirect-coverage expansion: org.jetbrains.androidx.lifecycle is now a
                // default ComposeModules.ALL group. Upstream umbrella: iOS only.
                publishUmbrellaWithPlatforms(
                    "org.jetbrains.androidx.lifecycle", "lifecycle-runtime", COMPOSE_VERSION,
                    listOf(FixtureNativeTarget.IOS_ARM64)
                )
                // Fork umbrella + platform modules discovered by the plugin.
                publishUmbrellaWithPlatforms(
                    "dev.sajidali.androidx.lifecycle", "lifecycle-runtime", COMPOSE_VERSION,
                    listOf(FixtureNativeTarget.TVOS_ARM64, FixtureNativeTarget.TVOS_SIMULATOR_ARM64)
                )
                // Cold-offline scenario: upstream-only (iOS-only), deliberately no fork
                // counterpart published — --offline discovery must never reach the network
                // to find out either way, so its absence is irrelevant to what's being tested.
                publishUmbrellaWithPlatforms(
                    "org.jetbrains.compose.ui", "ui", COLD_VERSION,
                    listOf(FixtureNativeTarget.IOS_ARM64)
                )
            }
        }
    }
}

/**
 * Task 9b (defect D13) functional tests: `org.jetbrains.compose` plugin-marker interception.
 *
 * Most of these assert at RESOLUTION level rather than successful plugin application: the
 * consumer project's build script requests `id("org.jetbrains.compose") version "<distinctive
 * version>"` and the build is asserted to `buildAndFail()` with the FAILURE MESSAGE naming
 * the expected `dev.sajidali.compose:compose-gradle-plugin:<version>` coordinate -- proof
 * that `useModule` fired with exactly the right substituted coordinate and version, entirely
 * without needing a real, compiled `Plugin<Project>` class published at that coordinate (see
 * task-9b-report.md for why building a fake jar with a real compiled plugin class was
 * rejected as needlessly heavy for this).
 *
 * The last test in this class goes one step further with a REAL (but deliberately
 * non-functional, no compiled class) jar published at the exact substituted coordinate, to
 * additionally prove that Gradle's plugin resolution machinery actually FETCHES the
 * substituted artifact (not just that the coordinate string was constructed correctly).
 */
class ComposePluginInterceptionFunctionalTest {

    @Test
    fun `org_jetbrains_compose plugin request is substituted to the tvOS fork by default`(
        @TempDir projectDir: File
    ) {
        writeComposePluginConsumer(projectDir, requestedVersion = "9.9.9")

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withTestKitDir(testKitDir)
            .withArguments("help", "--console=plain")
            .buildAndFail()

        assertTrue(
            result.output.contains("dev.sajidali.compose:compose-gradle-plugin:9.9.9"),
            "default interception must substitute org.jetbrains.compose to the fork coordinate " +
                "at the requested version; got:\n${result.output}"
        )
    }

    @Test
    fun `interceptComposeGradlePlugin false leaves org_jetbrains_compose resolving normally`(
        @TempDir projectDir: File
    ) {
        writeComposePluginConsumer(
            projectDir,
            requestedVersion = "9.9.9",
            composeTvosConfig = """
                manifestUrl.set("")
                interceptComposeGradlePlugin.set(false)
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withTestKitDir(testKitDir)
            .withArguments("help", "--console=plain")
            .buildAndFail()

        assertFalse(
            result.output.contains("dev.sajidali.compose"),
            "opt-out must never mention the fork coordinate; the failure must be against " +
                "normal (portal/Central) org.jetbrains.compose resolution instead; got:\n${result.output}"
        )
    }

    @Test
    fun `composeGradlePluginVersion extension override wins over the requested version`(
        @TempDir projectDir: File
    ) {
        writeComposePluginConsumer(
            projectDir,
            requestedVersion = "9.9.9",
            composeTvosConfig = """
                manifestUrl.set("")
                composeGradlePluginVersion.set("8.8.8")
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withTestKitDir(testKitDir)
            .withArguments("help", "--console=plain")
            .buildAndFail()

        assertTrue(
            result.output.contains("dev.sajidali.compose:compose-gradle-plugin:8.8.8"),
            "extension override must win over the requested plugin version (9.9.9); got:\n${result.output}"
        )
    }

    @Test
    fun `manifest gradlePlugin field wins over the requested version when no extension override is set`(
        @TempDir projectDir: File
    ) {
        val manifest = """{"schema": 2, "mappings": {}, "gradlePlugin": "7.7.7"}"""
        withManifestServer(manifest) { manifestUrl, hits ->
            writeComposePluginConsumer(
                projectDir,
                requestedVersion = "9.9.9",
                composeTvosConfig = """manifestUrl.set("$manifestUrl")"""
            )

            val result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withTestKitDir(testKitDir)
                .withArguments("help", "--console=plain")
                .buildAndFail()

            assertTrue(
                result.output.contains("dev.sajidali.compose:compose-gradle-plugin:7.7.7"),
                "manifest gradlePlugin field must win over the requested version (9.9.9) when " +
                    "no extension override is set; got:\n${result.output}"
            )
            assertTrue(hits.get() >= 1, "the consumer build must have fetched the manifest from the local server")
        }
    }

    @Test
    fun `a real artifact published at the substituted coordinate is actually fetched by Gradle`(
        @TempDir projectDir: File,
        @TempDir fakeRepoDir: File
    ) {
        // A REAL (but deliberately non-functional) jar at the exact substituted coordinate:
        // Gradle's plugin resolution reads META-INF/gradle-plugins/org.jetbrains.compose.properties
        // out of whatever jar useModule pointed it at, then tries to load the class named
        // there. Pointing it at a class that doesn't exist means the build still fails --
        // but at CLASS LOADING, strictly later than artifact/module resolution, proving the
        // real jar bytes were actually fetched over a real (file://) repository lookup rather
        // than merely asserting on the constructed coordinate string.
        val fakeVersion = "9.9.9-fake"
        writeFakeGradlePluginArtifact(
            repoDir = fakeRepoDir,
            group = "dev.sajidali.compose",
            artifact = "compose-gradle-plugin",
            version = fakeVersion,
            markerPluginId = "org.jetbrains.compose",
            implementationClass = "does.not.Exist"
        )
        writeComposePluginConsumer(
            projectDir,
            requestedVersion = fakeVersion,
            extraPluginRepositoryUrl = fakeRepoDir.toURI().toString()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withTestKitDir(testKitDir)
            .withArguments("help", "--console=plain")
            .buildAndFail()

        assertFalse(
            result.output.contains("Could not find dev.sajidali.compose:compose-gradle-plugin"),
            "a REAL artifact published at the substituted coordinate must resolve -- the " +
                "failure must move past artifact/module resolution; got:\n${result.output}"
        )
        assertTrue(
            result.output.contains("does.not.Exist"),
            "must fail while loading the (deliberately missing) implementation class named in " +
                "the real jar's marker properties file, proving that file was actually read " +
                "from a genuinely-fetched artifact; got:\n${result.output}"
        )
    }

    // -- consumer project scaffolding ----------------------------------------------------

    private fun writeComposePluginConsumer(
        projectDir: File,
        requestedVersion: String,
        composeTvosConfig: String = """manifestUrl.set("")""",
        extraPluginRepositoryUrl: String? = null
    ) {
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    maven { url = uri("${pluginRepo.toURI()}") }
                    ${if (extraPluginRepositoryUrl != null) """maven { url = uri("$extraPluginRepositoryUrl") }""" else ""}
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

            rootProject.name = "compose-plugin-consumer"
            """.trimIndent()
        )

        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("org.jetbrains.compose") version "$requestedVersion"
            }
            """.trimIndent()
        )

        projectDir.resolve("gradle.properties").writeText("org.gradle.jvmargs=-Xmx1g")
    }

    /**
     * Writes a REAL (resolvable) but deliberately non-functional Gradle plugin jar: a POM
     * plus a jar containing only `META-INF/gradle-plugins/<markerPluginId>.properties`
     * (pointing at a class that doesn't exist) -- no compiled `Plugin<Project>` class. See
     * this class's KDoc for why a fully-functional fake plugin isn't needed.
     */
    private fun writeFakeGradlePluginArtifact(
        repoDir: File,
        group: String,
        artifact: String,
        version: String,
        markerPluginId: String,
        implementationClass: String
    ) {
        val moduleDir = File(repoDir, "${group.replace('.', '/')}/$artifact/$version").apply { mkdirs() }
        moduleDir.resolve("$artifact-$version.pom").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>$group</groupId>
                <artifactId>$artifact</artifactId>
                <version>$version</version>
                <packaging>jar</packaging>
            </project>
            """.trimIndent()
        )
        val jarFile = moduleDir.resolve("$artifact-$version.jar")
        java.util.zip.ZipOutputStream(jarFile.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("META-INF/gradle-plugins/$markerPluginId.properties"))
            zos.write("implementation-class=$implementationClass\n".toByteArray())
            zos.closeEntry()
        }
    }

    companion object {
        private val pluginRepo = File(requireNotNull(System.getProperty("functionalTest.pluginRepo")))
        private val pluginVersion = requireNotNull(System.getProperty("functionalTest.pluginVersion"))
        private val testKitDir = File(requireNotNull(System.getProperty("functionalTest.testKitDir")))
    }
}

/**
 * Task 9b review fix: `pluginManagement.repositories` gradlePluginPortal() fallback preservation.
 *
 * What this proves: Gradle's own plugin resolution machinery only falls back to
 * `gradlePluginPortal()` when `settings.pluginManagement.repositories` is still EMPTY at the
 * point a plugin needs resolving. Before this fix, the settings plugin's `apply()`
 * unconditionally appended `mavenCentral()`/`mavenLocal()` to that handler -- turning an
 * initially-empty handler non-empty and silently disabling the portal fallback for every
 * OTHER, unrelated plugin the consumer's build later requests.
 *
 * Reproducing the exact README scenario (a bare `plugins { id("dev.sajidali.compose-tvos")
 * version "x.x.x" }` with NO `pluginManagement.repositories` block at all) is not directly
 * possible in this harness: our own plugin-under-test is only published to the local
 * `functionalTestRepo` (never the real Gradle Plugin Portal), and `GradleRunner.
 * withPluginClasspath()` does not inject into settings scripts (see the functional-test
 * wiring comment in build.gradle.kts) -- so declaring `pluginManagement.repositories` with at
 * least our own plugin's coordinate is otherwise unavoidable, which makes the handler
 * non-empty before our `apply()` ever runs and would mask this exact defect again.
 *
 * Instead, the consumer's `pluginManagement` block resolves our plugin-under-test via
 * `includeBuild(...)` against a tiny generated included build (below) that depends on the
 * already-published `functionalTestRepo` artifact and re-exposes it as a Gradle plugin --
 * `includeBuild` participates in plugin resolution WITHOUT ever touching
 * `pluginManagement.repositories`, so that handler is genuinely, verifiably empty the moment
 * our settings plugin's `apply()` runs, exactly matching the real README scenario's observable
 * state at that point, even though the resolution mechanism differs (`includeBuild` vs. the
 * real Gradle Plugin Portal). The consumer's build script then requests a second, unrelated,
 * nonexistent plugin id that can only ever be found (if at all) via the portal; the assertion
 * is on the FAILURE MESSAGE of that second request naming "Gradle Central Plugin Repository"
 * among the searched sources -- proof the portal fallback is still wired up after our own
 * repositories were appended.
 */
class PluginPortalFallbackFunctionalTest {

    @Test
    fun `an unrelated portal-hosted plugin request still consults the Gradle Plugin Portal when the consumer declared no pluginManagement repositories`(
        @TempDir projectDir: File,
        @TempDir includeBuildDir: File
    ) {
        writeIncludeBuildPluginProject(includeBuildDir)
        writeEmptyRepositoriesConsumer(projectDir, includeBuildDir)

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withTestKitDir(testKitDir)
            .withArguments("help", "--console=plain")
            .buildAndFail()

        assertTrue(
            result.output.contains(UNRESOLVABLE_PLUGIN_ID),
            "the build must fail while trying to resolve the unrelated, nonexistent plugin " +
                "request; got:\n${result.output}"
        )
        assertTrue(
            result.output.contains("Gradle Central Plugin Repository"),
            "with pluginManagement.repositories empty at our apply() time, the Gradle Plugin " +
                "Portal fallback must still be consulted for this later, unrelated plugin " +
                "request -- before the fix, our unconditional mavenCentral()/mavenLocal() " +
                "append made the handler non-empty and silently dropped the portal from the " +
                "search, so this failure would only ever mention MavenRepo/MavenLocal; " +
                "got:\n${result.output}"
        )
    }

    // -- consumer / included-build plugin-under-test scaffolding -------------------------

    private fun writeIncludeBuildPluginProject(dir: File) {
        dir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "compose-tvos-plugin-includebuild"
            """.trimIndent()
        )
        // `java-gradle-plugin`'s jar validation requires the implementation class to be
        // physically present INSIDE the produced jar -- a plain `dependencies { implementation
        // ... }` reference to the already-published functionalTestRepo artifact is NOT enough:
        // Gradle instead falls back to trying to resolve that dependency coordinate itself as a
        // *plugin marker* through the ordinary pluginManagement.repositories/portal route,
        // defeating the whole point of this included build. So the plugin's already-compiled
        // classes are unpacked straight out of the already-published jar into
        // `src/main/resources`, which `java-gradle-plugin` bundles as-is, no recompilation
        // needed; only its two real runtime dependencies (kotlin-stdlib, kotlinx-serialization-
        // json) are declared normally, resolved from mavenCentral() -- this project's OWN
        // top-level `repositories {}` block, entirely unrelated to the consuming settings
        // script's `pluginManagement.repositories`.
        val resourcesDir = File(dir, "src/main/resources").apply { mkdirs() }
        val pluginJar = File(pluginRepo, "dev/sajidali/compose-tvos/$pluginVersion/compose-tvos-$pluginVersion.jar")
        java.util.zip.ZipFile(pluginJar).use { zip ->
            zip.entries().asSequence()
                .filterNot { it.isDirectory || it.name.startsWith("META-INF/gradle-plugins/") }
                .forEach { entry ->
                    val outFile = File(resourcesDir, entry.name)
                    outFile.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input -> outFile.outputStream().use { input.copyTo(it) } }
                }
        }

        dir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                `java-gradle-plugin`
            }

            repositories {
                mavenCentral()
            }

            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-stdlib:$KOTLIN_VERSION")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$KOTLINX_SERIALIZATION_VERSION")
            }

            gradlePlugin {
                plugins {
                    create("composeTvosSettings") {
                        id = "dev.sajidali.compose-tvos"
                        implementationClass = "dev.sajidali.compose.tvos.ComposeTvosRedirectSettingsPlugin"
                    }
                }
            }
            """.trimIndent()
        )
    }

    private fun writeEmptyRepositoriesConsumer(projectDir: File, includeBuildDir: File) {
        // Deliberately no `repositories {}` block anywhere inside `pluginManagement`: this is
        // the README scenario, and `settings.pluginManagement.repositories` is genuinely empty
        // right up until our settings plugin's `apply()` runs.
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement {
                includeBuild("${includeBuildDir.absolutePath}")
            }

            plugins {
                id("dev.sajidali.compose-tvos")
            }

            composeTvos {
                manifestUrl.set("")
            }

            rootProject.name = "empty-plugin-management-repositories-consumer"
            """.trimIndent()
        )

        // A second, unrelated plugin request that exists nowhere -- neither the included
        // build nor any real repository -- so its failure is purely about which SOURCES got
        // searched, never about our own substituted org.jetbrains.compose coordinate.
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("$UNRESOLVABLE_PLUGIN_ID") version "0.0.1-does-not-exist"
            }
            """.trimIndent()
        )

        projectDir.resolve("gradle.properties").writeText("org.gradle.jvmargs=-Xmx1g")
    }

    companion object {
        private const val UNRESOLVABLE_PLUGIN_ID = "dev.sajidali.compose.tvos.test.portal.fallback.nonexistent"
        private const val KOTLIN_VERSION = "2.0.21"
        private const val KOTLINX_SERIALIZATION_VERSION = "1.7.3"

        private val pluginRepo = File(requireNotNull(System.getProperty("functionalTest.pluginRepo")))
        private val pluginVersion = requireNotNull(System.getProperty("functionalTest.pluginVersion"))
        private val testKitDir = File(requireNotNull(System.getProperty("functionalTest.testKitDir")))
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

    @Test
    fun `offline with a fresh cache returns cached mappings without a network request`(@TempDir cacheDir: File) {
        withManifestServer(manifestBody) { url, hits ->
            VersionManifestLoader.load(url, cacheDir, refreshDependencies = false)
            val hitsAfterWarm = hits.get()

            val mappings = VersionManifestLoader.load(url, cacheDir, refreshDependencies = false, offline = true)

            assertEquals(expectedMappings, mappings, "offline load must serve the fresh disk cache")
            assertEquals(hitsAfterWarm, hits.get(), "offline load must not perform a network request")
        }
    }

    @Test
    fun `offline with a stale cache still serves the stale mappings without a network request`(@TempDir cacheDir: File) {
        withManifestServer(manifestBody) { url, hits ->
            VersionManifestLoader.load(url, cacheDir, refreshDependencies = false)
            val cacheFile = manifestCacheFiles(cacheDir).single()
            val twentyFiveHoursAgo = System.currentTimeMillis() - 25L * 60 * 60 * 1000
            assertTrue(cacheFile.setLastModified(twentyFiveHoursAgo), "failed to age cache file")

            val hitsBeforeOffline = hits.get()
            val mappings = VersionManifestLoader.load(url, cacheDir, refreshDependencies = false, offline = true)

            assertEquals(
                expectedMappings, mappings,
                "offline load must serve whatever cache exists, stale or not -- offline never fetches"
            )
            assertEquals(
                hitsBeforeOffline, hits.get(),
                "offline load must not perform a network request even with a stale cache"
            )
        }
    }

    @Test
    fun `offline with no cache degrades to empty mappings without a network request`(@TempDir cacheDir: File) {
        withManifestServer(manifestBody) { url, hits ->
            val mappings = VersionManifestLoader.load(url, cacheDir, refreshDependencies = false, offline = true)

            assertTrue(mappings.isEmpty(), "no cache + offline must degrade to the same-version convention (empty mappings)")
            assertEquals(0, hits.get(), "offline load must never contact the server")
        }
    }

    private fun manifestCacheFiles(cacheDir: File): List<File> =
        cacheDir.resolve("compose-tvos-redirect-cache-v3/version-manifest")
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
