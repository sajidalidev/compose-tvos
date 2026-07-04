package dev.sajidali.compose.tvos

import org.gradle.api.Plugin
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.initialization.Settings
import org.gradle.api.logging.Logging
import java.io.File

/**
 * Settings plugin that adds tvOS support to JetBrains Compose Multiplatform projects.
 *
 * This plugin injects tvOS variants from alternative artifacts into the official
 * JetBrains Compose modules, allowing tvOS builds while other platforms continue
 * using official JetBrains artifacts.
 *
 * Usage in settings.gradle.kts:
 * ```kotlin
 * plugins {
 *     id("dev.sajidali.compose-tvos") version "x.x.x"
 * }
 *
 * composeTvos {
 *     verbose.set(true) // Optional: enable logging
 * }
 * ```
 */
class ComposeTvosRedirectSettingsPlugin : Plugin<Settings> {

    private val logger = Logging.getLogger(ComposeTvosRedirectSettingsPlugin::class.java)

    override fun apply(settings: Settings) {
        val extension = settings.extensions.create(
            ComposeTvosRedirectSettingsExtension.NAME,
            ComposeTvosRedirectSettingsExtension::class.java
        )

        // The directory Gradle itself resolved from GRADLE_USER_HOME / --gradle-user-home /
        // the default `~/.gradle` — computed once and shared by both disk caches below so
        // neither silently drifts from the Gradle invocation actually running the build
        // (e.g. under GradleTestKit, where it differs from `user.home`).
        val cacheRoot = settings.startParameter.gradleUserHomeDir

        settings.gradle.settingsEvaluated { evaluatedSettings ->
            val verbose = extension.verbose.get()
            val repositoryUrls = collectRepositoryUrls(evaluatedSettings, extension)
            val manifestMappings = VersionManifestLoader.load(
                manifestUrl = extension.manifestUrl.orNull,
                cacheDir = cacheRoot,
                refreshDependencies = evaluatedSettings.gradle.startParameter.isRefreshDependencies,
                logger = if (verbose) logger else null
            )

            if (verbose) {
                logger.lifecycle("[ComposeTvosRedirect] Settings plugin applied")
                logger.lifecycle("[ComposeTvosRedirect] Found ${repositoryUrls.size} repositories")
                repositoryUrls.forEach { logger.lifecycle("[ComposeTvosRedirect]   - $it") }
                logger.lifecycle("[ComposeTvosRedirect] Loaded ${manifestMappings.size} version override(s) from manifest")
            }

            configureComponentMetadataRules(evaluatedSettings, extension, repositoryUrls, manifestMappings, cacheRoot)

            val config = PluginConfiguration(
                verbose = extension.verbose.get(),
                targetVersion = extension.targetVersion.orNull,
                additionalGroups = extension.additionalGroups.get(),
                additionalArtifacts = extension.additionalArtifacts.get(),
                versionMappings = extension.versionMappings.get(),
                manifestMappings = manifestMappings,
                repositoryUrls = repositoryUrls
            )

            evaluatedSettings.gradle.beforeProject { project ->
                project.extensions.extraProperties.set(SHARED_CONFIG_KEY, config)
                project.plugins.apply(ComposeTvosRedirectPlugin::class.java)
            }
        }
    }

    private fun collectRepositoryUrls(
        settings: Settings,
        extension: ComposeTvosRedirectSettingsExtension
    ): List<String> {
        val urls = mutableListOf<String>()

        extension.repositoryUrl.orNull?.let { urls.add(it) }

        settings.dependencyResolutionManagement.repositories
            .filterIsInstance<MavenArtifactRepository>()
            .forEach { repo ->
                val url = repo.url.toString()
                if (url !in urls) urls.add(url)
            }

        if (urls.isEmpty()) {
            urls.add("file://${System.getProperty("user.home")}/.m2/repository")
            urls.add("https://maven.pkg.jetbrains.space/public/p/compose/dev")
            urls.add("https://repo.maven.apache.org/maven2")
        }

        return urls
    }

