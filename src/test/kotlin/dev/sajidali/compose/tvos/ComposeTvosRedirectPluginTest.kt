package dev.sajidali.compose.tvos

import com.sun.net.httpserver.HttpServer
import org.gradle.api.logging.Logging
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvosTargetsTest {

    @Test
    fun `matchesTvosSuffix detects tvOS artifacts`() {
        assertTrue(TvosTargets.matchesTvosSuffix("ui-tvosArm64"))
        assertTrue(TvosTargets.matchesTvosSuffix("ui-tvosX64"))
        assertTrue(TvosTargets.matchesTvosSuffix("ui-tvosSimulatorArm64"))
        assertTrue(TvosTargets.matchesTvosSuffix("ui-tvosarm64"))
        assertTrue(TvosTargets.matchesTvosSuffix("ui-TVOSARM64"))

        assertFalse(TvosTargets.matchesTvosSuffix("ui-iosArm64"))
        assertFalse(TvosTargets.matchesTvosSuffix("ui-macosArm64"))
        assertFalse(TvosTargets.matchesTvosSuffix("ui"))
    }

    @Test
    fun `extractTvosSuffix preserves original case`() {
        assertEquals("-tvosArm64", TvosTargets.extractTvosSuffix("ui-tvosArm64"))
        assertEquals("-tvosX64", TvosTargets.extractTvosSuffix("ui-tvosX64"))
        assertEquals("-tvosarm64", TvosTargets.extractTvosSuffix("ui-tvosarm64"))

        assertNull(TvosTargets.extractTvosSuffix("ui-iosArm64"))
        assertNull(TvosTargets.extractTvosSuffix("ui"))
    }
}

class TvosVariantDiscoveryTest {

    @Test
    fun `parseModuleMetadata extracts tvOS variants from available-at`() {
        val json = """
        {
          "variants": [
            {
              "name": "tvosArm64ApiElements",
              "attributes": {
                "org.jetbrains.kotlin.native.target": "tvos_arm64"
              },
              "available-at": {
                "module": "foundation-tvosarm64"
              }
            },
            {
              "name": "tvosSimulatorArm64ApiElements",
              "attributes": {
                "org.jetbrains.kotlin.native.target": "tvos_simulator_arm64"
              },
              "available-at": {
                "module": "foundation-tvossimulatorarm64"
              }
            },
            {
              "name": "iosArm64ApiElements",
              "attributes": {
                "org.jetbrains.kotlin.native.target": "ios_arm64"
              },
              "available-at": {
                "module": "foundation-iosarm64"
              }
            }
          ]
        }
        """.trimIndent()

        val variants = TvosVariantDiscovery.parseModuleMetadata(json, baseArtifactId = "foundation")

        assertEquals(2, variants.size)

        val arm64 = variants.find { it.nativeTarget == "tvos_arm64" }
        assertNotNull(arm64)
        assertEquals("foundation-tvosarm64", arm64!!.artifactId)

        val simArm64 = variants.find { it.nativeTarget == "tvos_simulator_arm64" }
        assertNotNull(simArm64)
        assertEquals("foundation-tvossimulatorarm64", simArm64!!.artifactId)
    }

    @Test
    fun `parseModuleMetadata returns empty for non-tvOS module`() {
        val json = """
        {
          "variants": [
            {
              "name": "iosArm64ApiElements",
              "attributes": {
                "org.jetbrains.kotlin.native.target": "ios_arm64"
              }
            }
          ]
        }
        """.trimIndent()

        assertTrue(TvosVariantDiscovery.parseModuleMetadata(json).isEmpty())
    }

    @Test
    fun `parseModuleMetadata keeps all variant kinds per target`() {
        // Mirror each source variant (api, sources, metadata, runtime) onto the upstream
        // umbrella — otherwise consumers resolving kotlin-metadata / kotlin-runtime /
        // docs-type=sources requests on the tvOS target silently miss the fork's artifact.
        val json = """
        {
          "variants": [
            {
              "name": "tvosArm64RuntimeElements",
              "attributes": {
                "org.jetbrains.kotlin.native.target": "tvos_arm64"
              },
              "available-at": { "module": "ui-tvosarm64" }
            },
            {
              "name": "tvosArm64ApiElements",
              "attributes": {
                "org.jetbrains.kotlin.native.target": "tvos_arm64"
              },
              "available-at": { "module": "ui-tvosarm64" }
            },
            {
              "name": "tvosArm64MetadataElements",
              "attributes": {
                "org.jetbrains.kotlin.native.target": "tvos_arm64"
              },
              "available-at": { "module": "ui-tvosarm64" }
            }
          ]
        }
        """.trimIndent()

        val variants = TvosVariantDiscovery.parseModuleMetadata(json, baseArtifactId = "ui")
        assertEquals(3, variants.size)
        assertTrue(variants.any { it.variantName == "tvosArm64ApiElements" })
        assertTrue(variants.any { it.variantName == "tvosArm64RuntimeElements" })
        assertTrue(variants.any { it.variantName == "tvosArm64MetadataElements" })
    }

    @Test
    fun `parseModuleMetadata captures the full attribute map per variant`() {
        val json = """
        {
          "variants": [
            {
              "name": "tvosArm64ApiElements",
              "attributes": {
                "artifactType": "org.jetbrains.kotlin.klib",
                "org.gradle.category": "library",
                "org.gradle.jvm.environment": "non-jvm",
                "org.gradle.usage": "kotlin-api",
                "org.jetbrains.kotlin.native.target": "tvos_arm64",
                "org.jetbrains.kotlin.platform.type": "native"
              },
              "available-at": { "module": "ui-tvosarm64" }
            }
          ]
        }
        """.trimIndent()

        val variants = TvosVariantDiscovery.parseModuleMetadata(json, baseArtifactId = "ui")
        assertEquals(1, variants.size)
        val attrs = variants[0].attributes
        assertEquals("library", attrs["org.gradle.category"])
        assertEquals("kotlin-api", attrs["org.gradle.usage"])
        assertEquals("non-jvm", attrs["org.gradle.jvm.environment"])
        assertEquals("tvos_arm64", attrs["org.jetbrains.kotlin.native.target"])
        assertEquals("native", attrs["org.jetbrains.kotlin.platform.type"])
    }

    @Test
    fun `parseModuleMetadata is robust to a nested object with a name-like key before attributes`() {
        // A regex-based section splitter keyed on `(?=\{\s*"name"\s*:)` would incorrectly
        // treat the nested dependency object's own "name" field as a new section boundary,
        // severing this variant's real name from its attributes/available-at block.
        val json = """
        {
          "variants": [
            {
              "name": "tvosArm64ApiElements",
              "dependencies": [
                { "name": "some-dependency", "group": "org.example", "module": "somelib" }
              ],
              "attributes": {
                "org.jetbrains.kotlin.native.target": "tvos_arm64"
              },
              "available-at": { "module": "ui-tvosarm64" }
            }
          ]
        }
        """.trimIndent()

        val variants = TvosVariantDiscovery.parseModuleMetadata(json, baseArtifactId = "ui")
        assertEquals(1, variants.size)
        assertEquals("tvosArm64ApiElements", variants[0].variantName)
        assertEquals("ui-tvosarm64", variants[0].artifactId)
    }

