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
 * Kept as a pure function of its inputs (no Gradle service/task state) so it is unit-testable
 * without GradleTestKit.
 */
internal object DiagnosticsSummary {
    private const val MAX_LISTED_MODULES = 5

    fun report(
        snapshot: DiagnosticsSnapshot,
        tvosTargetsDetected: Boolean,
        strictMode: Boolean,
        logger: Logger
    ) {
        if (snapshot.injections.isNotEmpty()) {
            logger.lifecycle(lifecycleLine(snapshot.injections.size, snapshot.emptyDiscoveries.size))
        }

        if (snapshot.emptyDiscoveries.isEmpty() || !tvosTargetsDetected) return

        if (strictMode) {
            throw GradleException(strictFailureMessage(snapshot.emptyDiscoveries))
        }
        logger.warn(warnBlock(snapshot.emptyDiscoveries))
    }

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
}
