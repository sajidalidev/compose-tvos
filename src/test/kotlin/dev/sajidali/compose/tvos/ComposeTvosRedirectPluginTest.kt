package dev.sajidali.compose.tvos

import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ComposeTvosRedirectPluginTest {

    @Test
    fun `plugin can be applied to project`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("dev.sajidali.compose-tvos-redirect")

        assertNotNull(project.plugins.findPlugin(ComposeTvosRedirectPlugin::class.java))
    }

    @Test
    fun `extension is created with default values`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("dev.sajidali.compose-tvos-redirect")

        val extension = project.extensions.findByType(ComposeTvosRedirectExtension::class.java)
        assertNotNull(extension)
        assertFalse(extension.verbose.get())
        assertFalse(extension.targetVersion.isPresent)
        assertFalse(extension.repositoryUrl.isPresent)
        assertTrue(extension.additionalGroups.get().isEmpty())
        assertTrue(extension.additionalArtifacts.get().isEmpty())
    }

    @Test
    fun `extension values can be configured`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("dev.sajidali.compose-tvos-redirect")

        val extension = project.extensions.findByType(ComposeTvosRedirectExtension::class.java)!!
        extension.targetVersion.set("1.6.0-tvos01")
        extension.repositoryUrl.set("https://maven.example.com")
        extension.verbose.set(true)

        assertEquals("1.6.0-tvos01", extension.targetVersion.get())
        assertEquals("https://maven.example.com", extension.repositoryUrl.get())
        assertTrue(extension.verbose.get())
    }

    @Test
    fun `additionalGroups can be configured`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("dev.sajidali.compose-tvos-redirect")

        val extension = project.extensions.findByType(ComposeTvosRedirectExtension::class.java)!!
        extension.additionalGroups.put("io.coil-kt.coil3", "dev.sajidali.coil3")
        extension.additionalGroups.put("io.insert-koin", "dev.sajidali.koin")

        val groups = extension.additionalGroups.get()
        assertEquals(2, groups.size)
        assertEquals("dev.sajidali.coil3", groups["io.coil-kt.coil3"])
        assertEquals("dev.sajidali.koin", groups["io.insert-koin"])
    }

    @Test
    fun `additionalArtifacts can be configured`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("dev.sajidali.compose-tvos-redirect")

        val extension = project.extensions.findByType(ComposeTvosRedirectExtension::class.java)!!
        extension.additionalArtifacts.put("io.coil-kt.coil3:coil-compose", "dev.sajidali.coil3:coil-compose")
        extension.additionalArtifacts.put("org.example:my-lib", "dev.sajidali.example:my-lib")

        val artifacts = extension.additionalArtifacts.get()
        assertEquals(2, artifacts.size)
        assertEquals("dev.sajidali.coil3:coil-compose", artifacts["io.coil-kt.coil3:coil-compose"])
        assertEquals("dev.sajidali.example:my-lib", artifacts["org.example:my-lib"])
    }
}

class TvosArtifactMappingTest {

    @Test
    fun `isTvosArtifact returns true for tvosarm64 artifacts`() {
        assertTrue(TvosArtifactMapping.isTvosArtifact("ui-tvosarm64"))
        assertTrue(TvosArtifactMapping.isTvosArtifact("foundation-tvosarm64"))
        assertTrue(TvosArtifactMapping.isTvosArtifact("runtime-tvosarm64"))
    }

    @Test
    fun `isTvosArtifact returns true for tvosx64 artifacts`() {
        assertTrue(TvosArtifactMapping.isTvosArtifact("ui-tvosx64"))
        assertTrue(TvosArtifactMapping.isTvosArtifact("foundation-tvosx64"))
    }

    @Test
    fun `isTvosArtifact returns true for tvossimulatorarm64 artifacts`() {
        assertTrue(TvosArtifactMapping.isTvosArtifact("ui-tvossimulatorarm64"))
        assertTrue(TvosArtifactMapping.isTvosArtifact("foundation-tvossimulatorarm64"))
    }

