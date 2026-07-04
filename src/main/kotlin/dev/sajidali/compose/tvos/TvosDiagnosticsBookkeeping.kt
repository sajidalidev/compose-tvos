package dev.sajidali.compose.tvos

import java.util.concurrent.ConcurrentHashMap

/**
 * Task 10e: end-of-build diagnostics bookkeeping (Task 5 / defect D1), extracted out of
 * [TvosVariantInjectionRule]'s companion object into its own holder.
 *
 * Before this extraction, both `injections`/`emptyDiscoveries`/`skippedAlreadySupported`
 * lived directly on `TvosVariantInjectionRule`'s companion. That was fine while
 * `TvosVariantInjectionRule` was the only writer, but Task 10e gives
 * [ComposeTvosRedirectPlugin]'s project-level `dependencySubstitution` its OWN, independent
 * "official-first" success path (`isOfficiallySupported`) that must record into the exact same
 * `skippedAlreadySupported` bookkeeping so the end-of-build summary's conflict-loser filter
 * (`DiagnosticsSummary.filterConflictLosers`, Task 10d) can see it too. Rather than having
 * `ComposeTvosRedirectPlugin` reach into an unrelated rule class's companion object (or
 * duplicate the maps), the bookkeeping itself moves to a small, shared, class-agnostic holder
 * both call directly.
 *
 * Lifetime/threading notes carried over unchanged from the pre-extraction companion object:
 * daemon-lifetime (a warm Gradle daemon spans many builds, hence [resetDiagnostics] is called
 * once per (re-)configured build from `ComposeTvosRedirectSettingsPlugin`'s
 * `settingsEvaluated`), and `ConcurrentHashMap` because both the component-metadata rule and
 * the dependency-substitution callback fire concurrently across components/configurations
 * (and, for a multi-project build, potentially across projects).
 *
 * GUARD-RAIL: any NEW "official-first" success path added anywhere in this plugin (i.e. any
 * mechanism that decides "the official artifact already covers this, no redirect/injection
 * needed") MUST record that success here via [recordSkippedAlreadySupported] (or
 * [recordEmptyForkDiscovery]), exactly as [ComposeTvosRedirectPlugin.isOfficiallySupported] and
 * `TvosVariantInjectionRule` already do. Skipping this is how a mechanism's successes go
 * invisible to `DiagnosticsSummary.filterConflictLosers` (Task 10d), which is the only thing
 * suppressing spurious "empty discovery" WARNs for conflict-resolution losers -- an unrecorded
 * success path silently reintroduces false-positive WARNs (or `strictMode` failures) for a
 * perfectly healthy build.
 */
internal object TvosDiagnosticsBookkeeping {
    private val injections = ConcurrentHashMap<String, InjectionRecord>()
    private val emptyDiscoveries = ConcurrentHashMap<String, EmptyDiscoveryRecord>()
    private val skippedAlreadySupported = ConcurrentHashMap<String, SkippedAlreadySupportedRecord>()

    fun recordInjection(record: InjectionRecord) {
        injections.putIfAbsent(record.sourceModule, record)
    }

    fun recordEmptyDiscovery(record: EmptyDiscoveryRecord) {
        emptyDiscoveries.putIfAbsent(record.sourceModule, record)
    }

    fun recordSkippedAlreadySupported(record: SkippedAlreadySupportedRecord) {
        skippedAlreadySupported.putIfAbsent(record.sourceModule, record)
    }

    /**
     * Task 10e: the ONE place the "official-first, fork-discovery-empty" precedence decision
     * is made -- factored out of [TvosVariantInjectionRule.execute] so it is unit-testable
     * without a Gradle `ComponentMetadataContext`. When the fork published NOTHING for a
     * redirect-eligible module@version (`alreadySupportedTargets` describes the OFFICIAL
     * artifact's own tvOS coverage, independent of the fork):
     *  - the official artifact already ships tvOS variant(s) itself -> nothing is actually
     *    broken (no injection was ever needed for this coordinate) -> [recordSkippedAlreadySupported],
     *    never [recordEmptyDiscovery] (which exists purely to flag genuine gaps -- see
     *    [EmptyDiscoveryRecord]'s KDoc).
     *  - the official artifact has no tvOS coverage either -> a genuine gap -> [recordEmptyDiscovery].
     */
    fun recordEmptyForkDiscovery(
        sourceModule: String,
        targetCoordinate: String,
        alreadySupportedTargets: Set<String>,
        repositoryUrls: List<String>,
        offline: Boolean
    ) {
        if (alreadySupportedTargets.isNotEmpty()) {
            recordSkippedAlreadySupported(
                SkippedAlreadySupportedRecord(sourceModule, targetCoordinate, alreadySupportedTargets.toList())
            )
        } else {
            recordEmptyDiscovery(
                EmptyDiscoveryRecord(sourceModule, targetCoordinate, repositoryUrls, offline)
            )
        }
    }

    /** Clears diagnostics bookkeeping; called once per build before either writer can fire. */
    fun resetDiagnostics() {
        injections.clear()
        emptyDiscoveries.clear()
        skippedAlreadySupported.clear()
    }

    /** Point-in-time read of the diagnostics bookkeeping accumulated so far this build. */
    fun diagnosticsSnapshot(): DiagnosticsSnapshot =
        DiagnosticsSnapshot(injections.values.toList(), emptyDiscoveries.values.toList(), skippedAlreadySupported.values.toList())
}
