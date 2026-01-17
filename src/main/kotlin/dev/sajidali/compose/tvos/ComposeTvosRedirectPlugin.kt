package dev.sajidali.compose.tvos

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ComponentMetadataDetails
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.logging.Logger

/**
 * Gradle plugin that redirects JetBrains Compose Multiplatform tvOS dependencies
 * to dev.sajidali.* artifacts while leaving all other platforms unchanged.
 *
 * Default behavior (injectTvosVariants = true):
 * - Keeps JetBrains umbrella modules (iOS/Android/Desktop use official artifacts)
 * - Injects tvOS variants into JetBrains umbrella modules pointing to dev.sajidali.*
 * - Redirects tvOS-specific artifacts to dev.sajidali.*
 *
 * Legacy behavior (injectTvosVariants = false, redirectAll = false):
 * - Redirects umbrella modules + tvOS artifacts to dev.sajidali.*
 * - Other platforms also end up using dev.sajidali.* via umbrella module
 */
class ComposeTvosRedirectPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create(
            ComposeTvosRedirectExtension.NAME,
            ComposeTvosRedirectExtension::class.java
        )

        project.afterEvaluate {
            configureRepositoryIfNeeded(project, extension)

            if (extension.injectTvosVariants.get()) {
                // New approach: inject tvOS variants into JetBrains umbrella modules
                configureTvosVariantInjection(project, extension, project.logger)
            }

            configureDependencySubstitution(project, extension, project.logger)
        }
    }

    private fun configureRepositoryIfNeeded(project: Project, extension: ComposeTvosRedirectExtension) {
        if (extension.repositoryUrl.isPresent) {
            val repoUrl = extension.repositoryUrl.get()
            project.repositories.maven { repo ->
                repo.setUrl(repoUrl)
                repo.name = "composeTvosRedirect"
            }
            if (extension.verbose.get()) {
                project.logger.lifecycle("[ComposeTvosRedirect] Added repository: $repoUrl")
            }
        }
    }

    private fun configureTvosVariantInjection(
        project: Project,
        extension: ComposeTvosRedirectExtension,
        logger: Logger
    ) {
        val verbose = extension.verbose.get()
        val targetVersion = extension.targetVersion.orNull
        val additionalGroups: Map<String, String> = extension.additionalGroups.get()
        val additionalArtifacts: Map<String, String> = extension.additionalArtifacts.get()

        // Build artifact mappings: predefined ComposeArtifacts + additionalArtifacts from extension
        val artifactMappings = buildArtifactMappings(additionalArtifacts, verbose, logger)

        // Build combined group mappings: Compose groups + additional groups
        val allGroupMappings = mutableMapOf<String, String>()
        // Compose groups: org.jetbrains.compose.* -> dev.sajidali.compose.*
        ComposeModules.ALL.forEach { composeGroup ->
            allGroupMappings[composeGroup] = TvosArtifactMapping.mapGroupId(composeGroup)
        }
        // Additional groups from extension
        allGroupMappings.putAll(additionalGroups)

        // Register component metadata rules for artifact-level mappings
        artifactMappings.forEach { (sourceCoordinate, targetPair) ->
            val (sourceGroup, sourceArtifact) = sourceCoordinate.split(":")
            val (targetGroup, targetArtifact) = targetPair

            project.dependencies.components.all { metadata ->
                val id = metadata.id
                val moduleName = id.module.name
                if (id.group == sourceGroup && moduleName == sourceArtifact && TvosArtifactMapping.isUmbrellaModule(moduleName)) {
                    val version = targetVersion ?: id.version

                    if (verbose) {
                        logger.lifecycle("[ComposeTvosRedirect] Injecting tvOS variants into (artifact match): ${id.group}:$moduleName:${id.version}")
                    }

                    injectTvosVariants(metadata, targetGroup, targetArtifact, version)
                }
            }
        }

        // Register component metadata rules to inject tvOS variants into umbrella modules
        allGroupMappings.forEach { (sourceGroup, targetGroup) ->
            project.dependencies.components.all { metadata ->
                val id = metadata.id
                val moduleName = id.module.name
                // Skip if already handled by artifact-level mapping
                val artifactKey = "${id.group}:$moduleName"
                if (artifactMappings.containsKey(artifactKey)) return@all

                if (id.group == sourceGroup && TvosArtifactMapping.isUmbrellaModule(moduleName)) {
                    val mappedTargetGroup = targetGroup
                    val version = targetVersion ?: id.version

                    if (verbose) {
                        logger.lifecycle("[ComposeTvosRedirect] Injecting tvOS variants into: ${id.group}:$moduleName:${id.version}")
                    }

                    injectTvosVariants(metadata, mappedTargetGroup, moduleName, version)
                }
            }
        }

        if (verbose) {
            logger.lifecycle("[ComposeTvosRedirect] tvOS variant injection configured")
            logger.lifecycle("[ComposeTvosRedirect] Compose groups: ${ComposeModules.ALL.joinToString()}")
            logger.lifecycle("[ComposeTvosRedirect] Predefined artifacts: ${ComposeArtifacts.ALL.joinToString()}")
            if (additionalGroups.isNotEmpty()) {
                logger.lifecycle("[ComposeTvosRedirect] Additional groups: ${additionalGroups.entries.joinToString { "${it.key} -> ${it.value}" }}")
            }
            if (additionalArtifacts.isNotEmpty()) {
                logger.lifecycle("[ComposeTvosRedirect] Additional artifacts: ${additionalArtifacts.entries.joinToString { "${it.key} -> ${it.value}" }}")
            }
            logger.lifecycle("[ComposeTvosRedirect] Target version: ${targetVersion ?: "(use original)"}")
        }
    }

    private fun injectTvosVariants(
        metadata: ComponentMetadataDetails,
        targetGroup: String,
        targetArtifact: String,
        version: String
    ) {
        // Add tvosArm64 variant (standalone, not derived from existing)
        metadata.addVariant("tvosArm64ApiElements-injected") { variant ->
            variant.attributes { attrs ->
                attrs.attribute(
                    org.gradle.api.attributes.Attribute.of("org.gradle.category", String::class.java),
                    "library"
                )
                attrs.attribute(
                    org.gradle.api.attributes.Attribute.of("org.gradle.usage", String::class.java),
                    "kotlin-api"
                )
                attrs.attribute(
                    org.gradle.api.attributes.Attribute.of("org.jetbrains.kotlin.platform.type", String::class.java),
                    "native"
                )
                attrs.attribute(
                    org.gradle.api.attributes.Attribute.of("org.jetbrains.kotlin.native.target", String::class.java),
                    "tvos_arm64"
                )
            }
            variant.withDependencies { deps ->
                deps.add("$targetGroup:$targetArtifact-${TvosTargets.TVOS_ARM64}:$version")
            }
        }

        // Add tvosSimulatorArm64 variant
        metadata.addVariant("tvosSimulatorArm64ApiElements-injected") { variant ->
            variant.attributes { attrs ->
                attrs.attribute(
                    org.gradle.api.attributes.Attribute.of("org.gradle.category", String::class.java),
                    "library"
                )
                attrs.attribute(
                    org.gradle.api.attributes.Attribute.of("org.gradle.usage", String::class.java),
                    "kotlin-api"
                )
                attrs.attribute(
                    org.gradle.api.attributes.Attribute.of("org.jetbrains.kotlin.platform.type", String::class.java),
                    "native"
                )
                attrs.attribute(
                    org.gradle.api.attributes.Attribute.of("org.jetbrains.kotlin.native.target", String::class.java),
                    "tvos_simulator_arm64"
                )
            }
            variant.withDependencies { deps ->
                deps.add("$targetGroup:$targetArtifact-${TvosTargets.TVOS_SIMULATOR_ARM64}:$version")
            }
        }

        // Add tvosX64 variant
        metadata.addVariant("tvosX64ApiElements-injected") { variant ->
            variant.attributes { attrs ->
                attrs.attribute(
                    org.gradle.api.attributes.Attribute.of("org.gradle.category", String::class.java),
                    "library"
                )
                attrs.attribute(
                    org.gradle.api.attributes.Attribute.of("org.gradle.usage", String::class.java),
                    "kotlin-api"
                )
                attrs.attribute(
                    org.gradle.api.attributes.Attribute.of("org.jetbrains.kotlin.platform.type", String::class.java),
                    "native"
                )
                attrs.attribute(
                    org.gradle.api.attributes.Attribute.of("org.jetbrains.kotlin.native.target", String::class.java),
                    "tvos_x64"
                )
            }
            variant.withDependencies { deps ->
                deps.add("$targetGroup:$targetArtifact-${TvosTargets.TVOS_X64}:$version")
            }
        }
    }

    private fun configureDependencySubstitution(
        project: Project,
        extension: ComposeTvosRedirectExtension,
        logger: Logger
    ) {
        val verbose = extension.verbose.get()
        val injectTvosVariants = extension.injectTvosVariants.get()
        val redirectAll = extension.redirectAll.get()
        val targetVersionOverride = extension.targetVersion.orNull
        val additionalGroups: Map<String, String> = extension.additionalGroups.get()
        val additionalArtifacts: Map<String, String> = extension.additionalArtifacts.get()

        // Build artifact mappings: predefined ComposeArtifacts + additionalArtifacts from extension
        val artifactMappings = buildArtifactMappings(additionalArtifacts, verbose, logger)

        // Build combined group mappings for dependency substitution
        val allGroupMappings = mutableMapOf<String, String>()
        // Compose groups: org.jetbrains.compose.* -> dev.sajidali.compose.*
        ComposeModules.ALL.forEach { composeGroup ->
            allGroupMappings[composeGroup] = TvosArtifactMapping.mapGroupId(composeGroup)
        }
        // Additional groups from extension
        allGroupMappings.putAll(additionalGroups)

        project.configurations.all { configuration ->
            configuration.resolutionStrategy.dependencySubstitution { substitutions ->
                substitutions.all { dependency ->
                    val requested = dependency.requested as? ModuleComponentSelector ?: return@all

                    val groupId = requested.group
                    val moduleName = requested.module
                    val originalVersion = requested.version
                    val artifactKey = "$groupId:$moduleName"

                    // Check for artifact-level mapping first
                    val artifactMapping = artifactMappings[artifactKey]
                    if (artifactMapping != null) {
                        val (targetGroup, targetArtifact) = artifactMapping
                        val targetVersion = targetVersionOverride ?: originalVersion

                        // For artifact mappings, apply same tvOS logic
                        val shouldRedirectArtifact = when {
                            injectTvosVariants -> TvosArtifactMapping.isTvosArtifact(moduleName)
                            redirectAll -> true
                            else -> TvosArtifactMapping.isTvosArtifact(moduleName) || TvosArtifactMapping.isUmbrellaModule(moduleName)
                        }

                        if (shouldRedirectArtifact) {
                            // Replace the artifact name suffix with the target artifact name
                            val targetModuleName = if (TvosArtifactMapping.isTvosArtifact(moduleName)) {
                                // Extract the tvOS suffix and append to target artifact
                                val suffix = TvosTargets.ALL.find { moduleName.lowercase().endsWith("-$it") }
                                if (suffix != null) "$targetArtifact-$suffix" else targetArtifact
                            } else {
                                targetArtifact
                            }

                            val targetCoordinate = "$targetGroup:$targetModuleName:$targetVersion"

                            if (verbose) {
                                logger.lifecycle(
                                    "[ComposeTvosRedirect] Redirecting (artifact match): $groupId:$moduleName:$originalVersion -> $targetCoordinate"
                                )
                            }

                            dependency.useTarget(targetCoordinate)
                        }
                        return@all
                    }

                    // Check if this is a Compose group
                    val isComposeGroup = TvosArtifactMapping.isComposeGroup(groupId)
                    // Check if this is an additional group
                    val isAdditionalGroup = additionalGroups.containsKey(groupId)

                    val shouldRedirect = when {
                        injectTvosVariants -> {
                            // Only redirect tvOS-specific artifacts (umbrella modules stay from source)
                            (isComposeGroup || isAdditionalGroup) && TvosArtifactMapping.isTvosArtifact(moduleName)
                        }
                        redirectAll -> {
                            // Redirect all artifacts from monitored groups
                            isComposeGroup || isAdditionalGroup
                        }
                        else -> {
                            // Smart redirect: tvOS artifacts + umbrella modules, but not other platforms
                            if (isComposeGroup) {
                                TvosArtifactMapping.shouldRedirectForTvos(groupId, moduleName)
                            } else if (isAdditionalGroup) {
                                // For additional groups, same logic: tvOS artifacts + umbrella modules
                                TvosArtifactMapping.isTvosArtifact(moduleName) || TvosArtifactMapping.isUmbrellaModule(moduleName)
                            } else {
                                false
                            }
                        }
                    }

                    if (shouldRedirect) {
                        // Use mapping from allGroupMappings, or fall back to standard Compose mapping
                        val targetGroup = allGroupMappings[groupId] ?: TvosArtifactMapping.mapGroupId(groupId)
                        val targetVersion = targetVersionOverride ?: originalVersion

                        val targetCoordinate = "$targetGroup:$moduleName:$targetVersion"

                        if (verbose) {
                            logger.lifecycle(
                                "[ComposeTvosRedirect] Redirecting: $groupId:$moduleName:$originalVersion -> $targetCoordinate"
                            )
                        }

                        dependency.useTarget(targetCoordinate)
                    }
                }
            }
        }

        if (verbose) {
            logger.lifecycle("[ComposeTvosRedirect] Dependency substitution configured")
            logger.lifecycle("[ComposeTvosRedirect] Mode: ${if (injectTvosVariants) "inject tvOS variants" else if (redirectAll) "redirect all" else "smart redirect"}")
            logger.lifecycle("[ComposeTvosRedirect] Monitored groups: ${ComposeModules.ALL.joinToString()}")
            logger.lifecycle("[ComposeTvosRedirect] Predefined artifacts: ${ComposeArtifacts.ALL.joinToString()}")
            if (additionalGroups.isNotEmpty()) {
                logger.lifecycle("[ComposeTvosRedirect] Additional groups: ${additionalGroups.keys.joinToString()}")
            }
            if (additionalArtifacts.isNotEmpty()) {
                logger.lifecycle("[ComposeTvosRedirect] Additional artifacts: ${additionalArtifacts.entries.joinToString { "${it.key} -> ${it.value}" }}")
            }
        }
    }

    /**
     * Builds artifact mappings combining predefined ComposeArtifacts with additional artifacts from extension.
     */
    private fun buildArtifactMappings(
        additionalArtifacts: Map<String, String>,
        verbose: Boolean,
        logger: Logger
    ): Map<String, Pair<String, String>> {
        val artifactMappings = mutableMapOf<String, Pair<String, String>>()

        // Add predefined artifact mappings
        ComposeArtifacts.ALL.forEach { sourceCoordinate ->
            val parts = sourceCoordinate.split(":")
            if (parts.size == 2) {
                val targetMapping = ComposeArtifacts.getTargetMapping(parts[0], parts[1])
                if (targetMapping != null) {
                    artifactMappings[sourceCoordinate] = targetMapping
                }
            }
        }

        // Add additional artifact mappings from extension (can override predefined)
        additionalArtifacts.forEach { (source, target) ->
            val sourceParts = source.split(":")
            val targetParts = target.split(":")
            if (sourceParts.size == 2 && targetParts.size == 2) {
                artifactMappings[source] = Pair(targetParts[0], targetParts[1])
            } else if (verbose) {
                logger.warn("[ComposeTvosRedirect] Invalid artifact mapping: $source -> $target (expected 'group:artifact' format)")
            }
        }

        return artifactMappings
    }
}