    @Test
    fun `isTvosArtifact returns false for non-tvOS artifacts`() {
        assertFalse(TvosArtifactMapping.isTvosArtifact("ui-iosarm64"))
        assertFalse(TvosArtifactMapping.isTvosArtifact("ui-iossimulatorarm64"))
        assertFalse(TvosArtifactMapping.isTvosArtifact("ui-android"))
        assertFalse(TvosArtifactMapping.isTvosArtifact("ui-desktop"))
        assertFalse(TvosArtifactMapping.isTvosArtifact("ui"))
    }

    @Test
    fun `isComposeGroup returns true for JetBrains Compose groups`() {
        assertTrue(TvosArtifactMapping.isComposeGroup("org.jetbrains.compose.ui"))
        assertTrue(TvosArtifactMapping.isComposeGroup("org.jetbrains.compose.foundation"))
        assertTrue(TvosArtifactMapping.isComposeGroup("org.jetbrains.compose.runtime"))
        assertTrue(TvosArtifactMapping.isComposeGroup("org.jetbrains.compose.material"))
        assertTrue(TvosArtifactMapping.isComposeGroup("org.jetbrains.compose.material3"))
        assertTrue(TvosArtifactMapping.isComposeGroup("org.jetbrains.compose.animation"))
        assertTrue(TvosArtifactMapping.isComposeGroup("org.jetbrains.compose.components"))
    }

    @Test
    fun `isComposeGroup returns false for non-Compose groups`() {
        assertFalse(TvosArtifactMapping.isComposeGroup("org.jetbrains.kotlin"))
        assertFalse(TvosArtifactMapping.isComposeGroup("androidx.compose.ui"))
        assertFalse(TvosArtifactMapping.isComposeGroup("com.google.android"))
        assertFalse(TvosArtifactMapping.isComposeGroup("dev.sajidali.compose.ui"))
    }

    @Test
    fun `mapGroupId correctly maps JetBrains to sajidali`() {
        assertEquals("dev.sajidali.compose.ui", TvosArtifactMapping.mapGroupId("org.jetbrains.compose.ui"))
        assertEquals("dev.sajidali.compose.foundation", TvosArtifactMapping.mapGroupId("org.jetbrains.compose.foundation"))
        assertEquals("dev.sajidali.compose.runtime", TvosArtifactMapping.mapGroupId("org.jetbrains.compose.runtime"))
        assertEquals("dev.sajidali.compose.material", TvosArtifactMapping.mapGroupId("org.jetbrains.compose.material"))
        assertEquals("dev.sajidali.compose.material3", TvosArtifactMapping.mapGroupId("org.jetbrains.compose.material3"))
        assertEquals("dev.sajidali.compose.animation", TvosArtifactMapping.mapGroupId("org.jetbrains.compose.animation"))
        assertEquals("dev.sajidali.compose.components", TvosArtifactMapping.mapGroupId("org.jetbrains.compose.components"))
    }

    @Test
    fun `shouldRedirect returns true for tvOS Compose artifacts`() {
        assertTrue(TvosArtifactMapping.shouldRedirect("org.jetbrains.compose.ui", "ui-tvosarm64"))
        assertTrue(TvosArtifactMapping.shouldRedirect("org.jetbrains.compose.foundation", "foundation-tvossimulatorarm64"))
        assertTrue(TvosArtifactMapping.shouldRedirect("org.jetbrains.compose.runtime", "runtime-tvosx64"))
    }

    @Test
    fun `shouldRedirect returns false for non-tvOS Compose artifacts`() {
        assertFalse(TvosArtifactMapping.shouldRedirect("org.jetbrains.compose.ui", "ui-iosarm64"))
        assertFalse(TvosArtifactMapping.shouldRedirect("org.jetbrains.compose.ui", "ui-android"))
        assertFalse(TvosArtifactMapping.shouldRedirect("org.jetbrains.compose.ui", "ui"))
    }

    @Test
    fun `shouldRedirect returns false for non-Compose tvOS artifacts`() {
        assertFalse(TvosArtifactMapping.shouldRedirect("org.jetbrains.kotlin", "kotlin-stdlib-tvosarm64"))
        assertFalse(TvosArtifactMapping.shouldRedirect("com.example", "example-tvosarm64"))
    }
}

class TvosTargetsTest {