    @Test
    fun `parseModuleMetadata preserves attribute values containing commas and equals signs`() {
        val json = """
        {
          "variants": [
            {
              "name": "tvosArm64ApiElements",
              "attributes": {
                "org.jetbrains.kotlin.native.target": "tvos_arm64",
                "custom.note": "value,with=commas,and=equals"
              },
              "available-at": { "module": "ui-tvosarm64" }
            }
          ]
        }
        """.trimIndent()

        val variants = TvosVariantDiscovery.parseModuleMetadata(json, baseArtifactId = "ui")
        assertEquals(1, variants.size)
        assertEquals("value,with=commas,and=equals", variants[0].attributes["custom.note"])
    }

    @Test
    fun `parseModuleMetadata stringifies boolean and numeric attribute values`() {
        val json = """
        {
          "variants": [
            {
              "name": "tvosArm64ApiElements",
              "attributes": {
                "org.jetbrains.kotlin.native.target": "tvos_arm64",
                "custom.enabled": true,
                "custom.priority": 42,
                "custom.ratio": 1.5
              },
              "available-at": { "module": "ui-tvosarm64" }
            }
          ]
        }
        """.trimIndent()

        val variants = TvosVariantDiscovery.parseModuleMetadata(json, baseArtifactId = "ui")
        assertEquals(1, variants.size)
        val attrs = variants[0].attributes
        assertEquals("true", attrs["custom.enabled"])
        assertEquals("42", attrs["custom.priority"])
        assertEquals("1.5", attrs["custom.ratio"])
    }

    @Test
    fun `parseModuleMetadata returns empty list for malformed JSON`() {
        assertTrue(TvosVariantDiscovery.parseModuleMetadata("{ this is not valid json").isEmpty())
        assertTrue(TvosVariantDiscovery.parseModuleMetadata("").isEmpty())
    }

    @Test
    fun `parseModuleMetadata skips a malformed variant and keeps well-formed siblings`() {
        // A single variant whose attributes map contains a non-primitive value (nested
        // object instead of a string/number/boolean) must not take down the whole document —
        // only that variant should be dropped; the two well-formed tvOS variants around it
        // must still come back.
        val json = """
        {
          "variants": [
            {
              "name": "tvosArm64ApiElements",
              "attributes": {
                "org.jetbrains.kotlin.native.target": "tvos_arm64"
              },
              "available-at": { "module": "ui-tvosarm64" }
            },
            {
              "name": "tvosSimulatorArm64ApiElements",
              "attributes": {
                "org.jetbrains.kotlin.native.target": "tvos_simulator_arm64",
                "custom.broken": { "unexpected": "nested-object" }
              },
              "available-at": { "module": "ui-tvossimulatorarm64" }
            },
            {
              "name": "tvosX64ApiElements",
              "attributes": {
                "org.jetbrains.kotlin.native.target": "tvos_x64"
              },
              "available-at": { "module": "ui-tvosx64" }
            }
          ]
        }
        """.trimIndent()

        val variants = TvosVariantDiscovery.parseModuleMetadata(json, baseArtifactId = "ui")

        assertEquals(2, variants.size)
        assertTrue(variants.any { it.nativeTarget == "tvos_arm64" })
        assertTrue(variants.any { it.nativeTarget == "tvos_x64" })
        assertFalse(variants.any { it.nativeTarget == "tvos_simulator_arm64" })
    }

    // -- Task 11 (dangling-metadata fix): available-at group/version capture -------------

    @Test
    fun `parseModuleMetadata captures the available-at group and version verbatim`() {
        val json = """
        {
          "variants": [
            {
              "name": "tvosArm64ApiElements",
              "attributes": { "org.jetbrains.kotlin.native.target": "tvos_arm64" },
              "available-at": {
                "group": "org.jetbrains.androidx.lifecycle",
                "module": "lifecycle-viewmodel-compose-tvosarm64",
                "version": "2.11.0-beta01"
              }
            }
          ]
        }
        """.trimIndent()

        val variants = TvosVariantDiscovery.parseModuleMetadata(json, baseArtifactId = "lifecycle-viewmodel-compose")
        assertEquals(1, variants.size)
        val variant = variants[0]
        assertEquals("lifecycle-viewmodel-compose-tvosarm64", variant.artifactId)
        assertEquals("org.jetbrains.androidx.lifecycle", variant.availableAtGroup)
        assertEquals("2.11.0-beta01", variant.availableAtVersion)
    }

    @Test
    fun `parseModuleMetadata leaves available-at group and version null for an inline variant`() {
        val json = """
        {
          "variants": [
            {
              "name": "tvosArm64ApiElements",
              "attributes": { "org.jetbrains.kotlin.native.target": "tvos_arm64" }
            }
          ]
        }
        """.trimIndent()

        val variants = TvosVariantDiscovery.parseModuleMetadata(json, baseArtifactId = "runtime")
        assertEquals(1, variants.size)
        val variant = variants[0]
        assertNull(variant.availableAtGroup)
        assertNull(variant.availableAtVersion)
    }

    // -- Task 10b (Phase 4 blocker fix): already-supported native-target extraction ------

    @Test
    fun `alreadySupportedNativeTargets extracts the native-target set from discovered variants`() {
        val json = """
        {
          "variants": [
            {
              "name": "tvosArm64ApiElements",
              "attributes": { "org.jetbrains.kotlin.native.target": "tvos_arm64" },
              "available-at": { "module": "runtime-tvosarm64" }
            },
            {
              "name": "tvosArm64MetadataElements",
              "attributes": { "org.jetbrains.kotlin.native.target": "tvos_arm64" },
              "available-at": { "module": "runtime-tvosarm64" }
            }
          ]
        }
        """.trimIndent()

        val variants = TvosVariantDiscovery.parseModuleMetadata(json, baseArtifactId = "runtime")
        val targets = TvosVariantDiscovery.alreadySupportedNativeTargets(variants)

        assertEquals(setOf("tvos_arm64"), targets)
    }

    @Test
    fun `alreadySupportedNativeTargets returns an empty set for no variants`() {
        assertTrue(TvosVariantDiscovery.alreadySupportedNativeTargets(emptyList()).isEmpty())
    }
}

class TvosVariantDiscoveryDiskCacheTest {

