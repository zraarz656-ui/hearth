package com.tradequest.app.core.database.player

import androidx.room.withTransaction
import com.tradequest.app.core.replay.ReplayStateCodec
import com.tradequest.app.core.replay.ReplayStore
import com.tradequest.app.core.trading.TradingState

/**
 * The [ReplayStore] adapter over `tradequest.db` (Steps 7.3-7.4).
 *
 * This is what lets `core.replay` stay free of any database dependency: the
 * processor declares the port, and the mapping to [ReplayStateEntity] lives here,
 * in the database layer, alongside the entity. The dependency points inwards.
 *
 * # Atomicity
 *
 * [commit] runs inside a Room transaction. One row is written, so the transaction
 * is not strictly necessary for a single statement — SQLite already makes one
 * statement atomic — but it is kept because it is the property Step 7.4 asks for
 * and it is what makes the guarantee survive a later change that writes a second
 * row (an equity snapshot, a daily-stats row) in the same commit. Encoding the
 * intent now means that change cannot quietly break the guarantee.
 */
class RoomReplayStore(
    private val db: PlayerDatabase,
) : ReplayStore {

    private val dao get() = db.playerDao()

    override suspend fun load(seasonId: String): TradingState? {
        val row = dao.replayState(seasonId) ?: return null
        return ReplayStateCodec.decode(row.stateJson)
    }

    override suspend fun commit(state: TradingState) {
        val encoded = ReplayStateCodec.encode(state)
        val row = ReplayStateEntity(
            seasonId = state.seasonId,
            stateJson = encoded,
            processedToSimUtcMs = state.processedToSimUtcMs,
            schemaVersion = ReplayStateCodec.SCHEMA_VERSION,
            updatedAtRealUtcMs = System.currentTimeMillis(),
        )
        db.withTransaction {
            dao.upsertReplayState(row)
        }
    }

    override suspend fun delete(seasonId: String) {
        dao.deleteReplayState(seasonId)
    }
}
