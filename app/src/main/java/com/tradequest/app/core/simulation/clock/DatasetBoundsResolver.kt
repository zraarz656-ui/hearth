package com.tradequest.app.core.simulation.clock

import com.tradequest.app.core.market.MarketRepository

/**
 * Builds [DatasetBounds] from stored market data (Step 3.8).
 *
 * The clock is told its bounds rather than looking them up, which keeps
 * `core.simulation` free of a repository dependency. This helper is the one
 * place that turns a dataset's stored extent into clock bounds, so the rule
 * "the simulation never leaves its dataset" has a single definition.
 *
 * # Why the bounds are the candle extent, not the dataset metadata
 *
 * `DatasetSummary.startTimestampUtcMs` is what the *manifest* recorded. The
 * candle table is what the simulation can actually replay. If an import ever
 * stored fewer candles than its manifest claims, trusting the manifest would
 * let the clock run into a region with no candles, and the chart would advance
 * over a hole. The candle extent is therefore authoritative.
 */
object DatasetBoundsResolver {

    /**
     * The bounds for [datasetId], or null when the dataset has no candles.
     *
     * A null result is a refusal, not an error to paper over: a season cannot be
     * started on an empty dataset, because there would be nothing to replay.
     */
    suspend fun resolve(
        marketRepository: MarketRepository,
        datasetId: String,
    ): DatasetBounds? {
        val (first, last) = marketRepository.timeBounds(datasetId) ?: return null
        return DatasetBounds(startSimUtcMs = first, endSimUtcMs = last)
    }
}
