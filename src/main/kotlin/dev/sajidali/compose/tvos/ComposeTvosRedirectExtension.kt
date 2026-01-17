package dev.sajidali.compose.tvos

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Extension for configuring the Compose tvOS Redirect plugin.
 *
 * Usage in Kotlin DSL:
 * ```
 * composeTvos {
 *     targetVersion.set("1.6.0-tvos01")  // Optional: override version
 *     repositoryUrl.set("https://maven.example.com")  // Optional
 *     verbose.set(true)  // Optional: enable logging
 * }
 * ```
 *
 * Usage in Groovy DSL:
 * ```
 * composeTvos {
 *     targetVersion = "1.6.0-tvos01"
 *     repositoryUrl = "https://maven.example.com"
 *     verbose = true
 * }
 * ```
 */
abstract class ComposeTvosRedirectExtension @Inject constructor(
    objects: ObjectFactory
) {
    /**
     * Optional version override for redirected artifacts.
     * If not set, the original dependency version will be used.
     */
    val targetVersion: Property<String> = objects.property(String::class.java)

    /**
     * Optional repository URL for the redirected artifacts.
     * If set, the plugin will add this repository to the project.
     */
    val repositoryUrl: Property<String> = objects.property(String::class.java)

    /**
     * Enable verbose logging to see which dependencies are being redirected.
     * Default: false
     */
    val verbose: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

    /**
     * Redirect all artifacts from monitored Compose groups, not just tvOS-specific ones.
     * Only used when injectTvosVariants is false.
     * Default: false
     */
    val redirectAll: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

    /**
     * Inject tvOS variants into JetBrains Compose umbrella modules instead of redirecting them.
     * This keeps iOS/Android/Desktop using official JetBrains artifacts while only tvOS
     * uses dev.sajidali.* artifacts.
     *
     * When true (default):
     * - Umbrella modules stay from JetBrains
     * - tvOS variants are injected pointing to dev.sajidali.*
     * - Only tvOS-specific artifacts are redirected
     *
     * When false:
     * - Uses redirectAll or smart redirect logic
     * - All platforms may end up using dev.sajidali.* via umbrella module
     *
     * Default: true
     */
    val injectTvosVariants: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

    /**
     * Additional library groups to redirect for tvOS support.
     * Each entry maps a source group prefix to a target group prefix.
     *
     * Example:
     * ```
     * composeTvos {
     *     additionalGroups.put("io.coil-kt.coil3", "dev.sajidali.coil3")
     *     additionalGroups.put("io.insert-koin", "dev.sajidali.koin")
     * }
     * ```
     */
    val additionalGroups: MapProperty<String, String> = objects.mapProperty(String::class.java, String::class.java)

    /**
     * Additional specific artifacts to redirect for tvOS support.
     * Each entry maps a source "group:artifact" to a target "group:artifact".
     * This allows matching by combined group and artifact name.
     *
     * Example:
     * ```
     * composeTvos {
     *     additionalArtifacts.put("io.coil-kt.coil3:coil-compose", "dev.sajidali.coil3:coil-compose")
     *     additionalArtifacts.put("org.example:my-lib", "dev.sajidali.example:my-lib")
     * }
     * ```
     */
    val additionalArtifacts: MapProperty<String, String> = objects.mapProperty(String::class.java, String::class.java)

    companion object {
        const val NAME = "composeTvos"
    }
}
