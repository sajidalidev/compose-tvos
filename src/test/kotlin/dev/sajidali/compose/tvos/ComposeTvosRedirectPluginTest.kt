package dev.sajidali.compose.tvos

import org.gradle.testfixtures.ProjectBuilder
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
    fun `parseModuleMetadata skips non-Api variants`() {
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
            }
          ]
        }
        """.trimIndent()

        val variants = TvosVariantDiscovery.parseModuleMetadata(json, baseArtifactId = "ui")
        assertEquals(1, variants.size)
        assertEquals("tvosArm64ApiElements", variants[0].variantName)
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
    fun `resolveVersion falls back to original version`() {
        assertEquals("1.0.0", ComposeVersions.resolveVersion("org.example", "lib", "1.0.0", emptyMap(), null))
    }

    @Test
    fun `resolveVersion uses override when specified`() {
        assertEquals("override", ComposeVersions.resolveVersion("org.example", "lib", "1.0.0", emptyMap(), "override"))
    }

    @Test
    fun `normalizeMappings adds global scope`() {
        val result = ComposeVersions.normalizeMappings(mapOf("1.10.0" to "mapped"))
        assertEquals("mapped", result["*:1.10.0"])
    }
}
