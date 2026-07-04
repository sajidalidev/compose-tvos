package dev.sajidali.compose.tvos

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger

/**
 * Formats and emits the Task 5 (defect D1) end-of-build diagnostics summary from a
 * [DiagnosticsSnapshot]:
 *  - injections happened -> a single lifecycle line (always; independent of the tvOS-target
 *    guard below -- an actual injection is real signal, never spurious noise).
 *  - empty-discovery cases exist AND the consuming project declared a tvOS target
 *    ([tvosTargetsDetected]) -> a single WARN block (capped at [MAX_LISTED_MODULES], "+K more"),
 *    or, in `strictMode`, a [GradleException] listing every one of them.
 *  - nothing eligible seen -> silent, regardless of verbosity (zero noise for non-tvOS
 *    projects is the whole point of the tvOS-target guard).
 *
 * Task 10d (Phase 4 closeout): before either the WARN block or the `strictMode` failure list is
 * built, [filterConflictLosers] drops empty-discovery entries for `group:module@version`
 * coordinates where that SAME `group:module` has, at some OTHER version, either a successful
 * injection or an already-officially-supported record. `TvosVariantInjectionRule`'s
 * `ComponentMetadataRule` fires on every version node Gradle's resolution engine touches while
 * building the graph, not just the one version conflict resolution eventually keeps -- so a real
 * consumer graph routinely produces one empty-discovery record per LOSING candidate version of a
 * module that resolves perfectly fine at its winning version (see task-10c-report.md gate (e):
 * 11 such entries on a healthy demo build with zero actual resolution failures). Suppressing
 * those here is purely a summary-time read of the existing bookkeeping -- `TvosVariantInjectionRule`
 * itself, and every map it maintains, is unchanged; a module with NO working version anywhere
 * still warns (the genuine "would have injected but found nothing" signal this whole feature
 * exists for).
 *
 * Kept as a pure function of its inputs (no Gradle service/task state) so it is unit-testable
 * without GradleTestKit.
 */
internal object DiagnosticsSummary {
    private const val MAX_LISTED_MODULES = 5

    /** Result of [filterConflictLosers]: the two disjoint partitions of a snapshot's empty discoveries. */
    internal data class FilteredEmptyDiscoveries(
        val kept: List<EmptyDiscoveryRecord>,
        val suppressed: List<EmptyDiscoveryRecord>
    )

    fun report(
        snapshot: DiagnosticsSnapshot,
        tvosTargetsDetected: Boolean,
        strictMode: Boolean,
        verbose: Boolean,
        logger: Logger
    ) {
        if (snapshot.injections.isNotEmpty()) {
            logger.lifecycle(lifecycleLine(snapshot.injections.size, snapshot.emptyDiscoveries.size))
        }

        val filtered = filterConflictLosers(snapshot)

        // Task 10e (fix of a 10d concern): this must fire at Gradle's `lifecycle` level, not
        // `info`, so it is visible to a consumer who only set `composeTvos.verbose=true` --
        // matching the level every OTHER verbose-only message in this plugin already uses
        // (TvosVariantInjectionRule's injection/skip lines, ComposeTvosRedirectPlugin's
        // redirect/skip-substitution lines). `logger.info` would have silently required the
        // consumer to ALSO pass Gradle's own `--info`/`--debug` flag, which this plugin's
        // `verbose` flag was never meant to depend on.
        if (verbose && filtered.suppressed.isNotEmpty()) {
            logger.lifecycle(suppressedLine(filtered.suppressed))
        }

        if (filtered.kept.isEmpty() || !tvosTargetsDetected) return

        if (strictMode) {
            throw GradleException(strictFailureMessage(filtered.kept))
        }
        logger.warn(warnBlock(filtered.kept))
    }

    /**
     * Partitions [DiagnosticsSnapshot.emptyDiscoveries] into `kept` (the module has no working
     * version anywhere -- a genuine gap) and `suppressed` (conflict-resolution losers: the same
     * `group:module` succeeded, at some other version, either via injection or as
     * already-officially-supported). An empty-discovery record's own `sourceModule` version can
     * never itself also appear in [DiagnosticsSnapshot.injections] or
     * [DiagnosticsSnapshot.skippedAlreadySupported] -- `TvosVariantInjectionRule.execute` records
     * a coordinate into `emptyDiscoveries` and returns immediately, mutually exclusive with the
     * later injection/skip bookkeeping for that exact same coordinate -- so matching on
     * `group:module` alone (ignoring the version suffix) is sufficient to mean "some OTHER
     * version," with no extra same-version exclusion needed.
     */
    internal fun filterConflictLosers(snapshot: DiagnosticsSnapshot): FilteredEmptyDiscoveries {
        val workingModuleKeys = (
            snapshot.injections.asSequence().map { moduleKey(it.sourceModule) } +
                snapshot.skippedAlreadySupported.asSequence().map { moduleKey(it.sourceModule) }
            ).toSet()
        val (suppressed, kept) = snapshot.emptyDiscoveries.partition { moduleKey(it.sourceModule) in workingModuleKeys }
        return FilteredEmptyDiscoveries(kept = kept, suppressed = suppressed)
    }

    /** Strips the trailing `:version` off a `group:module:version` sourceModule string. */
    private fun moduleKey(sourceModule: String): String = sourceModule.substringBeforeLast(':')

    internal fun lifecycleLine(injectedCount: Int, skippedCount: Int): String =
        "[ComposeTvos] Injected tvOS variants into $injectedCount module(s) ($skippedCount skipped). " +
            "Run with composeTvos.verbose=true for detail."

    internal fun warnBlock(emptyDiscoveries: List<EmptyDiscoveryRecord>): String {
        val listed = emptyDiscoveries.take(MAX_LISTED_MODULES).joinToString("\n") {
            "  - ${it.sourceModule} -> ${it.targetCoordinate}"
        }
        val remaining = emptyDiscoveries.size - MAX_LISTED_MODULES
        val moreLine = if (remaining > 0) "\n  ... +$remaining more" else ""
        return "[ComposeTvos] WARNING: tvOS variant discovery found nothing for " +
            "${emptyDiscoveries.size} redirect-eligible module(s); tvOS builds depending on " +
            "them will fail to resolve:\n$listed$moreLine\n" +
            "Check that dev.sajidali artifacts exist for the requested version, set " +
            "composeTvos.versionMappings to point at a version that does, or see: " +
            "https://github.com/sajidalidev/compose-tvos#readme"
    }

    internal fun strictFailureMessage(emptyDiscoveries: List<EmptyDiscoveryRecord>): String {
        val listed = emptyDiscoveries.joinToString("\n") { "  - ${it.sourceModule} -> ${it.targetCoordinate}" }
        return "[ComposeTvos] strictMode: tvOS variant discovery found nothing for " +
            "${emptyDiscoveries.size} redirect-eligible module(s):\n$listed"
    }

    internal fun suppressedLine(suppressed: List<EmptyDiscoveryRecord>): String =
        "[ComposeTvos] ${suppressed.size} version-candidate entries suppressed " +
            "(conflict-resolution losers): ${suppressed.joinToString(", ") { it.sourceModule }}"
}
