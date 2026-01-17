package dev.sajidali.compose.tvos

/**
 * tvOS target suffixes used in Kotlin Multiplatform artifact names.
 * Note: Artifact names typically use lowercase suffixes.
 */
object TvosTargets {
    const val TVOS_ARM64 = "tvosarm64"
    const val TVOS_X64 = "tvosx64"
    const val TVOS_SIMULATOR_ARM64 = "tvossimulatorarm64"

    val ALL = setOf(TVOS_ARM64, TVOS_X64, TVOS_SIMULATOR_ARM64)

    // Check if module name ends with any tvOS suffix (case-insensitive)
    fun matchesTvosSuffix(moduleName: String): Boolean {
        val lowerModule = moduleName.lowercase()
        return ALL.any { suffix -> lowerModule.endsWith("-$suffix") }
    }
}

/**
 * JetBrains Compose Multiplatform group IDs that should be redirected for tvOS targets.
 */
object ComposeModules {
    const val UI = "org.jetbrains.compose.ui"
    const val FOUNDATION = "org.jetbrains.compose.foundation"
    const val RUNTIME = "org.jetbrains.compose.runtime"
    const val MATERIAL = "org.jetbrains.compose.material"
    const val MATERIAL3 = "org.jetbrains.compose.material3"
    const val ANIMATION = "org.jetbrains.compose.animation"
    const val COMPONENTS = "org.jetbrains.compose.components"

    const val NAVIGATION = "org.jetbrains.androidx.navigation"

    val ALL = setOf(
        UI,
        FOUNDATION,
        RUNTIME,
        MATERIAL,
        MATERIAL3,
        ANIMATION,
        COMPONENTS,
        NAVIGATION
    )
}

/**
 * Predefined artifact mappings for specific group:artifact combinations.
 * Maps source "group:artifact" to target "group:artifact".
 *
 * Use this for libraries that need artifact-level matching rather than group-level matching.
 */
object ComposeArtifacts {
    private const val JETBRAINS_PREFIX = "org.jetbrains"
    private const val TARGET_PREFIX = "dev.sajidali"

    // Navigation
    const val NAVIGATION_COMPOSE = "org.jetbrains.androidx.navigation:navigation-compose"

    // Lifecycle
    const val LIFECYCLE_VIEWMODEL_COMPOSE = "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose"
    const val LIFECYCLE_VIEWMODEL_NAVIGATION3 = "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-navigation3"

    const val NAVIGATION3_UI = "org.jetbrains.androidx.navigation3:navigation3-ui"

    const val NAVIGATIONEVENT_COMPOSE = "org.jetbrains.androidx.navigation:navigationevent-compose"

    const val SAVEDSTATE_COMPOSE = "org.jetbrains.androidx.savedstate:savedstate-compose"

    /**
     * All predefined artifact coordinates (group:artifact format).
     */
    val ALL = setOf(
        NAVIGATION_COMPOSE,
        LIFECYCLE_VIEWMODEL_COMPOSE,
        LIFECYCLE_VIEWMODEL_NAVIGATION3,
        NAVIGATION3_UI,
        NAVIGATIONEVENT_COMPOSE,
        SAVEDSTATE_COMPOSE
    )

    /**
     * Maps source artifact coordinate to target artifact coordinate.
     * Example: "org.jetbrains.androidx.navigation:navigation-compose" -> "dev.sajidali.androidx.navigation:navigation-compose"
     */
    fun mapArtifact(sourceCoordinate: String): String {
        return sourceCoordinate.replace(JETBRAINS_PREFIX, TARGET_PREFIX)
    }

    /**
     * Checks if the given group:artifact coordinate is a predefined Compose artifact.
     */
    fun isComposeArtifact(groupId: String, artifactId: String): Boolean {
        return "$groupId:$artifactId" in ALL
    }

