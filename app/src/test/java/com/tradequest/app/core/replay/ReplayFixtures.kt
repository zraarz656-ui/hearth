package com.tradequest.app.core.replay

import com.tradequest.app.core.market.DatasetClosure
import com.tradequest.app.core.market.DatasetSummary
import com.tradequest.app.core.market.DatasetValidationInfo
import com.tradequest.app.core.market.HistoricalCandle
import com.tradequest.app.core.market.MarketRepository
import com.tradequest.app.core.market.NewsItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Fixtures for the Phase 7 replay tests.
 *
 * # Why an in-memory [MarketRepository]
 *
 * The processor's contract is "given a dataset's candles, replay them
 * deterministically". Driving it through the real Room repository would test
 * SQLite's behaviour as much as the processor's, and would make a
 * batch-boundary test depend on database timing. A fixture that returns a fixed
 * candle list makes each test about the property it names. The Room-backed path
 * is exercised separately, in `RoomReplayStoreTest`, where persistence *is* the
 * thing under test.
 *
 * All times are UTC epoch milliseconds; all prices are integer thousandths of a
 * USD. Nothing here is historical data.
 */
internal object ReplayFixtures {

    /** 2024-01-01T00:00:00Z, a Monday. Matches `TradeFixtures.T0`. */
    const val T0: Long = 1_704_067_200_000L

    const val MINUTE: Long = 60_000L

    const val DATASET_ID: String = "ds_replay"

    const val SEASON_ID: String = "season_replay"

    /** Stored impact spellings treated as high impact, matching the DAO query. */
    private val HIGH_IMPACT = setOf("HIGH", "H")

    /** $10,000, as Step 6.10 fixes the starting balance. */
    const val START_BALANCE_MICROS: Long = 10_000L * 1_000_000L

    fun at(minuteIndex: Int, elapsedMs: Int = 0): Long = T0 + minuteIndex * MINUTE + elapsedMs

    /**
     * A bullish candle: open -> low -> high -> close.
     *
     * The path rises, so a buy's target is reached before any pullback.
     */
    fun bullish(minuteIndex: Int, open: Int, low: Int, high: Int, close: Int): HistoricalCandle =
        HistoricalCandle(
            timestampUtcMs = at(minuteIndex),
            openUnits = open,
            highUnits = high,
            lowUnits = low,
            closeUnits = close,
            tickVolume = 100,
        )

    /** A bearish candle: open -> high -> low -> close. */
    fun bearish(minuteIndex: Int, open: Int, high: Int, low: Int, close: Int): HistoricalCandle =
        HistoricalCandle(
            timestampUtcMs = at(minuteIndex),
            openUnits = open,
            highUnits = high,
            lowUnits = low,
            closeUnits = close,
            tickVolume = 100,
        )

    /**
     * A steady climb, one minute per step, from $2000.
     *
     * Rising, so a long entered at the open runs into profit and can be closed by
     * a target; useful for "did the replay produce exactly the trades I expect".
     */
    fun climbing(count: Int, startUnits: Int = 2_000_000, stepUnits: Int = 1_000): List<HistoricalCandle> =
        (0 until count).map { i ->
            val base = startUnits + i * stepUnits
            bullish(i, open = base, low = base - 100, high = base + stepUnits, close = base + stepUnits - 100)
        }

    /**
     * A flat series: every candle has zero range at [price].
     *
     * Price never moves, so nothing triggers. Used for tests about the replay
     * *machinery* — batching, resumption, idempotency — where a trade would be a
     * distraction.
     */
    fun flat(count: Int, price: Int = 2_000_000): List<HistoricalCandle> =
        (0 until count).map { i ->
            HistoricalCandle(
                timestampUtcMs = at(i),
                openUnits = price,
                highUnits = price,
                lowUnits = price,
                closeUnits = price,
                tickVolume = 100,
            )
        }

    /**
     * Two series separated by a multi-hour break.
     *
     * The break is a genuine gap: no candles exist across it, so the engine must
     * cross it as a single step rather than interpolating. Used by the multi-day
     * catch-up test.
     */
    fun acrossGap(
        beforeCount: Int = 5,
        gapMinutes: Int = 240,
        afterCount: Int = 5,
    ): List<HistoricalCandle> {
        val before = climbing(beforeCount)
        val afterStart = beforeCount + gapMinutes
        val after = (0 until afterCount).map { i ->
            val minuteIndex = afterStart + i
            val base = 2_000_000 + (beforeCount + i) * 1_000
            bullish(minuteIndex, open = base, low = base - 100, high = base + 1_000, close = base + 900)
        }
        return before + after
    }

    /**
     * A [MarketRepository] over a fixed candle list.
     *
     * Only the methods the processor uses are meaningful; the rest return empty
     * or zero, and are marked so rather than pretending to work. That is honest
     * for a fixture whose purpose is one narrow contract.
     */
    class FakeMarketRepository(
        private val candles: List<HistoricalCandle>,
        private val closures: List<DatasetClosure> = emptyList(),
        private val news: List<NewsItem> = emptyList(),
        private val datasetId: String = DATASET_ID,
    ) : MarketRepository {

        /** How many range reads were served, so a test can assert bounding. */
        var candleReadCount: Int = 0
            private set

        override fun observeDatasets(): Flow<List<DatasetSummary>> = flowOf(emptyList())

        override suspend fun listDatasets(): List<DatasetSummary> = emptyList()

        override suspend fun getDataset(datasetId: String): DatasetSummary? = null

        override suspend fun countDatasets(): Int = 0

        override suspend fun candlesInRange(
            datasetId: String,
            fromUtcMs: Long,
            toUtcMs: Long,
            maxRows: Int,
        ): List<HistoricalCandle> {
            require(datasetId == this.datasetId) {
                "fixture only serves $this.datasetId, asked for $datasetId"
            }
            candleReadCount++
            val rows = candles.filter { it.timestampUtcMs >= fromUtcMs && it.timestampUtcMs < toUtcMs }
            require(rows.size <= maxRows) {
                "range [$fromUtcMs, $toUtcMs) would return ${rows.size} rows, over the $maxRows bound"
            }
            return rows
        }

        override suspend fun candleAt(datasetId: String, timestampUtcMs: Long): HistoricalCandle? =
            candles.firstOrNull { it.timestampUtcMs == timestampUtcMs }

        override suspend fun candleCount(datasetId: String): Int = candles.size

        override suspend fun timeBounds(datasetId: String): Pair<Long, Long>? =
            if (candles.isEmpty()) {
                null
            } else {
                candles.first().timestampUtcMs to candles.last().timestampUtcMs
            }

        override suspend fun newsInRange(
            datasetId: String,
            fromUtcMs: Long,
            toUtcMs: Long,
            maxRows: Int,
        ): List<NewsItem> = news.filter { it.publishedAtUtcMs in fromUtcMs until toUtcMs }

        override suspend fun newsCount(datasetId: String): Int = news.size

        override suspend fun nextHighImpactNews(
            datasetId: String,
            afterUtcMs: Long,
        ): NewsItem? = news
            .filter { it.publishedAtUtcMs >= afterUtcMs && it.impact.trim().uppercase() in HIGH_IMPACT }
            .minByOrNull { it.publishedAtUtcMs }

        override suspend fun closures(datasetId: String): List<DatasetClosure> = closures

        override suspend fun validation(datasetId: String): DatasetValidationInfo? = null

        override suspend fun deleteDataset(datasetId: String): Boolean = false
    }
}
