package dev.sajidali.compose.tvos

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Extension for configuring the Compose tvOS Redirect plugin.
 *
 * Usage in settings.gradle.kts:
 * ```kotlin
 * plugins {
 *     id("dev.sajidali.compose-tvos") version "x.x.x"
 * }
 *
 * composeTvos {
 *     verbose.set(true)
 * }
 * ```
 */
abstract class ComposeTvosRedirectSettingsExtension @Inject constructor(
    objects: ObjectFactory
) {
    /**
     * Enable verbose logging to see variant discovery and dependency redirection.
     * Default: false
     */
    val verbose: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

    /**
     * Optional version override for all redirected artifacts.
     * If not set, version mappings or the original dependency version will be used.
     */
    val targetVersion: Property<String> = objects.property(String::class.java)

    /**
     * Optional repository URL to prioritize for variant discovery.
     * If not set, uses repositories from dependencyResolutionManagement.
     */
    val repositoryUrl: Property<String> = objects.property(String::class.java)

    /**
     * URL of the version override manifest. The plugin defaults the unmapped case to
     * the original requested version (same-version convention), so this manifest only
     * needs to list exceptions where the tvOS fork is published at a different version
     * than upstream (e.g. material3 alpha tracks).
     *
     * Set to an empty string to disable manifest fetching entirely.
     */
    val manifestUrl: Property<String> = objects.property(String::class.java)
        .convention("https://raw.githubusercontent.com/sajidalidev/compose-tvos/main/manifest/compose-tvos-versions.json")

    /**
     * Additional library groups to redirect for tvOS support.
     * Maps source group ID to target group ID.
     *
     * Example:
     * ```kotlin
     * composeTvos {
     *     additionalGroups.put("io.insert-koin", "dev.sajidali.koin")
     * }
     * ```
     */
    val additionalGroups: MapProperty<String, String> =
        objects.mapProperty(String::class.java, String::class.java)

    /**
     * Additional specific artifacts to redirect.
     * Maps source "group:artifact" to target "group:artifact".
     *
     * Example:
     * ```kotlin
     * composeTvos {
     *     additionalArtifacts.put("io.coil-kt.coil3:coil-compose", "dev.sajidali.coil3:coil-compose")
     * }
     * ```
     */
    val additionalArtifacts: MapProperty<String, String> =
        objects.mapProperty(String::class.java, String::class.java)

    /**
     * Version mappings for translating source versions to target versions.
     *
     * Key format: "scope:versionPattern" where scope can be:
     * - "groupId" for exact group match
     * - "groupId.*" for group pattern match
     * - "*" for global match
     *
     * Examples:
     * ```kotlin
     * composeTvos {
     *     versionMappings.put("org.jetbrains.compose.*:1.10.*", "1.10.0")
     *     versionMappings.put("io.insert-koin:*", "3.5.0")
     * }
     * ```
     */
    val versionMappings: MapProperty<String, String> =
        objects.mapProperty(String::class.java, String::class.java)

    companion object {
        const val NAME = "composeTvos"
    }
}