    private val moduleJson = """
    {
      "variants": [
        {
          "name": "tvosArm64ApiElements",
          "attributes": {
            "org.jetbrains.kotlin.native.target": "tvos_arm64"
          },
          "available-at": { "module": "cachetest-tvosarm64" }
        }
      ]
    }
    """.trimIndent()

    @Test
    fun `corrupt disk cache is ignored and variants are refetched from the repository`(@TempDir tempDir: File) {
        TvosVariantDiscovery.clearCache()

        val groupId = "dev.sajidali.compose.cachetest"
        val artifactId = "cachetest"
        val version = "1.0.0-disk-cache-test"

        val repoDir = File(tempDir, "repo")
        val moduleFile = File(
            repoDir,
            "dev/sajidali/compose/cachetest/cachetest/$version/cachetest-$version.module"
        )
        moduleFile.parentFile.mkdirs()
        moduleFile.writeText(moduleJson)

        val cacheDir = File(tempDir, "cache")
        val cacheKey = "$groupId:$artifactId:$version"
        val safeKey = cacheKey.replace(":", "_").replace(".", "_")
        val cacheFile = File(cacheDir, "compose-tvos-redirect-cache-v3/$safeKey.cache")
        cacheFile.parentFile.mkdirs()
        cacheFile.writeText("{ this is not valid cache json at all")

        val variants = TvosVariantDiscovery.discoverVariants(
            repositoryUrls = listOf(repoDir.toURI().toString()),
            groupId = groupId,
            artifactId = artifactId,
            version = version,
            cacheDir = cacheDir
        )

        assertEquals(1, variants.size, "corrupt cache must not short-circuit discovery")
        assertEquals("tvos_arm64", variants[0].nativeTarget)
        assertEquals("cachetest-tvosarm64", variants[0].artifactId)

        // The corrupt file must have been overwritten with a valid, re-readable cache once
        // discovery succeeded.
        TvosVariantDiscovery.clearCache()
        val cached = TvosVariantDiscovery.discoverVariants(
            repositoryUrls = emptyList(),
            groupId = groupId,
            artifactId = artifactId,
            version = version,
            cacheDir = cacheDir
        )
        assertEquals(1, cached.size, "valid cache written after refetch must be re-readable")
    }

    @Test
    fun `disk cache round-trip preserves the full multi-key attributes map`(@TempDir tempDir: File) {
        TvosVariantDiscovery.clearCache()

        val groupId = "dev.sajidali.compose.attrscachetest"
        val artifactId = "attrscachetest"
        val version = "1.0.0-attrs-cache-test"

        val moduleJsonWithAttrs = """
        {
          "variants": [
            {
              "name": "tvosArm64ApiElements",
              "attributes": {
                "org.gradle.category": "library",
                "org.gradle.usage": "kotlin-api",
                "org.gradle.jvm.environment": "non-jvm",
                "org.jetbrains.kotlin.platform.type": "native",
                "org.jetbrains.kotlin.native.target": "tvos_arm64"
              },
              "available-at": { "module": "attrscachetest-tvosarm64" }
            }
          ]
        }
        """.trimIndent()

        val repoDir = File(tempDir, "repo")
        val moduleFile = File(
            repoDir,
            "dev/sajidali/compose/attrscachetest/attrscachetest/$version/attrscachetest-$version.module"
        )
        moduleFile.parentFile.mkdirs()
        moduleFile.writeText(moduleJsonWithAttrs)

        val cacheDir = File(tempDir, "cache")

        // Fetch from the fixture repo — this also write-throughs the parsed variants to disk.
        val fetched = TvosVariantDiscovery.discoverVariants(
            repositoryUrls = listOf(repoDir.toURI().toString()),
            groupId = groupId,
            artifactId = artifactId,
            version = version,
            cacheDir = cacheDir
        )
        assertEquals(1, fetched.size)

        // Force the disk-read path: clear the in-memory cache and supply no repository URLs,
        // so discoverVariants can only succeed by reading back what was written to disk above.
        TvosVariantDiscovery.clearCache()
        val cached = TvosVariantDiscovery.discoverVariants(
            repositoryUrls = emptyList(),
            groupId = groupId,
            artifactId = artifactId,
            version = version,
            cacheDir = cacheDir
        )

        assertEquals(1, cached.size, "variant written to disk must be re-readable")
        val original = fetched[0]
        val roundTripped = cached[0]

        assertEquals(original.variantName, roundTripped.variantName)
        assertEquals(original.nativeTarget, roundTripped.nativeTarget)
        assertEquals(original.artifactId, roundTripped.artifactId)
        assertEquals(original.attributes, roundTripped.attributes)

        assertEquals(5, roundTripped.attributes.size)
        assertEquals("library", roundTripped.attributes["org.gradle.category"])
        assertEquals("kotlin-api", roundTripped.attributes["org.gradle.usage"])
        assertEquals("non-jvm", roundTripped.attributes["org.gradle.jvm.environment"])
        assertEquals("native", roundTripped.attributes["org.jetbrains.kotlin.platform.type"])
        assertEquals("tvos_arm64", roundTripped.attributes["org.jetbrains.kotlin.native.target"])
    }

    // -- Task 10b follow-up (Fix 2, review of task-10b-report.md): successful-but-empty
    // discovery results must be cached (memory + disk), while a genuine fetch FAILURE must
    // never be, so it stays retryable. --------------------------------------------------------

    @Test
    fun `a successful fetch that finds zero tvOS variants is cached and not re-fetched`(@TempDir tempDir: File) {
        TvosVariantDiscovery.clearCache()

        val groupId = "dev.sajidali.compose.emptyfetchtest"
        val artifactId = "emptyfetchtest"
        val version = "1.0.0-empty-fetch-test"

        val repoDir = File(tempDir, "repo")
        val moduleFile = File(
            repoDir,
            "dev/sajidali/compose/emptyfetchtest/emptyfetchtest/$version/emptyfetchtest-$version.module"
        )
        moduleFile.parentFile.mkdirs()
        // Well-formed module metadata with NO tvOS variants at all (e.g. an android-only
        // umbrella) -- a genuinely successful fetch+parse that legitimately finds zero tvOS
        // variants, the majority-case shape this fix targets.
        moduleFile.writeText(
            """
            {
              "variants": [
                { "name": "androidApiElements", "attributes": { "org.jetbrains.kotlin.native.target": "android_arm64" } }
              ]
            }
            """.trimIndent()
        )

        val cacheDir = File(tempDir, "cache")
        val variants = TvosVariantDiscovery.discoverVariants(
            repositoryUrls = listOf(repoDir.toURI().toString()),
            groupId = groupId,
            artifactId = artifactId,
            version = version,
            cacheDir = cacheDir
        )
        assertTrue(variants.isEmpty())

        val cacheKey = "$groupId:$artifactId:$version"
        val safeKey = cacheKey.replace(":", "_").replace(".", "_")
        val cacheFile = File(cacheDir, "compose-tvos-redirect-cache-v3/$safeKey.cache")
        assertTrue(cacheFile.exists(), "a successful empty-variant fetch must still write the disk cache")

        // Force the disk-read path (clear the in-memory cache) and remove the repository
        // entirely, so a re-fetch attempt would fail loudly rather than silently succeeding.
        TvosVariantDiscovery.clearCache()
        repoDir.deleteRecursively()
        val cached = TvosVariantDiscovery.discoverVariants(
            repositoryUrls = listOf(repoDir.toURI().toString()),
            groupId = groupId,
            artifactId = artifactId,
            version = version,
            cacheDir = cacheDir
        )
        assertTrue(cached.isEmpty(), "the cached empty result must be served without needing the (now-deleted) repository")
    }

