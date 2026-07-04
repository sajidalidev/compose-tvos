package dev.sajidali.compose.tvos

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir
import java.io.File
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
}

class ComposeModulesTest {

    @Test
    fun `ALL contains all Compose module groups`() {
        assertEquals(8, ComposeModules.ALL.size)
        assertTrue(ComposeModules.ALL.contains("org.jetbrains.compose.ui"))
        assertTrue(ComposeModules.ALL.contains("org.jetbrains.compose.foundation"))
        assertTrue(ComposeModules.ALL.contains("org.jetbrains.compose.runtime"))
        assertTrue(ComposeModules.ALL.contains("org.jetbrains.compose.material"))
        assertTrue(ComposeModules.ALL.contains("org.jetbrains.compose.material3"))
        assertTrue(ComposeModules.ALL.contains("org.jetbrains.compose.animation"))
        assertTrue(ComposeModules.ALL.contains("org.jetbrains.compose.components"))
        assertTrue(ComposeModules.ALL.contains("org.jetbrains.androidx.navigation"))
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
}
