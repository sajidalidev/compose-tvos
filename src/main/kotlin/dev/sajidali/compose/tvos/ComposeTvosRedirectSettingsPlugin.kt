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

        // -- Task 9b (defect D13): org.jetbrains.compose plugin-marker interception -------
        //
        // Populated below, inside the settingsEvaluated block, once the manifest has been
        // fetched. Declared here (not there) so the eachPlugin callback registered just
        // below -- which captures it by reference -- can see whatever value it holds BY THE
        // TIME THE CALLBACK ACTUALLY RUNS, not at registration time.
        var manifestGradlePluginVersion: String? = null

        // eachPlugin rules must be REGISTERED here, at apply() time: this method runs
        // synchronously while the settings script's own `plugins {}` block is still being
        // processed (this plugin IS one of those requests), i.e. strictly before
        // pluginManagement resolves any *later* plugin request. The only realistic later
        // requests are project build scripts' `plugins {}` blocks.
        //
        // Timing verified empirically (see task-9b-report.md): `extension` (configured by
        // the settings script's `composeTvos {}` block, which — like the whole rest of the
        // settings script — finishes evaluating before `settingsEvaluated` fires) and
        // `manifestGradlePluginVersion` (assigned inside settingsEvaluated below) are BOTH
        // fully populated by the time a PROJECT build script's `plugins {}` block triggers
        // this callback, because Gradle only resolves project-level plugin requests once
        // project configuration begins — always strictly after settingsEvaluated. Reading
        // `extension`/`manifestGradlePluginVersion` lazily INSIDE the callback body (never
        // at registration time, when neither is populated yet) is what makes this work.
        settings.pluginManagement.resolutionStrategy.eachPlugin { details ->
            if (details.requested.id.id == COMPOSE_GRADLE_PLUGIN_ID) {
                val intercept = extension.interceptComposeGradlePlugin.getOrElse(true)
                if (intercept) {
                    // First non-null wins: extension override -> manifest `gradlePlugin`
                    // field -> the requested version itself (same-version convention).
                    val version = extension.composeGradlePluginVersion.orNull
                        ?: manifestGradlePluginVersion
                        ?: details.requested.version
                    if (version != null) {
                        details.useModule("$COMPOSE_GRADLE_PLUGIN_COORDINATE:$version")
                    }
                }
            }
        }

        // Repositories the substituted module above resolves from. Opt-out
        // (interceptComposeGradlePlugin=false) is a `Property` read lazily inside the
        // eachPlugin callback, so it is NOT knowable here at apply() time -- pragmatically,
        // mavenCentral()/mavenLocal() are always appended. That is harmless: they are pure
        // additions (never removals/reordering) placed AFTER whatever the consumer's settings
        // script already declared (including any gradlePluginPortal() call, which is left
        // untouched and still resolves ordinary, non-intercepted plugin markers exactly as
        // before), so Gradle tries the consumer's own repositories first and only falls
        // through to these for coordinates the consumer's repositories don't have -- e.g. our
        // substituted dev.sajidali.compose:compose-gradle-plugin. Repositories are NOT
        // deduplicated by URL, so a consumer who already declares mavenCentral()/mavenLocal()
        // themselves gets a harmless, redundant second entry rather than a merge.
        //
        // Critical fix (Task 9b review): Gradle's OWN plugin resolution machinery falls back
        // to gradlePluginPortal() only when `pluginManagement.repositories` is still EMPTY at
        // the point a plugin needs resolving (verified against Gradle 9.5's
        // DefaultPluginArtifactRepositories). A consumer following the README -- a bare
        // `plugins { id("dev.sajidali.compose-tvos") version "x.x.x" }`, with no
        // `pluginManagement.repositories` block of their own -- has an EMPTY handler right up
        // until this line runs. Appending mavenCentral()/mavenLocal() unconditionally would
        // make that handler non-empty, silently disabling Gradle's implicit portal fallback
        // for every OTHER, unrelated plugin the consumer's build later requests (e.g. any
        // portal-hosted plugin id in a project build script) -- breaking their build with a
        // "Searched in the following repositories: MavenRepo, MavenLocal" failure that never
        // mentions the portal. So: if the handler is empty right now, add
        // gradlePluginPortal() FIRST, preserving Gradle's default resolution behavior, before
        // adding our own two repositories. If it is non-empty, the consumer declared
        // repositories deliberately (taking ownership of plugin resolution themselves), and we
        // must not second-guess that by injecting the portal behind their back -- only our two
        // repositories are appended, exactly as before.
        if (settings.pluginManagement.repositories.isEmpty()) {
            settings.pluginManagement.repositories.gradlePluginPortal()
        }
        settings.pluginManagement.repositories.apply {
            mavenCentral()
            mavenLocal()
        }

        settings.gradle.settingsEvaluated { evaluatedSettings ->
            // Diagnostics bookkeeping (Task 5 / defect D1) lives in TvosDiagnosticsBookkeeping
            // (Task 10e: shared by both TvosVariantInjectionRule and ComposeTvosRedirectPlugin,
            // see that object's KDoc), which -- like the existing variant-discovery caches --
            // outlives a single build in a warm Gradle daemon. Reset here, once per
            // (re-)configured build, so the end-of-build summary reports only THIS build's
            // state rather than accumulating stale entries from a previous build sharing the
            // same daemon.
            TvosDiagnosticsBookkeeping.resetDiagnostics()
            ComposeTvosRedirectPlugin.resetTvosTargetDetection()

            val verbose = extension.verbose.get()
            val offline = evaluatedSettings.gradle.startParameter.isOffline
            val repositoryUrls = collectRepositoryUrls(evaluatedSettings, extension)
            val loadedManifest = VersionManifestLoader.loadManifest(
                manifestUrl = extension.manifestUrl.orNull,
                cacheDir = cacheRoot,
                refreshDependencies = evaluatedSettings.gradle.startParameter.isRefreshDependencies,
                logger = if (verbose) logger else null,
                offline = offline
            )
            val manifestMappings = loadedManifest?.mappings ?: emptyMap()
            // Read by the eachPlugin callback registered earlier in apply() -- see the
            // Task 9b comment there for why a plain var capture (not a Property/Provider)
            // is sufficient: the callback only ever fires for project build scripts'
            // plugin requests, which happen strictly after this settingsEvaluated block.
            manifestGradlePluginVersion = loadedManifest?.gradlePlugin

            if (verbose) {
                logger.lifecycle("[ComposeTvosRedirect] Settings plugin applied")
                logger.lifecycle("[ComposeTvosRedirect] Found ${repositoryUrls.size} repositories")
                repositoryUrls.forEach { logger.lifecycle("[ComposeTvosRedirect]   - $it") }
                logger.lifecycle("[ComposeTvosRedirect] Loaded ${manifestMappings.size} version override(s) from manifest")
            }

            configureComponentMetadataRules(evaluatedSettings, extension, repositoryUrls, manifestMappings, cacheRoot, offline)

            val config = PluginConfiguration(
                verbose = extension.verbose.get(),
                targetVersion = extension.targetVersion.orNull,
                additionalGroups = extension.additionalGroups.get(),
                additionalArtifacts = extension.additionalArtifacts.get(),
                versionMappings = extension.versionMappings.get(),
                manifestMappings = manifestMappings,
                repositoryUrls = repositoryUrls,
                cacheDirPath = cacheRoot.absolutePath,
                offline = offline
            )

            evaluatedSettings.gradle.beforeProject { project ->
                project.extensions.extraProperties.set(SHARED_CONFIG_KEY, config)
                project.plugins.apply(ComposeTvosRedirectPlugin::class.java)
            }

            // End-of-build diagnostics summary (Task 5 / defect D1): a BuildService's
            // close() -- not `settings.gradle.projectsEvaluated` or `gradle.buildFinished`,
            // see TvosDiagnosticsService's KDoc for why -- so it runs once, after every
            // TvosVariantInjectionRule execution for this build has already happened,
            // whether resolution was eager or (the common case) deferred to task execution,
            // and remains configuration-cache compatible.
            val diagnosticsService = evaluatedSettings.gradle.sharedServices.registerIfAbsent(
                TvosDiagnosticsService.NAME,
                TvosDiagnosticsService::class.java
            ) { spec ->
                spec.parameters.strictMode.set(extension.strictMode)
                spec.parameters.verbose.set(extension.verbose)
            }
            // Force creation now: an unused/never-`.get()` build service is never
            // instantiated, and Gradle only calls close() on build services it actually
            // created, so a purely lazy registration would silently never report anything.
            diagnosticsService.get()
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
        cacheDir: File,
        offline: Boolean
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
        groupMappings.putAll(ComposeModules.EXPLICIT_GROUP_TARGETS)
        groupMappings.putAll(additionalGroups)

        val params = TvosVariantInjectionRule.Params(
            groupMappings = groupMappings,
            artifactMappings = artifactMappings,
            versionMappings = versionMappings,
            targetVersionOverride = targetVersionOverride,
            repositoryUrls = repositoryUrls,
            cacheDirPath = cacheDir.absolutePath,
            offline = offline,
            verbose = verbose
        )

        settings.dependencyResolutionManagement.components.all(TvosVariantInjectionRule::class.java) {
            it.params(params)
        }

        if (verbose) {
            logger.lifecycle("[ComposeTvosRedirect] Component metadata rules configured")
        }
    }

    companion object {
        const val SHARED_CONFIG_KEY = "composeTvosRedirect.config"

        /** The upstream plugin id intercepted by the Task 9b plugin-marker substitution. */
        private const val COMPOSE_GRADLE_PLUGIN_ID = "org.jetbrains.compose"

        /** `group:artifact` of the tvOS-patched fork the id above is substituted to. */
        private const val COMPOSE_GRADLE_PLUGIN_COORDINATE = "dev.sajidali.compose:compose-gradle-plugin"
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
    val repositoryUrls: List<String>,
    // Task 10b follow-up (Fix 1, review of task-10b-report.md): threaded through so
    // ComposeTvosRedirectPlugin's project-level dependency substitution can run the same
    // official-first pre-check TvosVariantInjectionRule already performs -- see
    // ComposeTvosRedirectPlugin.isOfficiallySupported.
    val cacheDirPath: String,
    val offline: Boolean
) : java.io.Serializable
