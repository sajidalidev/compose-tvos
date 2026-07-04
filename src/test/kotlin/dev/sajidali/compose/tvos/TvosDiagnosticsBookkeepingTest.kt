package dev.sajidali.compose.tvos

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task 10e: unit tests for [TvosDiagnosticsBookkeeping], in particular the
 * "official-first, fork-discovery-empty" precedence decision in
 * [TvosDiagnosticsBookkeeping.recordEmptyForkDiscovery] -- factored out of
 * [TvosVariantInjectionRule.execute] specifically so it is unit-testable without a Gradle
 * `ComponentMetadataContext`.
 */
class TvosDiagnosticsBookkeepingTest {

    @BeforeTest
    @AfterTest
    fun resetBookkeeping() {
        // This bookkeeping is daemon-lifetime, companion-object-level state (by design -- see
        // its own KDoc); reset before AND after every test in this class so no test's writes
        // leak into another (this test class, unlike the others in this module, unavoidably
        // touches this shared, mutable, non-test-scoped singleton directly).
        TvosDiagnosticsBookkeeping.resetDiagnostics()
    }

    @Test
    fun `recordEmptyForkDiscovery records a skip, not a genuine gap, when the official artifact already covers the target`() {
        TvosDiagnosticsBookkeeping.recordEmptyForkDiscovery(
            sourceModule = "org.jetbrains.compose.runtime:runtime:1.13.0-beta01",
            targetCoordinate = "dev.sajidali.compose.runtime:runtime:1.13.0-beta01",
            alreadySupportedTargets = setOf("tvos_arm64"),
            repositoryUrls = emptyList(),
            offline = false
        )

        val snapshot = TvosDiagnosticsBookkeeping.diagnosticsSnapshot()
        assertEquals(emptyList(), snapshot.emptyDiscoveries, "must not be recorded as a genuine gap")
        assertEquals(1, snapshot.skippedAlreadySupported.size)
        val record = snapshot.skippedAlreadySupported.single()
        assertEquals("org.jetbrains.compose.runtime:runtime:1.13.0-beta01", record.sourceModule)
        assertEquals(listOf("tvos_arm64"), record.skippedNativeTargets)
    }

    @Test
    fun `recordEmptyForkDiscovery records a genuine gap when the official artifact has no tvOS coverage either`() {
        TvosDiagnosticsBookkeeping.recordEmptyForkDiscovery(
            sourceModule = "g:material-ripple:1.11.0-beta03",
            targetCoordinate = "dev.sajidali.g:material-ripple:1.11.0-beta03",
            alreadySupportedTargets = emptySet(),
            repositoryUrls = emptyList(),
            offline = false
        )

        val snapshot = TvosDiagnosticsBookkeeping.diagnosticsSnapshot()
        assertEquals(emptyList(), snapshot.skippedAlreadySupported, "must not be recorded as an already-supported skip")
        assertEquals(1, snapshot.emptyDiscoveries.size)
        assertEquals("g:material-ripple:1.11.0-beta03", snapshot.emptyDiscoveries.single().sourceModule)
    }

    @Test
    fun `resetDiagnostics clears injections, emptyDiscoveries and skippedAlreadySupported`() {
        TvosDiagnosticsBookkeeping.recordInjection(InjectionRecord("g:m:1.0", "t:m:1.0", 1))
        TvosDiagnosticsBookkeeping.recordEmptyDiscovery(EmptyDiscoveryRecord("g:m2:1.0", "t:m2:1.0", emptyList(), false))
        TvosDiagnosticsBookkeeping.recordSkippedAlreadySupported(
            SkippedAlreadySupportedRecord("g:m3:1.0", "t:m3:1.0", listOf("tvos_arm64"))
        )

        TvosDiagnosticsBookkeeping.resetDiagnostics()

        val snapshot = TvosDiagnosticsBookkeeping.diagnosticsSnapshot()
        assertEquals(emptyList(), snapshot.injections)
        assertEquals(emptyList(), snapshot.emptyDiscoveries)
        assertEquals(emptyList(), snapshot.skippedAlreadySupported)
    }

    @Test
    fun `recordInjection is first-write-wins for the same sourceModule`() {
        TvosDiagnosticsBookkeeping.recordInjection(InjectionRecord("g:m:1.0", "t:m:1.0", 1))
        TvosDiagnosticsBookkeeping.recordInjection(InjectionRecord("g:m:1.0", "t:m:1.0", 99))

        val snapshot = TvosDiagnosticsBookkeeping.diagnosticsSnapshot()
        assertEquals(1, snapshot.injections.size)
        assertEquals(1, snapshot.injections.single().variantCount, "the first recorded injection must win")
    }
}
