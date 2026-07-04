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
            detectTvosTargets(project)
            configureDependencySubstitution(project, sharedConfig)
        }
    }

    /**
     * Task 5 warning-quality guard: [TvosVariantInjectionRule] only ever sees resolved
     * component coordinates -- never which Kotlin targets a project itself declares -- so it
     * cannot distinguish a genuine tvOS consumer from an android/iOS-only Compose project
     * whose umbrella modules resolve through the exact same group/artifact mapping and would
     * therefore ALSO show up as "empty discovery" once no fork artifacts exist for their
     * versions (warning about that would be spurious noise, worse than the silent no-op this
     * task is fixing). This plugin, applied to every project, CAN see the project's `kotlin`
     * extension, so it detects tvOS targets here and sets [tvosTargetsDetected]; the settings
     * plugin's end-of-build summary only warns/strict-fails when this flag is set.
     *
     * No hard dependency on Kotlin Gradle Plugin classes (not on this plugin's compile
     * classpath): `kotlin { }`'s `KotlinProjectExtension`/`KotlinMultiplatformExtension` are
     * looked up purely by name/reflection, defensively -- any failure (extension absent,
     * shape unexpected, method missing) just leaves the flag unset, which only suppresses a
     * warning rather than crashing configuration.
     *
     * Limitation: on a configuration-cache-REUSED build this `afterEvaluate` does not re-run
     * (the whole configuration phase is skipped), so the flag defaults to false for that build
     * and the warning/strictMode-failure is suppressed -- an accepted config-cache-reuse gap,
     * matching the same limitation on the bookkeeping side (see
     * `TvosVariantInjectionRule.resetDiagnostics`).
     */
    private fun detectTvosTargets(project: Project) {
        try {
            val kotlinExtension = project.extensions.findByName("kotlin") ?: return
            val getTargets = kotlinExtension.javaClass.methods
                .firstOrNull { it.name == "getTargets" && it.parameterCount == 0 } ?: return
            val targets = getTargets.invoke(kotlinExtension) as? Iterable<*> ?: return

            val hasTvosTarget = targets.any { target ->
                val getName = target?.javaClass?.methods
                    ?.firstOrNull { it.name == "getName" && it.parameterCount == 0 }
                (getName?.invoke(target) as? String)?.startsWith("tvos", ignoreCase = true) == true
            }
            if (hasTvosTarget) {
                tvosTargetsDetected = true
            }
        } catch (_: Exception) {
            // Reflection is best-effort/defensive: any failure here must not crash
            // configuration for an unrelated reason -- it just leaves the flag unset,
            // which fails toward silence (no spurious warning), not toward a crash.
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

        // User mappings overwrite manifest mappings on key collision (later putAll wins).
        val allVersionMappings = mutableMapOf<String, String>()
        allVersionMappings.putAll(ComposeVersions.normalizeMappings(config.manifestMappings))
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

    companion object {
        // Task 5 warning-quality guard flag; see `detectTvosTargets` KDoc above. `@Volatile`
        // because it is written from per-project `afterEvaluate` callbacks (potentially
        // several projects in a multi-project build) and read later from
        // `TvosDiagnosticsService.close()`.
        @Volatile
        internal var tvosTargetsDetected: Boolean = false

        /** Clears the flag; called once per build (alongside `TvosVariantInjectionRule.resetDiagnostics`). */
        fun resetTvosTargetDetection() {
            tvosTargetsDetected = false
        }
    }
}
