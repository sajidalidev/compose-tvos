package dev.sajidali.compose.tvos

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Task 10d (Phase 4 closeout): unit tests for [DiagnosticsSummary]'s summary-time suppression of
 * empty-discovery entries that are conflict-resolution losers -- see task-10c-report.md gate (e)
 * for the real-world motivation (11 spurious WARN entries on a perfectly healthy demo build).
 *
 * [DiagnosticsSummary.report] is a pure function of its inputs (no Gradle service/task state),
 * so these are plain unit tests against constructed [DiagnosticsSnapshot]s, no GradleTestKit.
 */
class DiagnosticsSummaryTest {

    private fun emptyDiscovery(sourceModule: String, targetCoordinate: String = "dev.sajidali.$sourceModule") =
        EmptyDiscoveryRecord(sourceModule, targetCoordinate, repositoryUrls = emptyList(), offline = false)

    private fun injection(sourceModule: String) =
        InjectionRecord(sourceModule, targetCoordinate = "dev.sajidali.$sourceModule", variantCount = 1)

    private fun skipped(sourceModule: String) =
        SkippedAlreadySupportedRecord(sourceModule, targetCoordinate = sourceModule, skippedNativeTargets = listOf("tvos_arm64"))

    // -- filterConflictLosers (pure filter logic) ----------------------------------------

    @Test
    fun `a losing candidate version is suppressed when the same module was injected at another version`() {
        val snapshot = DiagnosticsSnapshot(
            injections = listOf(injection("org.jetbrains.compose.ui:ui-backhandler:1.12.0-beta01")),
            emptyDiscoveries = listOf(emptyDiscovery("org.jetbrains.compose.ui:ui-backhandler:1.11.0-beta03"))
        )

        val filtered = DiagnosticsSummary.filterConflictLosers(snapshot)

        assertEquals(emptyList(), filtered.kept, "the losing candidate version must be suppressed, not kept")
        assertEquals(1, filtered.suppressed.size)
        assertEquals("org.jetbrains.compose.ui:ui-backhandler:1.11.0-beta03", filtered.suppressed.single().sourceModule)
    }

    @Test
    fun `a losing candidate version is suppressed when the same module was skipped as already-supported at another version`() {
        val snapshot = DiagnosticsSnapshot(
            injections = emptyList(),
            emptyDiscoveries = listOf(emptyDiscovery("org.jetbrains.compose.runtime:runtime:1.11.0-beta03")),
            skippedAlreadySupported = listOf(skipped("org.jetbrains.compose.runtime:runtime:1.12.0-beta01"))
        )

        val filtered = DiagnosticsSummary.filterConflictLosers(snapshot)

        assertEquals(emptyList(), filtered.kept)
        assertEquals(1, filtered.suppressed.size)
    }

    @Test
    fun `a module with no working version anywhere still warns`() {
        val snapshot = DiagnosticsSnapshot(
            injections = listOf(injection("org.jetbrains.compose.ui:ui:1.12.0-beta01")),
            emptyDiscoveries = listOf(emptyDiscovery("org.jetbrains.compose.ui:ui-cold:1.11.0-cold"))
        )

        val filtered = DiagnosticsSummary.filterConflictLosers(snapshot)

        assertEquals(1, filtered.kept.size, "a module with no working version anywhere (different module name) must still warn")
        assertEquals("org.jetbrains.compose.ui:ui-cold:1.11.0-cold", filtered.kept.single().sourceModule)
        assertEquals(emptyList(), filtered.suppressed)
    }

    @Test
    fun `a mix of losers and genuine gaps partitions correctly`() {
        val snapshot = DiagnosticsSnapshot(
            injections = listOf(injection("g:ui:1.12.0-beta01")),
            emptyDiscoveries = listOf(
                emptyDiscovery("g:ui:1.11.0-beta03"), // loser: ui works at 1.12.0-beta01
                emptyDiscovery("g:ui:1.11.0"), // loser: same module, different losing version
                emptyDiscovery("g:material-ripple:1.11.0-beta03") // genuine gap: material-ripple never works
            )
        )

        val filtered = DiagnosticsSummary.filterConflictLosers(snapshot)

        assertEquals(1, filtered.kept.size)
        assertEquals("g:material-ripple:1.11.0-beta03", filtered.kept.single().sourceModule)
        assertEquals(2, filtered.suppressed.size)
        assertTrue(filtered.suppressed.all { it.sourceModule.startsWith("g:ui:") })
    }

    @Test
    fun `an exact group and module match at a different version is required -- a different module is never suppressed by coincidence`() {
        val snapshot = DiagnosticsSnapshot(
            injections = listOf(injection("dev.sajidali.compose.ui:ui:1.12.0-beta01")),
            emptyDiscoveries = listOf(emptyDiscovery("dev.sajidali.compose.ui:ui-text:1.11.0-beta03"))
        )

        val filtered = DiagnosticsSummary.filterConflictLosers(snapshot)

        assertEquals(1, filtered.kept.size, "ui-text is a different module from ui and must not be suppressed by its injection")
    }