    @Test
    fun `an existing empty-list disk cache file is served as a valid hit, not a miss`(@TempDir tempDir: File) {
        TvosVariantDiscovery.clearCache()

        val groupId = "dev.sajidali.compose.emptycachetest"
        val artifactId = "emptycachetest"
        val version = "1.0.0-empty-cache-hit-test"

        val cacheDir = File(tempDir, "cache")
        val cacheKey = "$groupId:$artifactId:$version"
        val safeKey = cacheKey.replace(":", "_").replace(".", "_")
        val cacheFile = File(cacheDir, "compose-tvos-redirect-cache-v3/$safeKey.cache")
        cacheFile.parentFile.mkdirs()
        cacheFile.writeText("[]")

        val hits = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            hits.incrementAndGet()
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.start()
        try {
            val variants = TvosVariantDiscovery.discoverVariants(
                repositoryUrls = listOf("http://127.0.0.1:${server.address.port}"),
                groupId = groupId,
                artifactId = artifactId,
                version = version,
                cacheDir = cacheDir
            )

            assertTrue(variants.isEmpty())
            assertEquals(0, hits.get(), "an existing empty-list cache file must be served without any network fetch")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a fetch failure across all repositories is not cached and remains retryable`(@TempDir tempDir: File) {
        TvosVariantDiscovery.clearCache()

        val groupId = "dev.sajidali.compose.failtest"
        val artifactId = "failtest"
        val version = "1.0.0-fetch-failure-test"
        val missingRepoDir = File(tempDir, "missing-repo") // never created -> the .module file is missing -> failure
        val cacheDir = File(tempDir, "cache")

        val first = TvosVariantDiscovery.discoverVariants(
            repositoryUrls = listOf(missingRepoDir.toURI().toString()),
            groupId = groupId,
            artifactId = artifactId,
            version = version,
            cacheDir = cacheDir
        )
        assertTrue(first.isEmpty())

        val cacheKey = "$groupId:$artifactId:$version"
        val safeKey = cacheKey.replace(":", "_").replace(".", "_")
        val cacheFile = File(cacheDir, "compose-tvos-redirect-cache-v3/$safeKey.cache")
        assertFalse(cacheFile.exists(), "a fetch failure must never populate the disk cache")

        // Publish the module and retry: a stale, wrongly-cached empty result would have
        // short-circuited this retry and returned empty again.
        TvosVariantDiscovery.clearCache()
        val moduleFile = File(
            missingRepoDir,
            "dev/sajidali/compose/failtest/failtest/$version/failtest-$version.module"
        )
        moduleFile.parentFile.mkdirs()
        moduleFile.writeText(
            """
            {
              "variants": [
                {
                  "name": "tvosArm64ApiElements",
                  "attributes": { "org.jetbrains.kotlin.native.target": "tvos_arm64" },
                  "available-at": { "module": "failtest-tvosarm64" }
                }
              ]
            }
            """.trimIndent()
        )

        val retried = TvosVariantDiscovery.discoverVariants(
            repositoryUrls = listOf(missingRepoDir.toURI().toString()),
            groupId = groupId,
            artifactId = artifactId,
            version = version,
            cacheDir = cacheDir
        )
        assertEquals(1, retried.size, "a retry after a previous fetch failure must still succeed (no stale cached-empty result)")
    }
}

/**
 * Task 11 (dangling-metadata fix): direct unit tests for
 * [TvosVariantDiscovery.targetModuleExists]'s caching and confirmed-404-vs-network-failure
 * distinction -- mirrors the [TvosVariantDiscoveryDiskCacheTest] pattern above for
 * [TvosVariantDiscovery.discoverVariants]'s own `FetchOutcome`-based caching.
 */
class TvosVariantDiscoveryExistenceProbeTest {

    @Test
    fun `targetModuleExists returns true when the module is found on a repository`(@TempDir tempDir: File) {
        TvosVariantDiscovery.clearCache()

        val groupId = "dev.sajidali.compose.existstest"
        val artifactId = "existstest-tvosarm64"
        val version = "1.0.0-exists-test"

        val repoDir = File(tempDir, "repo")
        val moduleFile = File(repoDir, "${groupId.replace('.', '/')}/$artifactId/$version/$artifactId-$version.module")
        moduleFile.parentFile.mkdirs()
        moduleFile.writeText("""{"variants": []}""")

        val exists = TvosVariantDiscovery.targetModuleExists(
            repositoryUrls = listOf(repoDir.toURI().toString()),
            groupId = groupId,
            artifactId = artifactId,
            version = version,
            cacheDir = File(tempDir, "cache")
        )

        assertTrue(exists, "a genuinely published module must be confirmed to exist")
    }

    @Test
    fun `targetModuleExists returns false and caches confirmed absence when every repository 404s`(@TempDir tempDir: File) {
        TvosVariantDiscovery.clearCache()

        val groupId = "dev.sajidali.compose.absenttest"
        val artifactId = "absenttest-tvosarm64"
        val version = "1.0.0-absent-test"
        val cacheDir = File(tempDir, "cache")

        val hits = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            hits.incrementAndGet()
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.start()
        try {
            val first = TvosVariantDiscovery.targetModuleExists(
                repositoryUrls = listOf("http://127.0.0.1:${server.address.port}"),
                groupId = groupId,
                artifactId = artifactId,
                version = version,
                cacheDir = cacheDir
            )
            assertFalse(first, "a confirmed 404 on every repository must report the module as absent")
            assertEquals(1, hits.get())

            // Second call must be served from the (in-memory) cache, no further network hit.
            val second = TvosVariantDiscovery.targetModuleExists(
                repositoryUrls = listOf("http://127.0.0.1:${server.address.port}"),
                groupId = groupId,
                artifactId = artifactId,
                version = version,
                cacheDir = cacheDir
            )
            assertFalse(second)
            assertEquals(1, hits.get(), "a confirmed-absent result must be cached, not re-probed")

            // Force the disk-read path: clear the in-memory cache and shut the server down --
            // a re-probe attempt would fail loudly rather than silently succeeding.
            TvosVariantDiscovery.clearCache()
            server.stop(0)
            val third = TvosVariantDiscovery.targetModuleExists(
                repositoryUrls = listOf("http://127.0.0.1:${server.address.port}"),
                groupId = groupId,
                artifactId = artifactId,
                version = version,
                cacheDir = cacheDir
            )
            assertFalse(third, "the confirmed-absent result must also be cached to disk and re-readable")
        } finally {
            try { server.stop(0) } catch (_: Exception) { }
        }
    }

    @Test
    fun `targetModuleExists treats a non-404 failure as unknown, falls back to true, and never caches it`(
        @TempDir tempDir: File
    ) {
        TvosVariantDiscovery.clearCache()

        val groupId = "dev.sajidali.compose.unknowntest"
        val artifactId = "unknowntest-tvosarm64"
        val version = "1.0.0-unknown-test"
        val cacheDir = File(tempDir, "cache")

        val hits = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            hits.incrementAndGet()
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        server.start()
        try {
            val result = TvosVariantDiscovery.targetModuleExists(
                repositoryUrls = listOf("http://127.0.0.1:${server.address.port}"),
                groupId = groupId,
                artifactId = artifactId,
                version = version,
                cacheDir = cacheDir
            )
            assertTrue(result, "an inconclusive (non-404) failure must fall back to 'supported', distinct from a confirmed 404")
            assertEquals(1, hits.get())

            val cacheKey = "$groupId:$artifactId:$version"
            val safeKey = cacheKey.replace(":", "_").replace(".", "_")
            val cacheFile = File(cacheDir, "compose-tvos-redirect-cache-v3/$safeKey.exists")
            assertFalse(cacheFile.exists(), "an inconclusive probe outcome must never be written to the disk cache")
        } finally {
            server.stop(0)
        }
    }
}

/**
 * Direct unit tests for [TvosVariantDiscovery.discoverVariants]'s `offline` guard: a local
 * [HttpServer] stands in for a configured repository URL and counts requests, so these tests
 * prove the network is never touched while offline — not merely that the correct value comes
 * back. (See task-4-report.md, "Fix" section, for the mutation-check evidence that these
 * assertions would fail if the `if (offline)` short-circuit were removed.)
 */
class TvosVariantDiscoveryOfflineTest {

    private fun countingServer(): Pair<HttpServer, AtomicInteger> {
        val hits = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            hits.incrementAndGet()
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.start()
        return server to hits
    }

    @Test
    fun `offline discovery with empty memory cache and no disk cache never contacts the repository`(@TempDir tempDir: File) {
        TvosVariantDiscovery.clearCache()
        val (server, hits) = countingServer()
        try {
            val variants = TvosVariantDiscovery.discoverVariants(
                repositoryUrls = listOf("http://127.0.0.1:${server.address.port}"),
                groupId = "dev.sajidali.compose.offlinetest",
                artifactId = "offlinetest",
                version = "1.0.0-offline-nocache-test",
                cacheDir = File(tempDir, "cache"),
                offline = true
            )

            assertTrue(variants.isEmpty(), "offline discovery with nothing cached must return empty")
            assertEquals(0, hits.get(), "offline discovery must never open a network connection")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `offline discovery for a SNAPSHOT version never contacts the repository`(@TempDir tempDir: File) {
        // SNAPSHOT versions are deliberately never written to the disk cache (see
        // discoverVariants), so this is the purest form of "nothing cached" — offline must
        // still short-circuit before the SNAPSHOT-specific disk-cache skip is even reached.
        TvosVariantDiscovery.clearCache()
        val (server, hits) = countingServer()
        try {
            val variants = TvosVariantDiscovery.discoverVariants(
                repositoryUrls = listOf("http://127.0.0.1:${server.address.port}"),
                groupId = "dev.sajidali.compose.offlinetest",
                artifactId = "offlinetest",
                version = "1.0.0-SNAPSHOT",
                cacheDir = File(tempDir, "cache"),
                offline = true
            )

            assertTrue(variants.isEmpty(), "offline discovery of an uncached SNAPSHOT must return empty")
            assertEquals(0, hits.get(), "offline discovery must never open a network connection for a SNAPSHOT version")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `offline discovery serves a valid disk cache without contacting the repository`(@TempDir tempDir: File) {
        TvosVariantDiscovery.clearCache()

        val groupId = "dev.sajidali.compose.offlinedisktest"
        val artifactId = "offlinedisktest"
        val version = "1.0.0-offline-disk-cache-test"
        val cacheDir = File(tempDir, "cache")
        val cacheKey = "$groupId:$artifactId:$version"
        val safeKey = cacheKey.replace(":", "_").replace(".", "_")
        val cacheFile = File(cacheDir, "compose-tvos-redirect-cache-v3/$safeKey.cache")
        cacheFile.parentFile.mkdirs()
        cacheFile.writeText(
            """[{"variantName":"tvosArm64ApiElements","nativeTarget":"tvos_arm64","artifactId":"offlinedisktest-tvosarm64","attributes":{}}]"""
        )

        val (server, hits) = countingServer()
        try {
            val variants = TvosVariantDiscovery.discoverVariants(
                repositoryUrls = listOf("http://127.0.0.1:${server.address.port}"),
                groupId = groupId,
                artifactId = artifactId,
                version = version,
                cacheDir = cacheDir,
                offline = true
            )

            assertEquals(1, variants.size, "offline discovery must serve the on-disk cache when present")
            assertEquals("tvos_arm64", variants[0].nativeTarget)
            assertEquals(0, hits.get(), "offline discovery must not contact the repository even with a disk cache present")
        } finally {
            server.stop(0)
        }
    }
}

class TvosArtifactMappingTest {

    @Test
    fun `isTvosArtifact returns true for tvOS artifacts`() {
        assertTrue(TvosArtifactMapping.isTvosArtifact("ui-tvosArm64"))
        assertTrue(TvosArtifactMapping.isTvosArtifact("foundation-tvosX64"))
        assertTrue(TvosArtifactMapping.isTvosArtifact("ui-tvosSimulatorArm64"))
    }

    @Test
    fun `isTvosArtifact returns false for non-tvOS artifacts`() {
        assertFalse(TvosArtifactMapping.isTvosArtifact("ui-iosarm64"))
        assertFalse(TvosArtifactMapping.isTvosArtifact("ui-android"))
        assertFalse(TvosArtifactMapping.isTvosArtifact("ui"))
    }

    @Test
    fun `isUmbrellaModule returns true for modules without platform suffix`() {
        assertTrue(TvosArtifactMapping.isUmbrellaModule("ui"))
        assertTrue(TvosArtifactMapping.isUmbrellaModule("foundation"))
        assertTrue(TvosArtifactMapping.isUmbrellaModule("material3"))
        assertTrue(TvosArtifactMapping.isUmbrellaModule("navigation-compose"))
    }

    @Test
    fun `isUmbrellaModule returns false for platform-specific modules`() {
        assertFalse(TvosArtifactMapping.isUmbrellaModule("ui-tvosArm64"))
        assertFalse(TvosArtifactMapping.isUmbrellaModule("ui-iosArm64"))
        assertFalse(TvosArtifactMapping.isUmbrellaModule("ui-android"))
        assertFalse(TvosArtifactMapping.isUmbrellaModule("ui-desktop"))
    }

    @Test
    fun `isComposeGroup returns true for JetBrains Compose groups`() {
        assertTrue(TvosArtifactMapping.isComposeGroup("org.jetbrains.compose.ui"))
        assertTrue(TvosArtifactMapping.isComposeGroup("org.jetbrains.compose.foundation"))
        assertTrue(TvosArtifactMapping.isComposeGroup("org.jetbrains.compose.material3"))
        assertTrue(TvosArtifactMapping.isComposeGroup("org.jetbrains.androidx.navigation"))
    }

    @Test
    fun `isComposeGroup returns false for non-Compose groups`() {
        assertFalse(TvosArtifactMapping.isComposeGroup("org.jetbrains.kotlin"))
        assertFalse(TvosArtifactMapping.isComposeGroup("androidx.compose.ui"))
        assertFalse(TvosArtifactMapping.isComposeGroup("dev.sajidali.compose.ui"))
    }

    @Test
    fun `mapGroupId correctly maps JetBrains to sajidali`() {
        assertEquals("dev.sajidali.compose.ui", TvosArtifactMapping.mapGroupId("org.jetbrains.compose.ui"))
        assertEquals("dev.sajidali.compose.foundation", TvosArtifactMapping.mapGroupId("org.jetbrains.compose.foundation"))
        assertEquals("dev.sajidali.androidx.navigation", TvosArtifactMapping.mapGroupId("org.jetbrains.androidx.navigation"))
    }

    @Test
    fun `mapGroupId correctly maps the D14 redirect-coverage groups`() {
        assertEquals("dev.sajidali.androidx.lifecycle", TvosArtifactMapping.mapGroupId("org.jetbrains.androidx.lifecycle"))
        assertEquals("dev.sajidali.androidx.savedstate", TvosArtifactMapping.mapGroupId("org.jetbrains.androidx.savedstate"))
        assertEquals("dev.sajidali.androidx.navigationevent", TvosArtifactMapping.mapGroupId("org.jetbrains.androidx.navigationevent"))
        assertEquals("dev.sajidali.androidx.navigation3", TvosArtifactMapping.mapGroupId("org.jetbrains.androidx.navigation3"))
        assertEquals("dev.sajidali.compose.annotation-internal", TvosArtifactMapping.mapGroupId("org.jetbrains.compose.annotation-internal"))
        assertEquals("dev.sajidali.compose.collection-internal", TvosArtifactMapping.mapGroupId("org.jetbrains.compose.collection-internal"))
        assertEquals("dev.sajidali.compose.material3.adaptive", TvosArtifactMapping.mapGroupId("org.jetbrains.compose.material3.adaptive"))
    }
}

class ComposeModulesTest {

    @Test
    fun `ALL contains all Compose module groups`() {
        assertEquals(15, ComposeModules.ALL.size)
        assertTrue(ComposeModules.ALL.contains("org.jetbrains.compose.ui"))
        assertTrue(ComposeModules.ALL.contains("org.jetbrains.compose.foundation"))
        assertTrue(ComposeModules.ALL.contains("org.jetbrains.compose.runtime"))
        assertTrue(ComposeModules.ALL.contains("org.jetbrains.compose.material"))
        assertTrue(ComposeModules.ALL.contains("org.jetbrains.compose.material3"))
        assertTrue(ComposeModules.ALL.contains("org.jetbrains.compose.animation"))
        assertTrue(ComposeModules.ALL.contains("org.jetbrains.compose.components"))
        assertTrue(ComposeModules.ALL.contains("org.jetbrains.androidx.navigation"))
    }

    @Test
    fun `ALL contains the D14 redirect-coverage expansion groups`() {
        assertTrue(ComposeModules.ALL.contains("org.jetbrains.androidx.lifecycle"))
        assertTrue(ComposeModules.ALL.contains("org.jetbrains.androidx.savedstate"))
        assertTrue(ComposeModules.ALL.contains("org.jetbrains.androidx.navigationevent"))
        assertTrue(ComposeModules.ALL.contains("org.jetbrains.androidx.navigation3"))
        assertTrue(ComposeModules.ALL.contains("org.jetbrains.compose.annotation-internal"))
        assertTrue(ComposeModules.ALL.contains("org.jetbrains.compose.collection-internal"))
        assertTrue(ComposeModules.ALL.contains("org.jetbrains.compose.material3.adaptive"))
    }
}

class ComposeArtifactsTest {

    @Test
    fun `ALL contains predefined artifact coordinates`() {
        assertEquals(5, ComposeArtifacts.ALL.size)
        assertTrue(ComposeArtifacts.ALL.contains("org.jetbrains.androidx.navigation:navigation-compose"))
        assertTrue(ComposeArtifacts.ALL.contains("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose"))
    }

    @Test
    fun `getTargetMapping returns correct mapping`() {
        val mapping = ComposeArtifacts.getTargetMapping("org.jetbrains.androidx.navigation", "navigation-compose")
        assertNotNull(mapping)
        assertEquals("dev.sajidali.androidx.navigation", mapping!!.first)
        assertEquals("navigation-compose", mapping.second)
    }

    @Test
    fun `getTargetMapping returns null for non-predefined artifacts`() {
        assertNull(ComposeArtifacts.getTargetMapping("com.example", "my-lib"))
    }
}

class ComposeVersionsTest {

    @Test
    fun `resolveVersion prioritizes artifact over group`() {
        val mappings = mapOf(
            "org.example:lib:1.0.*" to "1.0.0-artifact",
            "org.example:1.0.*" to "1.0.0-group"
        )
        assertEquals("1.0.0-artifact", ComposeVersions.resolveVersion("org.example", "lib", "1.0.0", mappings, null))
    }

    @Test
    fun `resolveVersion falls back to original version (same-version convention)`() {
        // No mapping entry → return what was requested. This is the convention that lets
        // the plugin survive new Compose releases without a code change.
        assertEquals("1.10.5", ComposeVersions.resolveVersion(
            "org.jetbrains.compose.ui", "ui", "1.10.5", emptyMap(), null
        ))
    }

    @Test
    fun `resolveVersion uses override when specified`() {
        assertEquals("override", ComposeVersions.resolveVersion("org.example", "lib", "1.0.0", emptyMap(), "override"))
    }

    @Test
    fun `user mapping overrides manifest mapping on same key`() {
        // Mirrors what the settings/project plugins do: putAll manifest, then putAll user.
        // User entry wins on key collision.
        val manifest = ComposeVersions.normalizeMappings(mapOf(
            "org.jetbrains.compose.material3:1.10.*" to "1.10.0-alpha05"
        ))
        val user = ComposeVersions.normalizeMappings(mapOf(
            "org.jetbrains.compose.material3:1.10.*" to "1.10.0-rc01"
        ))
        val merged = mutableMapOf<String, String>().apply {
            putAll(manifest)
            putAll(user)
        }
        assertEquals("1.10.0-rc01", ComposeVersions.resolveVersion(
            "org.jetbrains.compose.material3", "material3", "1.10.3", merged, null
        ))
    }

    @Test
    fun `normalizeMappings adds global scope`() {
        val result = ComposeVersions.normalizeMappings(mapOf("1.10.0" to "mapped"))
        assertEquals("mapped", result["*:1.10.0"])
    }

    @Test
    fun `resolveVersion within a tier picks the longer wildcard prefix regardless of insertion order`() {
        val ascending = linkedMapOf(
            "org.example:1.10.*" to "short-prefix",
            "org.example:1.10.2.*" to "long-prefix"
        )
        assertEquals(
            "long-prefix",
            ComposeVersions.resolveVersion("org.example", "lib", "1.10.2.5", ascending, null),
            "longer prefix must win when the shorter pattern is inserted first"
        )

        val descending = linkedMapOf(
            "org.example:1.10.2.*" to "long-prefix",
            "org.example:1.10.*" to "short-prefix"
        )
        assertEquals(
            "long-prefix",
            ComposeVersions.resolveVersion("org.example", "lib", "1.10.2.5", descending, null),
            "longer prefix must win when the longer pattern is inserted first"
        )
    }

    @Test
    fun `resolveVersion within a tier prefers an exact version pattern over a wildcard`() {
        val mappings = linkedMapOf(
            "org.example:1.10.*" to "wildcard",
            "org.example:1.10.2" to "exact"
        )
        assertEquals("exact", ComposeVersions.resolveVersion("org.example", "lib", "1.10.2", mappings, null))

        val reversed = linkedMapOf(
            "org.example:1.10.2" to "exact",
            "org.example:1.10.*" to "wildcard"
        )
        assertEquals("exact", ComposeVersions.resolveVersion("org.example", "lib", "1.10.2", reversed, null))
    }

    @Test
    fun `resolveVersion breaks remaining specificity ties by ascending lexicographic key order`() {
        // Both keys' version patterns ("1.10.*") are equally specific, so per the documented
        // fallback the lexicographically smaller key wins: "org.jetbrains.*:1.10.*" sorts
        // before "org.jetbrains.compose.*:1.10.*" ('*' < 'c').
        val ascending = linkedMapOf(
            "org.jetbrains.*:1.10.*" to "wins-star",
            "org.jetbrains.compose.*:1.10.*" to "wins-compose"
        )
        assertEquals(
            "wins-star",
            ComposeVersions.resolveVersion("org.jetbrains.compose.material3", "material3", "1.10.5", ascending, null)
        )

        val descending = linkedMapOf(
            "org.jetbrains.compose.*:1.10.*" to "wins-compose",
            "org.jetbrains.*:1.10.*" to "wins-star"
        )
        assertEquals(
            "wins-star",
            ComposeVersions.resolveVersion("org.jetbrains.compose.material3", "material3", "1.10.5", descending, null)
        )
    }
}

class VersionManifestLoaderTest {

    @Test
    fun `parse extracts mappings from valid manifest`() {
        val json = """
        {
          "schema": 1,
          "mappings": {
            "org.jetbrains.compose.material3:1.10.*": "1.10.0-alpha05",
            "org.jetbrains.androidx.lifecycle:2.9.*": "2.10.0-alpha06"
          }
        }
        """.trimIndent()

        val mappings = VersionManifestLoader.parse(json, null)
        assertNotNull(mappings)
        assertEquals(2, mappings!!.size)
        assertEquals("1.10.0-alpha05", mappings["org.jetbrains.compose.material3:1.10.*"])
    }

    @Test
    fun `parse tolerates unknown top-level fields`() {
        val json = """
        {
          "schema": 1,
          "_comment": "human-readable note",
          "futureField": ["whatever"],
          "mappings": {"a:1.*": "1.0"}
        }
        """.trimIndent()
        val mappings = VersionManifestLoader.parse(json, null)
        assertNotNull(mappings)
        assertEquals("1.0", mappings!!["a:1.*"])
    }

    @Test
    fun `parse returns null for malformed JSON`() {
        assertNull(VersionManifestLoader.parse("{not json", null))
    }

    @Test
    fun `parse returns empty map for missing mappings field`() {
        val mappings = VersionManifestLoader.parse("""{"schema": 1}""", null)
        assertNotNull(mappings)
        assertTrue(mappings!!.isEmpty())
    }

    // -- Task 9b (defect D13): schema 2 gradlePlugin field --------------------------------

    @Test
    fun `parseManifest extracts the gradlePlugin field from a schema 2 manifest`() {
        val json = """
        {
          "schema": 2,
          "mappings": {"org.jetbrains.compose.material3:1.10.*": "1.10.0-alpha05"},
          "gradlePlugin": "1.12.0-beta01"
        }
        """.trimIndent()

        val manifest = VersionManifestLoader.parseManifest(json, null)
        assertNotNull(manifest)
        assertEquals("1.12.0-beta01", manifest!!.gradlePlugin)
        assertEquals(1, manifest.mappings.size)
    }

    @Test
    fun `parseManifest tolerates a schema 1 manifest with no gradlePlugin field`() {
        val json = """
        {
          "schema": 1,
          "mappings": {"org.jetbrains.compose.material3:1.10.*": "1.10.0-alpha05"}
        }
        """.trimIndent()

        val manifest = VersionManifestLoader.parseManifest(json, null)
        assertNotNull(manifest)
        assertNull(manifest!!.gradlePlugin)
        assertEquals(1, manifest.mappings.size)
    }

    @Test
    fun `parseManifest accepts an unrecognized schema 3 manifest, documenting the current accept-anything behavior`() {
        // `schema` is purely informational and never validated (see VersionManifestLoader's
        // KDoc): any integer, including ones ahead of what this loader was written against,
        // still parses fine as long as the fields it actually reads (`mappings`,
        // `gradlePlugin`) are present in a recognized shape.
        val json = """
        {
          "schema": 3,
          "mappings": {"org.jetbrains.compose.material3:1.10.*": "1.10.0-alpha05"},
          "gradlePlugin": "1.13.0"
        }
        """.trimIndent()

        val manifest = VersionManifestLoader.parseManifest(json, null)
        assertNotNull(manifest)
        assertEquals(3, manifest!!.schema)
        assertEquals("1.13.0", manifest.gradlePlugin)
        assertEquals(1, manifest.mappings.size)
    }
}

/**
 * Task 10e: unit tests for [ComposeTvosRedirectPlugin.isOfficiallySupported]'s substitution-side
 * diagnostics recording -- previously this mechanism's "already officially supported, skip
 * substitution" successes were recorded NOWHERE, so `DiagnosticsSummary.filterConflictLosers`
 * (Task 10d) had no visibility into them (see task-10d-report.md section 4). `isOfficiallySupported`
 * is `internal` (not `private`) specifically so it can be exercised directly here, against a
 * plain local `file://` fixture repository, without a full GradleTestKit consumer build.
 */
class ComposeTvosRedirectPluginIsOfficiallySupportedTest {

    private val logger = Logging.getLogger(ComposeTvosRedirectPluginIsOfficiallySupportedTest::class.java)

    @BeforeTest
    @AfterTest
    fun resetBookkeeping() {
        TvosDiagnosticsBookkeeping.resetDiagnostics()
        TvosVariantDiscovery.clearCache()
    }

    private fun configFor(tempDir: File, repositoryUrl: String): PluginConfiguration = PluginConfiguration(
        verbose = false,
        targetVersion = null,
        additionalGroups = emptyMap(),
        additionalArtifacts = emptyMap(),
        versionMappings = emptyMap(),
        manifestMappings = emptyMap(),
        repositoryUrls = listOf(repositoryUrl),
        cacheDirPath = File(tempDir, "cache").absolutePath,
        offline = false
    )

    @Test
    fun `isOfficiallySupported records a skip into the shared bookkeeping when the official artifact already ships the requested target`(
        @TempDir tempDir: File
    ) {
        val groupId = "org.jetbrains.compose.subtest"
        val baseModule = "subtest"
        val version = "1.0.0-subtest-officially-supported"

        val repoDir = File(tempDir, "repo")
        val moduleFile = File(repoDir, "${groupId.replace('.', '/')}/$baseModule/$version/$baseModule-$version.module")
        moduleFile.parentFile.mkdirs()
        moduleFile.writeText(
            """
            {
              "variants": [
                {
                  "name": "tvosArm64ApiElements",
                  "attributes": { "org.jetbrains.kotlin.native.target": "tvos_arm64" },
                  "available-at": { "module": "$baseModule-tvosarm64" }
                }
              ]
            }
            """.trimIndent()
        )

        val supported = ComposeTvosRedirectPlugin().isOfficiallySupported(
            groupId, "$baseModule-tvosarm64", version, configFor(tempDir, repoDir.toURI().toString()), logger
        )

        assertTrue(supported)
        val snapshot = TvosDiagnosticsBookkeeping.diagnosticsSnapshot()
        assertEquals(1, snapshot.skippedAlreadySupported.size)
        val record = snapshot.skippedAlreadySupported.single()
        assertEquals("$groupId:$baseModule:$version", record.sourceModule, "sourceModule must match the exact format TvosVariantInjectionRule uses for the same umbrella module")
        assertEquals(listOf("tvos_arm64"), record.skippedNativeTargets)
    }

    @Test
    fun `isOfficiallySupported records nothing when the official artifact does not ship the requested target`(
        @TempDir tempDir: File
    ) {
        val groupId = "org.jetbrains.compose.subtest2"
        val baseModule = "subtest2"
        val version = "1.0.0-subtest-not-supported"

        // No .module file published anywhere -- fetch fails, officialVariants comes back empty.
        val emptyRepoDir = File(tempDir, "empty-repo")

        val supported = ComposeTvosRedirectPlugin().isOfficiallySupported(
            groupId, "$baseModule-tvosarm64", version, configFor(tempDir, emptyRepoDir.toURI().toString()), logger
        )

        assertFalse(supported)
        val snapshot = TvosDiagnosticsBookkeeping.diagnosticsSnapshot()
        assertEquals(emptyList(), snapshot.skippedAlreadySupported, "nothing must be recorded when the official artifact is not already supported")
    }

    // -- Task 11 (dangling-metadata fix) --------------------------------------------------

    @Test
    fun `isOfficiallySupported returns false when the official available-at target module is confirmed dangling`(
        @TempDir tempDir: File
    ) {
        // Reproduces the confirmed real-world shape (task-11-report.md): the official umbrella
        // advertises a tvOS variant via available-at, but the target module it points at was
        // never actually published anywhere -- dangling metadata that must NOT be trusted at
        // face value.
        val groupId = "org.jetbrains.compose.subtest3"
        val baseModule = "subtest3"
        val version = "1.0.0-subtest-dangling"

        val repoDir = File(tempDir, "repo")
        val moduleFile = File(repoDir, "${groupId.replace('.', '/')}/$baseModule/$version/$baseModule-$version.module")
        moduleFile.parentFile.mkdirs()
        moduleFile.writeText(
            """
            {
              "variants": [
                {
                  "name": "tvosArm64ApiElements",
                  "attributes": { "org.jetbrains.kotlin.native.target": "tvos_arm64" },
                  "available-at": { "group": "$groupId", "module": "$baseModule-tvosarm64", "version": "$version" }
                }
              ]
            }
            """.trimIndent()
        )
        // Deliberately NO "$baseModule-tvosarm64" module published anywhere -- the advertised
        // available-at target dangles.

        val supported = ComposeTvosRedirectPlugin().isOfficiallySupported(
            groupId, "$baseModule-tvosarm64", version, configFor(tempDir, repoDir.toURI().toString()), logger
        )

        assertFalse(supported, "a confirmed-dangling available-at target must not count as officially supported")
        assertEquals(
            emptyList(), TvosDiagnosticsBookkeeping.diagnosticsSnapshot().skippedAlreadySupported,
            "nothing must be recorded as a skip once the advertised target is confirmed dangling"
        )
    }

    @Test
    fun `isOfficiallySupported returns false and records nothing for a non-tvOS-suffixed module name`(
        @TempDir tempDir: File
    ) {
        val supported = ComposeTvosRedirectPlugin().isOfficiallySupported(
            "org.jetbrains.compose.ui", "ui", "1.11.0", configFor(tempDir, File(tempDir, "unused-repo").toURI().toString()), logger
        )

        assertFalse(supported, "a bare umbrella module name (no tvOS suffix) is never officially-supported by this check")
        assertEquals(emptyList(), TvosDiagnosticsBookkeeping.diagnosticsSnapshot().skippedAlreadySupported)
    }
}
