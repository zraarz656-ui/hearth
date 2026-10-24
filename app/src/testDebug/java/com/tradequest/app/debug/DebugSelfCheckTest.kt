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

        // Every group the harness runs must be represented. The old assertion
        // only counted passes and named four Phase 12 steps, so a harness that
        // stopped calling `runTradingChecks` — or `runWiringChecks`, or
        // `runPersistenceCheck` — would still pass on the remaining groups and
        // report a green run with a hole in it. Each entry below is a step that
        // only exists if its group actually ran.
        for (step in REQUIRED_STEPS) {
            assertTrue(
                "no passing self-check step matching '$step':\n$report",
                report.lineSequence().any { it.startsWith("PASS") && it.contains(step) },
            )
        }

        val summary = report.lineSequence().last { it.startsWith("=== self-check complete") }
        assertTrue(
            "the summary must report zero failures, was: $summary",
            summary.contains("0 failed"),
        )
    }

    private companion object {
        /**
         * One distinctive step per check group, plus every Phase 12 step.
         *
         * A group whose call site is deleted takes all of its steps with it, so a
         * single representative per group is enough to notice. The Phase 12 steps
         * are listed individually because that group is the newest and the one
         * most likely to be dropped from the harness by an unrelated edit.
         */
        val REQUIRED_STEPS = listOf(
            // Wiring.
            "start a challenge creates the season",
            // Trading.
            "the app builds a tradable session for the active season",
            // Challenge.
            "the rule set agrees with the engine's own loss floor",
            // News, blind mode and time warp.
            "12.5",
            "12.4",
            "12.6",
            "12.7",
            // Real time.
            "advances with real elapsed time",
            // Persistence.
            "survive a container restart",
        )
    }
}
