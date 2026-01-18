package dev.sajidali.compose.tvos

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentSelector

/**
 * Project plugin that handles dependency substitution for tvOS artifacts.
 *
 * This plugin is auto-applied by ComposeTvosRedirectSettingsPlugin to each project.
 * It should not be applied directly - use the settings plugin instead:
 *
 * ```kotlin
 * // settings.gradle.kts
 * plugins {
 *     id("dev.sajidali.compose-tvos") version "x.x.x"
 * }
 * ```
 */
class ComposeTvosRedirectPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val sharedConfig = try {
            project.extensions.extraProperties.get(ComposeTvosRedirectSettingsPlugin.SHARED_CONFIG_KEY)
                as? PluginConfiguration
        } catch (_: Exception) {
            null
        }

        if (sharedConfig == null) {
            project.logger.warn(
                "[ComposeTvosRedirect] Plugin applied directly to project. " +
                "Please apply the settings plugin in settings.gradle.kts instead."
            )
            return
        }

        project.afterEvaluate {
            configureDependencySubstitution(project, sharedConfig)
        }
    }

    private fun configureDependencySubstitution(project: Project, config: PluginConfiguration) {
        val verbose = config.verbose
        val logger = project.logger

        val allGroupMappings = mutableMapOf<String, String>()
        ComposeModules.ALL.forEach { composeGroup ->
            allGroupMappings[composeGroup] = TvosArtifactMapping.mapGroupId(composeGroup)
        }
        allGroupMappings.putAll(config.additionalGroups)

        val artifactMappings = mutableMapOf<String, Pair<String, String>>()
        ComposeArtifacts.ALL.forEach { sourceCoordinate ->
            val parts = sourceCoordinate.split(":")
            if (parts.size == 2) {
                val targetMapping = ComposeArtifacts.getTargetMapping(parts[0], parts[1])
                if (targetMapping != null) {
                    artifactMappings[sourceCoordinate] = targetMapping
                }
            }
        }
        config.additionalArtifacts.forEach { (source, target) ->
            val sourceParts = source.split(":")
            val targetParts = target.split(":")
            if (sourceParts.size == 2 && targetParts.size == 2) {
                artifactMappings[source] = Pair(targetParts[0], targetParts[1])
            }
        }

        val allVersionMappings = mutableMapOf<String, String>()
        allVersionMappings.putAll(ComposeVersions.normalizeMappings(ComposeVersions.ALL))
        allVersionMappings.putAll(ComposeVersions.normalizeMappings(config.versionMappings))

        project.configurations.all { configuration ->
            configuration.resolutionStrategy.dependencySubstitution { substitutions ->
                substitutions.all { dependency ->
                    val requested = dependency.requested as? ModuleComponentSelector ?: return@all

                    val groupId = requested.group
                    val moduleName = requested.module
                    val originalVersion = requested.version
                    val artifactKey = "$groupId:$moduleName"

                    // Check artifact-level mapping first
                    val artifactMapping = artifactMappings[artifactKey]
                    if (artifactMapping != null && TvosArtifactMapping.isTvosArtifact(moduleName)) {
                        val (targetGroup, targetArtifact) = artifactMapping
                        val targetVersion = ComposeVersions.resolveVersion(
                            groupId, moduleName, originalVersion, allVersionMappings, config.targetVersion
                        )
                        val suffix = TvosTargets.extractTvosSuffix(moduleName)
                        val targetModuleName = if (suffix != null) "$targetArtifact$suffix" else targetArtifact
                        val targetCoordinate = "$targetGroup:$targetModuleName:$targetVersion"

                        if (verbose) {
                            logger.lifecycle("[ComposeTvosRedirect] Redirecting: $groupId:$moduleName:$originalVersion -> $targetCoordinate")
                        }
                        dependency.useTarget(targetCoordinate)
                        return@all
                    }

                    // Check group-level mapping
                    val isComposeGroup = TvosArtifactMapping.isComposeGroup(groupId)
                    val isAdditionalGroup = config.additionalGroups.containsKey(groupId)

                    if ((isComposeGroup || isAdditionalGroup) && TvosArtifactMapping.isTvosArtifact(moduleName)) {
                        val targetGroup = allGroupMappings[groupId] ?: TvosArtifactMapping.mapGroupId(groupId)
                        val targetVersion = ComposeVersions.resolveVersion(
                            groupId, moduleName, originalVersion, allVersionMappings, config.targetVersion
                        )
                        val targetCoordinate = "$targetGroup:$moduleName:$targetVersion"

                        if (verbose) {
                            logger.lifecycle("[ComposeTvosRedirect] Redirecting: $groupId:$moduleName:$originalVersion -> $targetCoordinate")
                        }
                        dependency.useTarget(targetCoordinate)
                    }
                }
            }
        }

        if (verbose) {
            logger.lifecycle("[ComposeTvosRedirect] Dependency substitution configured")
        }
    }
}
