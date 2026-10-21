package com.tradequest.app.core.replay

import com.tradequest.app.core.simulation.clock.ClockReading
import com.tradequest.app.core.simulation.clock.SimulationClock
import com.tradequest.app.core.trading.AccountState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * What a resume decided and did (Step 7.7).
 *
 * [owedSimMs] is how far historical time had run ahead of what the pipeline had
 * processed — the work the app owes after being closed. It is reported even when
 * the catch-up was clamped to the dataset end, so a caller can tell "nothing was
 * owed" from "work was owed but the dataset ran out".
 */
data class ResumeResult(
    val seasonId: String,
    val reading: ClockReading,
    val owedSimMs: Long,
    val outcome: ReplayOutcome,
) {
    val account: AccountState get() = outcome.account

    /** True when the catch-up stopped because the dataset ended, not the target. */
    val clampedToDatasetEnd: Boolean get() = !outcome.reachedTarget
}

/**
 * Drives a season's replay from the clock (Step 7.7).
 *
 * # The sequence
 *
 * 1. **Read the clock.** It says where simulated time is *now* and how far the
 *    pipeline has committed. The difference is what is owed.
 * 2. **Catch up.** Hand the target to the [ReplayProcessor], which resumes from
 *    the persisted cursor and commits each batch.
 * 3. **Commit the clock.** Only once the replay has genuinely reached the target
 *    is the clock told the pipeline is caught up, so a crash before that leaves
 *    the work owed rather than silently skipped.
 * 4. **Publish.** The final account is returned so the caller can render it.
 *
 * # Why step 3 is last
 *
 * Committing the clock first would tell it the pipeline had processed time it had
 * not, and the next resume would skip that interval entirely. Ordering the commit
 * after the replay is what makes "owed" monotonically decrease rather than reset.
 */
class ReplayCoordinator(
    private val clock: SimulationClock,
    private val processor: ReplayProcessor,
) {

    /**
     * Catches [seasonId] up to wherever the clock now stands.
     *
     * A season the clock is not attached to, or a clock with no reading, is not an
     * error: it means there is nothing to resume, and the result says so.
     */
    suspend fun resume(seasonId: String): ResumeResult? {
        val reading = clock.read() ?: return null
        return catchUp(seasonId, reading)
    }

    /**
     * The same catch-up, reporting each committed batch (Step 7.6).
     *
     * The UI path uses this so a long catch-up updates the account as it goes
     * rather than freezing until it finishes.
     */
    fun resumeAsFlow(seasonId: String): Flow<ReplayProgress> = flow {
        val reading = clock.read()
        if (reading == null) {
            emit(ReplayProgress.Complete(processor.process(seasonId, 0L)))
            return@flow
        }
        // The clock is committed from the outcome the processor actually reached,
        // not from the target it was asked for. See [catchUp].
        var reached = reading.lastCommittedSimUtcMs
        processor.processAsFlow(seasonId, reading.simUtcMs).collect { progress ->
            if (progress is ReplayProgress.Batch) {
                reached = progress.processedToSimUtcMs
            } else if (progress is ReplayProgress.Complete) {
                reached = progress.outcome.processedToSimUtcMs
            }
            emit(progress)
        }
        clock.commit(reached)
    }

    /**
     * Runs the catch-up for [reading] and commits the clock afterwards.
     *
     * Separated from [resume] so the ordering — replay, then clock — is stated
     * once and both entry points share it.
     */
    private suspend fun catchUp(seasonId: String, reading: ClockReading): ResumeResult {
        val outcome = processor.process(seasonId, reading.simUtcMs)
        // The clock is told only what the replay actually reached. When the
        // dataset ran out first, committing the target would mark unprocessed
        // time as done.
        clock.commit(outcome.processedToSimUtcMs)
        return ResumeResult(
            seasonId = seasonId,
            reading = reading,
            owedSimMs = reading.pendingSimMs,
            outcome = outcome,
        )
    }
}