    @Test
    fun `ALL contains all tvOS target suffixes`() {
        assertEquals(3, TvosTargets.ALL.size)
        assertTrue(TvosTargets.ALL.contains(TvosTargets.TVOS_ARM64))
        assertTrue(TvosTargets.ALL.contains(TvosTargets.TVOS_X64))
        assertTrue(TvosTargets.ALL.contains(TvosTargets.TVOS_SIMULATOR_ARM64))
    }
}

class ComposeModulesTest {

    @Test
    fun `ALL contains all Compose module groups`() {
        assertEquals(8, ComposeModules.ALL.size)
        assertTrue(ComposeModules.ALL.contains(ComposeModules.UI))
        assertTrue(ComposeModules.ALL.contains(ComposeModules.FOUNDATION))
        assertTrue(ComposeModules.ALL.contains(ComposeModules.RUNTIME))
        assertTrue(ComposeModules.ALL.contains(ComposeModules.MATERIAL))
        assertTrue(ComposeModules.ALL.contains(ComposeModules.MATERIAL3))
        assertTrue(ComposeModules.ALL.contains(ComposeModules.ANIMATION))
        assertTrue(ComposeModules.ALL.contains(ComposeModules.COMPONENTS))
        assertTrue(ComposeModules.ALL.contains(ComposeModules.NAVIGATION))
    }
}

class ComposeArtifactsTest {

    @Test
    fun `ALL contains predefined artifact coordinates`() {
        assertEquals(3, ComposeArtifacts.ALL.size)
        assertTrue(ComposeArtifacts.ALL.contains(ComposeArtifacts.NAVIGATION_COMPOSE))
        assertTrue(ComposeArtifacts.ALL.contains(ComposeArtifacts.LIFECYCLE_VIEWMODEL_COMPOSE))
        assertTrue(ComposeArtifacts.ALL.contains(ComposeArtifacts.LIFECYCLE_RUNTIME_COMPOSE))
    }

    @Test
    fun `isComposeArtifact returns true for predefined artifacts`() {
        assertTrue(ComposeArtifacts.isComposeArtifact("org.jetbrains.androidx.navigation", "navigation-compose"))
        assertTrue(ComposeArtifacts.isComposeArtifact("org.jetbrains.androidx.lifecycle", "lifecycle-viewmodel-compose"))
        assertTrue(ComposeArtifacts.isComposeArtifact("org.jetbrains.androidx.lifecycle", "lifecycle-runtime-compose"))
    }

    @Test
    fun `isComposeArtifact returns false for non-predefined artifacts`() {
        assertFalse(ComposeArtifacts.isComposeArtifact("org.jetbrains.compose.ui", "ui"))
        assertFalse(ComposeArtifacts.isComposeArtifact("com.example", "my-lib"))
    }

    @Test
    fun `mapArtifact correctly maps JetBrains to sajidali`() {
        assertEquals(
            "dev.sajidali.androidx.navigation:navigation-compose",
            ComposeArtifacts.mapArtifact("org.jetbrains.androidx.navigation:navigation-compose")
        )
        assertEquals(
            "dev.sajidali.androidx.lifecycle:lifecycle-viewmodel-compose",
            ComposeArtifacts.mapArtifact("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose")
        )
    }

    @Test
    fun `getTargetMapping returns correct mapping for predefined artifacts`() {
        val navigationMapping = ComposeArtifacts.getTargetMapping("org.jetbrains.androidx.navigation", "navigation-compose")
        assertNotNull(navigationMapping)
        assertEquals("dev.sajidali.androidx.navigation", navigationMapping!!.first)
        assertEquals("navigation-compose", navigationMapping.second)

        val lifecycleMapping = ComposeArtifacts.getTargetMapping("org.jetbrains.androidx.lifecycle", "lifecycle-viewmodel-compose")
        assertNotNull(lifecycleMapping)
        assertEquals("dev.sajidali.androidx.lifecycle", lifecycleMapping!!.first)
        assertEquals("lifecycle-viewmodel-compose", lifecycleMapping.second)
    }

    @Test
    fun `getTargetMapping returns null for non-predefined artifacts`() {
        val mapping = ComposeArtifacts.getTargetMapping("com.example", "my-lib")
        assertEquals(null, mapping)
    }
}
