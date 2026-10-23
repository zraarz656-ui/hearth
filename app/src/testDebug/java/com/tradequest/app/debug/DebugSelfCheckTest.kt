package com.tradequest.app.debug

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs [DebugSelfCheck] and asserts every step passed.
 *
 * # Why this test exists
 *
 * `DebugSelfCheck` is the on-device harness for the Phase 10 and Phase 11 flows,
 * and it is *itself* only exercised by hand — the tester taps a button and reads
 * the log. A harness that is silently broken is worse than none: it would report
 * nothing and look like the flows were fine. This test runs the same code under
 * Robolectric, so the report is known-good before it ever reaches a device, and a
 * change that breaks a step fails here instead of on the phone.
 *
 * It cannot replace the device run: Robolectric's clock is not a real wall clock,
 * so the "1x advances with real elapsed time" step is the one claim only a device
 * can settle. Everything else — the wiring, the trading path, the challenge
 * evaluation, the persistence — is asserted here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DebugSelfCheckTest {

    @Test
    fun `every self-check step passes`() = runTest {
        val report = DebugSelfCheck.run(ApplicationProvider.getApplicationContext())

        // The report is line-oriented so a device paste is readable; assert on the
        // summary rather than re-deriving each line here.
        val failures = report.lineSequence().filter { it.startsWith("FAIL") }.toList()
        assertEquals("self-check failures:\n${failures.joinToString("\n")}", 0, failures.size)

        val passes = report.lineSequence().count { it.startsWith("PASS") }
        assertTrue("expected several passing steps, found $passes", passes >= 10)

        val summary = report.lineSequence().last { it.startsWith("=== self-check complete") }
        assertTrue(
            "the summary must report zero failures, was: $summary",
            summary.contains("0 failed"),
        )
    }
}