    private fun configureComponentMetadataRules(
        settings: Settings,
        extension: ComposeTvosRedirectSettingsExtension,
        repositoryUrls: List<String>,
        manifestMappings: Map<String, String>,
        cacheDir: File
    ) {
        val verbose = extension.verbose.get()
        val targetVersionOverride = extension.targetVersion.orNull
        val additionalGroups = extension.additionalGroups.get()
        val additionalArtifacts = extension.additionalArtifacts.get()

        // User mappings overwrite manifest mappings on key collision (later putAll wins).
        val versionMappings = mutableMapOf<String, String>()
        versionMappings.putAll(ComposeVersions.normalizeMappings(manifestMappings))
        versionMappings.putAll(ComposeVersions.normalizeMappings(extension.versionMappings.get()))

        // Build artifact mappings
        val artifactMappings = mutableMapOf<String, Pair<String, String>>()
        ComposeArtifacts.ALL.forEach { sourceCoordinate ->
            val parts = sourceCoordinate.split(":")
            if (parts.size == 2) {
                ComposeArtifacts.getTargetMapping(parts[0], parts[1])?.let {
                    artifactMappings[sourceCoordinate] = it
                }
            }
        }
        additionalArtifacts.forEach { (source, target) ->
            val sourceParts = source.split(":")
            val targetParts = target.split(":")
            if (sourceParts.size == 2 && targetParts.size == 2) {
                artifactMappings[source] = Pair(targetParts[0], targetParts[1])
            }
        }

        // Build group mappings
        val groupMappings = mutableMapOf<String, String>()
        ComposeModules.ALL.forEach { group ->
            groupMappings[group] = TvosArtifactMapping.mapGroupId(group)
        }
        groupMappings.putAll(additionalGroups)

        // Variant cache
        val variantCache = mutableMapOf<String, List<TvosVariant>>()

        fun getVariants(targetGroup: String, targetArtifact: String, version: String): List<TvosVariant> {
            val key = "$targetGroup:$targetArtifact:$version"
            return variantCache.getOrPut(key) {
                TvosVariantDiscovery.discoverVariants(
                    repositoryUrls, targetGroup, targetArtifact, version, cacheDir,
                    if (verbose) logger else null
                )
            }
        }

        // Register rules for artifact mappings
        artifactMappings.forEach { (sourceCoordinate, targetPair) ->
            val (sourceGroup, sourceArtifact) = sourceCoordinate.split(":")
            val (targetGroup, targetArtifact) = targetPair

            settings.dependencyResolutionManagement.components.withModule("$sourceGroup:$sourceArtifact") { metadata ->
                val id = metadata.id
                if (TvosArtifactMapping.isUmbrellaModule(id.module.name)) {
                    val version = ComposeVersions.resolveVersion(
                        id.group, id.module.name, id.version, versionMappings, targetVersionOverride
                    )
                    val variants = getVariants(targetGroup, targetArtifact, version)
                    if (variants.isNotEmpty()) {
                        if (verbose) {
                            logger.lifecycle("[ComposeTvosRedirect] Injecting ${variants.size} tvOS variants into: ${id.group}:${id.module.name}:${id.version}")
                        }
                        injectTvosVariants(metadata, targetGroup, version, variants)
                    }
                }
            }
        }

        // Register rules for group mappings
        groupMappings.forEach { (sourceGroup, targetGroup) ->
            settings.dependencyResolutionManagement.components.all { metadata ->
                val id = metadata.id
                val moduleName = id.module.name
                val artifactKey = "${id.group}:$moduleName"

                if (artifactMappings.containsKey(artifactKey)) return@all

                if (id.group == sourceGroup && TvosArtifactMapping.isUmbrellaModule(moduleName)) {
                    val version = ComposeVersions.resolveVersion(
                        id.group, moduleName, id.version, versionMappings, targetVersionOverride
                    )
                    val variants = getVariants(targetGroup, moduleName, version)
                    if (variants.isNotEmpty()) {
                        if (verbose) {
                            logger.lifecycle("[ComposeTvosRedirect] Injecting ${variants.size} tvOS variants into: ${id.group}:$moduleName:${id.version}")
                        }
                        injectTvosVariants(metadata, targetGroup, version, variants)
                    }
                }
            }
        }

        if (verbose) {
            logger.lifecycle("[ComposeTvosRedirect] Component metadata rules configured")
        }
    }

    private fun injectTvosVariants(
        metadata: org.gradle.api.artifacts.ComponentMetadataDetails,
        targetGroup: String,
        version: String,
        variants: List<TvosVariant>
    ) {
        variants.forEach { variant ->
            metadata.addVariant("${variant.variantName}-injected") { variantMetadata ->
                variantMetadata.attributes { attrs ->
                    val attributesToApply = if (variant.attributes.isNotEmpty()) {
                        variant.attributes
                    } else {
                        // Fallback for cache entries predating attribute capture. This
                        // covers the common kotlin-api request path but does NOT cover
                        // metadata / sources / runtime variant lookups — if a consumer
                        // reports unresolved references in a shared source set (e.g.
                        // `appleMain`) after upgrading, deleting the cache directory
                        // (<gradleUserHome>/compose-tvos-redirect-cache-v3/, `~/.gradle` by
                        // default) forces re-discovery
                        // with the full attribute set.
                        mapOf(
                            "org.gradle.category" to "library",
                            "org.gradle.usage" to "kotlin-api",
                            "org.gradle.jvm.environment" to "non-jvm",
                            "org.jetbrains.kotlin.platform.type" to "native",
                            "org.jetbrains.kotlin.native.target" to variant.nativeTarget
                        )
                    }
                    attributesToApply.forEach { (key, value) ->
                        attrs.attribute(
                            org.gradle.api.attributes.Attribute.of(key, String::class.java),
                            value
                        )
                    }
                }
                variantMetadata.withDependencies { deps ->
                    deps.add("$targetGroup:${variant.artifactId}:$version")
                }
            }
        }
    }

    companion object {
        const val SHARED_CONFIG_KEY = "composeTvosRedirect.config"
    }
}

/**
 * Configuration shared between settings and project plugins.
 */
data class PluginConfiguration(
    val verbose: Boolean,
    val targetVersion: String?,
    val additionalGroups: Map<String, String>,
    val additionalArtifacts: Map<String, String>,
    val versionMappings: Map<String, String>,
    val manifestMappings: Map<String, String>,
    val repositoryUrls: List<String>
) : java.io.Serializable