    /**
     * Gets the target group and artifact for a source coordinate.
     * Returns Pair(targetGroup, targetArtifact) or null if not a predefined artifact.
     */
    fun getTargetMapping(groupId: String, artifactId: String): Pair<String, String>? {
        val sourceCoordinate = "$groupId:$artifactId"
        if (sourceCoordinate !in ALL) return null

        val targetCoordinate = mapArtifact(sourceCoordinate)
        val parts = targetCoordinate.split(":")
        return if (parts.size == 2) Pair(parts[0], parts[1]) else null
    }
}

/**
 * Non-tvOS platform suffixes that should NOT be redirected.
 */
object OtherPlatformSuffixes {
    val ALL = setOf(
        // iOS
        "iosarm64", "iosx64", "iossimulatorarm64",
        "uikitarm64", "uikitx64", "uikitsimulatorarm64",
        // Android
        "android", "android-debug", "android-release",
        // Desktop/JVM
        "desktop", "jvm",
        // macOS
        "macosarm64", "macosx64",
        // watchOS
        "watchosarm32", "watchosarm64", "watchosx64", "watchossimulatorarm64", "watchosdevicearm64",
        // Linux
        "linuxarm64", "linuxx64",
        // Windows
        "mingwx64",
        // Web
        "js", "wasmjs", "wasm"
    )
}

/**
 * Utility functions for tvOS artifact detection and mapping.
 */
object TvosArtifactMapping {
    private const val SOURCE_PREFIX = "org.jetbrains"
    private const val TARGET_PREFIX = "dev.sajidali"

    /**
     * Checks if the given module name is a tvOS artifact (case-insensitive).
     * tvOS artifacts end with one of the tvOS target suffixes (e.g., ui-tvosArm64).
     */
    fun isTvosArtifact(moduleName: String): Boolean {
        return TvosTargets.matchesTvosSuffix(moduleName)
    }

    /**
     * Checks if the given module name is a non-tvOS platform-specific artifact.
     */
    fun isOtherPlatformArtifact(moduleName: String): Boolean {
        return OtherPlatformSuffixes.ALL.any { suffix ->
            moduleName.endsWith("-$suffix")
        }
    }

    /**
     * Checks if the given module name is an umbrella/common module (no platform suffix).
     */
    fun isUmbrellaModule(moduleName: String): Boolean {
        return !isTvosArtifact(moduleName) && !isOtherPlatformArtifact(moduleName)
    }

    /**
     * Checks if the given group ID is a JetBrains Compose group that should be redirected.
     */
    fun isComposeGroup(groupId: String): Boolean {
        return groupId in ComposeModules.ALL
    }

    /**
     * Maps a JetBrains Compose group ID to the corresponding dev.sajidali group.
     * Example: org.jetbrains.compose.ui -> dev.sajidali.compose.ui
     */
    fun mapGroupId(sourceGroupId: String): String {
        return sourceGroupId.replace(SOURCE_PREFIX, TARGET_PREFIX)
    }

    /**
     * Determines if a dependency should be redirected based on group and module.
     * Only redirects tvOS-specific artifacts.
     */
    fun shouldRedirect(groupId: String, moduleName: String): Boolean {
        return isComposeGroup(groupId) && isTvosArtifact(moduleName)
    }

    /**
     * Determines if a dependency should be redirected for tvOS support.
     * Redirects:
     * - tvOS-specific artifacts (e.g., ui-tvosarm64)
     * - Umbrella/common modules (e.g., ui, foundation) - needed for tvOS variant resolution
     * Does NOT redirect:
     * - iOS, Android, Desktop, or other platform-specific artifacts
     */
    fun shouldRedirectForTvos(groupId: String, moduleName: String): Boolean {
        if (!isComposeGroup(groupId)) return false

        // Redirect tvOS artifacts
        if (isTvosArtifact(moduleName)) return true

        // Redirect umbrella modules (needed for tvOS variant resolution)
        if (isUmbrellaModule(moduleName)) return true

        // Don't redirect other platform-specific artifacts
        return false
    }
}