    // -- report(): strictMode uses the filtered set --------------------------------------

    @Test
    fun `strictMode failure message lists only the filtered (kept) set, not suppressed losers`() {
        val snapshot = DiagnosticsSnapshot(
            injections = listOf(injection("g:ui:1.12.0-beta01")),
            emptyDiscoveries = listOf(
                emptyDiscovery("g:ui:1.11.0-beta03"),
                emptyDiscovery("g:material-ripple:1.11.0-beta03")
            )
        )
        val logger = RecordingLogger()

        val failure = assertFailsWith<GradleException> {
            DiagnosticsSummary.report(snapshot, tvosTargetsDetected = true, strictMode = true, verbose = false, logger = logger)
        }

        assertTrue(failure.message!!.contains("g:material-ripple:1.11.0-beta03"))
        assertTrue(!failure.message!!.contains("g:ui:1.11.0-beta03"), "the suppressed loser must not appear in the strictMode failure list")
    }

    // -- report(): WARN block uses the filtered set --------------------------------------

    @Test
    fun `WARN block is suppressed entirely when every empty discovery is a conflict-resolution loser`() {
        val snapshot = DiagnosticsSnapshot(
            injections = listOf(injection("g:ui:1.12.0-beta01")),
            emptyDiscoveries = listOf(emptyDiscovery("g:ui:1.11.0-beta03"))
        )
        val logger = RecordingLogger()

        DiagnosticsSummary.report(snapshot, tvosTargetsDetected = true, strictMode = false, verbose = false, logger = logger)

        assertEquals(emptyList(), logger.warnMessages, "no WARN may fire when every empty discovery is a suppressed loser")
    }

    @Test
    fun `WARN block still fires for a module with a genuine gap alongside suppressed losers`() {
        val snapshot = DiagnosticsSnapshot(
            injections = listOf(injection("g:ui:1.12.0-beta01")),
            emptyDiscoveries = listOf(
                emptyDiscovery("g:ui:1.11.0-beta03"),
                emptyDiscovery("g:material-ripple:1.11.0-beta03")
            )
        )
        val logger = RecordingLogger()

        DiagnosticsSummary.report(snapshot, tvosTargetsDetected = true, strictMode = false, verbose = false, logger = logger)

        assertEquals(1, logger.warnMessages.size)
        assertTrue(logger.warnMessages.single().contains("g:material-ripple:1.11.0-beta03"))
        assertTrue(!logger.warnMessages.single().contains("g:ui:1.11.0-beta03"))
    }

    // -- report(): verbose-only suppressed-entries info line -----------------------------

    @Test
    fun `verbose prints an info line naming the suppressed entries when losers were suppressed`() {
        val snapshot = DiagnosticsSnapshot(
            injections = listOf(injection("g:ui:1.12.0-beta01")),
            emptyDiscoveries = listOf(emptyDiscovery("g:ui:1.11.0-beta03"))
        )
        val logger = RecordingLogger()

        DiagnosticsSummary.report(snapshot, tvosTargetsDetected = true, strictMode = false, verbose = true, logger = logger)

        assertEquals(1, logger.infoMessages.size)
        val message = logger.infoMessages.single()
        assertTrue(message.contains("1"), "message must name the suppressed count; got: $message")
        assertTrue(message.contains("suppressed"), "message must say suppressed; got: $message")
        assertTrue(message.contains("conflict-resolution losers"), "message must explain why; got: $message")
        assertTrue(message.contains("g:ui:1.11.0-beta03"), "message must name the suppressed entry; got: $message")
    }

    @Test
    fun `non-verbose never prints the suppressed-entries info line`() {
        val snapshot = DiagnosticsSnapshot(
            injections = listOf(injection("g:ui:1.12.0-beta01")),
            emptyDiscoveries = listOf(emptyDiscovery("g:ui:1.11.0-beta03"))
        )
        val logger = RecordingLogger()

        DiagnosticsSummary.report(snapshot, tvosTargetsDetected = true, strictMode = false, verbose = false, logger = logger)

        assertEquals(emptyList(), logger.infoMessages)
    }

    @Test
    fun `verbose prints no suppressed-entries info line when nothing was suppressed`() {
        val snapshot = DiagnosticsSnapshot(
            injections = emptyList(),
            emptyDiscoveries = listOf(emptyDiscovery("g:material-ripple:1.11.0-beta03"))
        )
        val logger = RecordingLogger()

        DiagnosticsSummary.report(snapshot, tvosTargetsDetected = true, strictMode = false, verbose = true, logger = logger)

        assertEquals(emptyList(), logger.infoMessages)
    }

    /** Captures warn/info calls for assertions; delegates everything else to a real (silent) Logger. */
    private class RecordingLogger : Logger by Logging.getLogger(RecordingLogger::class.java) {
        val warnMessages = mutableListOf<String>()
        val infoMessages = mutableListOf<String>()

        override fun warn(message: String) {
            warnMessages += message
        }

        override fun info(message: String) {
            infoMessages += message
        }
    }
}
