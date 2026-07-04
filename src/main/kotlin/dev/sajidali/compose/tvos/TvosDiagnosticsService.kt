package dev.sajidali.compose.tvos

import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

interface TvosDiagnosticsServiceParams : BuildServiceParameters {
    val strictMode: Property<Boolean>
    val verbose: Property<Boolean>
}

/**
 * Emits the Task 5 (defect D1) end-of-build diagnostics summary from
 * [TvosDiagnosticsService.close], Gradle's own "run this once, right at the end of the
 * build" hook for a [BuildService] -- registered once from
 * `ComposeTvosRedirectSettingsPlugin` and closed automatically by Gradle when the build
 * finishes.
 *
 * ## Why a BuildService and not `settings.gradle.projectsEvaluated` or `gradle.buildFinished`
 *
 * `gradle.buildFinished { }` was the first choice tried, and is what the task brief asked to
 * avoid ("buildFinished-free equivalent"): confirmed empirically, registering that listener is
 * a HARD configuration-cache-store failure on Gradle 8.13 --
 * "Listener registration 'Gradle.buildFinished' by build is unsupported" -- not merely a
 * deprecation warning, so it cannot be used at all under `--configuration-cache`.
 *
 * `settings.gradle.projectsEvaluated { }` (the brief's suggested hook) was tried next and
 * confirmed, empirically, to fire too EARLY for the common case: `TvosVariantInjectionRule`
 * only executes when Gradle actually resolves a configuration containing the umbrella
 * module, and for a project that resolves dependencies lazily at TASK EXECUTION time (the
 * configuration-cache-recommended pattern -- and the one this repo's own functional-test
 * `resolveGraph` task uses, via a lazy `resolutionResult.rootComponent` `Provider` realized
 * inside `doLast`), that resolution -- and therefore every rule execution -- happens strictly
 * AFTER `projectsEvaluated` already ran. A summary hooked only at `projectsEvaluated` would
 * therefore see empty bookkeeping and print nothing, for exactly the builds this feature most
 * needs to help. (Aside: under `--configuration-cache` specifically, Kotlin Gradle Plugin was
 * observed to resolve the same configuration eagerly between `projectsEvaluated` and task
 * execution -- likely to capture resolved dependency state into the cached task graph -- so
 * `projectsEvaluated` would have looked like it worked for that one path while still being
 * broken for a plain, non-CC build. Relying on that KGP-version-specific incidental behavior
 * was rejected as fragile.)
 *
 * A [BuildService] with a `close()` method is Gradle's own configuration-cache-compatible
 * replacement for build-scoped end-of-build listeners: `close()` runs once, after all task
 * actions have completed (so every rule execution has already happened, regardless of
 * whether resolution was eager or lazy), and -- unlike `buildFinished` -- registering and
 * using a `BuildService` is fully supported under `--configuration-cache` (verified: the
 * `--configuration-cache` functional test stores and reuses its cache entry with this service
 * registered and printing).
 *
 * ## Configuration-cache-reuse limitation (documented, accepted per the task brief)
 *
 * On a configuration-cache-REUSED build, the entire configuration phase (including
 * `settingsEvaluated`, where this service is registered and the bookkeeping/tvOS-target-flag
 * reset happens) is skipped -- the task graph is replayed directly from the cache. That means
 * this service is never registered for that build, `close()` never runs, and no summary/warn/
 * strictMode-failure is printed for a config-cache-reused build, even if empty-discovery cases
 * would otherwise exist. This mirrors the exact same accepted limitation on the tvOS-target-
 * detection flag (`ComposeTvosRedirectPlugin.tvosTargetsDetected`, whose `afterEvaluate` also
 * does not re-run on cache reuse).
 */
abstract class TvosDiagnosticsService : BuildService<TvosDiagnosticsServiceParams>, AutoCloseable {

    override fun close() {
        DiagnosticsSummary.report(
            snapshot = TvosDiagnosticsBookkeeping.diagnosticsSnapshot(),
            tvosTargetsDetected = ComposeTvosRedirectPlugin.tvosTargetsDetected,
            strictMode = parameters.strictMode.getOrElse(false),
            verbose = parameters.verbose.getOrElse(false),
            logger = logger
        )
    }

    companion object {
        private val logger = Logging.getLogger(TvosDiagnosticsService::class.java)
        const val NAME = "composeTvosDiagnostics"
    }
}
