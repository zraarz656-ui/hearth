package com.tradequest.app.core.database.player

import com.tradequest.app.core.challenge.SeasonMode

import com.tradequest.app.core.simulation.clock.ClockAnchor
import com.tradequest.app.core.simulation.clock.ClockMode
import com.tradequest.app.core.simulation.clock.ClockStore
import com.tradequest.app.core.simulation.clock.PlaybackSpeed

/**
 * Persists the simulation clock into `tradequest.db` (Step 3.5).
 *
 * This is the adapter that lets `core.simulation` stay free of any database
 * dependency: the clock declares [ClockStore], and the mapping to
 * [ReplayCheckpointEntity] lives here, in the database layer, where the entity
 * already is.
 *
 * `ReplayCheckpoint` was created in Phase 2 with every field Step 3.5 requires
 * except the clock mode, so no schema change and no migration is needed.
 */
class RoomClockStore(
    private val db: PlayerDatabase,
) : ClockStore {

    private val dao get() = db.playerDao()

    override suspend fun load(seasonId: String): ClockAnchor? {
        val row = dao.checkpoint(seasonId) ?: return null
        val season = dao.getSeason(seasonId) ?: return null

        // A speed that is not one of the supported multipliers means the stored
        // row is not trustworthy. Falling back to 1x is the safe direction: it
        // under-advances rather than over-advancing a season.
        val speed = PlaybackSpeed.fromMultiplier(row.playbackSpeed) ?: PlaybackSpeed.DEFAULT

        return ClockAnchor(
            historicalAnchorSimUtcMs = row.historicalAnchorSimUtcMs,
            realAnchorUtcMs = row.realAnchorUtcMs,
            mode = clockModeOf(season.mode),
            speed = speed,
            isPaused = row.isPaused,
            lastCommittedSimUtcMs = row.lastCommittedSimUtcMs,
        )
    }

    override suspend fun save(seasonId: String, anchor: ClockAnchor) {
        dao.upsertCheckpoint(
            ReplayCheckpointEntity(
                seasonId = seasonId,
                lastCommittedSimUtcMs = anchor.lastCommittedSimUtcMs,
                historicalAnchorSimUtcMs = anchor.historicalAnchorSimUtcMs,
                realAnchorUtcMs = anchor.realAnchorUtcMs,
                playbackSpeed = anchor.speed.multiplier,
                isPaused = anchor.isPaused,
                updatedAtRealUtcMs = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun delete(seasonId: String) {
        dao.deleteCheckpoint(seasonId)
    }

    companion object {
        /**
         * Maps a persisted season mode to the clock's mode.
         *
         * The mode is read from the season rather than stored on the checkpoint
         * row, because adding a column to a released schema would require a
         * migration for no gain: the mapping is total and unambiguous.
         *
         * The two enums are separate types because they mean different things.
         * `SeasonMode` decides *which rules apply* to a season; [ClockMode]
         * decides *how time advances*. Today `CHALLENGE` in one always implies
         * `CHALLENGE` in the other, and this function is the single place that
         * states so. If the two ever diverge, this is the only code to change.
         *
         * An unrecognised stored value maps to `CHALLENGE`, the conservative
         * choice: Challenge Mode is locked to 1x, so an unrecognised mode
         * cannot let a season run at 60x.
         */
        fun clockModeOf(seasonMode: String): ClockMode = when (seasonMode) {
            SeasonMode.PRACTICE.name -> ClockMode.PRACTICE
            else -> ClockMode.CHALLENGE
        }
    }
}
