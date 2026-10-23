package com.tradequest.app.core.market

import java.security.MessageDigest

/**
 * Summary of an imported dataset, as required by Step 1.7.
 *
 * [contentHash] is derived only from canonical candle content, so the same
 * data always yields the same hash regardless of file layout, column order,
 * timestamp format, or source timezone.
 */
data class DatasetManifest(
    val datasetId: String,
    val instrument: String,
    val startTimestampUtcMs: Long,
    val endTimestampUtcMs: Long,
    val candleCount: Int,
    val minimumPriceUnits: Int,
    val maximumPriceUnits: Int,
    val missingMinuteCount: Int,
    val duplicateCount: Int,
    val validationStatus: String,
    val sourceDescription: String,
    val contentHash: String,
    val weekendClosureDays: Int,
    val holidayClosureDays: Int,
    val unexpectedEmptyDays: Int,
) {
    /** Total candles implied by the covered span. */
    val expectedCandleCount: Int
        get() = if (candleCount == 0) 0
        else ((endTimestampUtcMs - startTimestampUtcMs) / 60_000L).toInt() + 1

    val coveragePercent: Double
        get() = expectedCandleCount.let { if (it == 0) 0.0 else candleCount * 100.0 / it }
}

/**
 * Builds dataset manifests and content hashes.
 *
 * Hashing is done over a canonical textual form of each candle: the UTC
 * epoch millisecond, the four prices as fixed 3-decimal strings, and the
 * volume. Nothing locale-dependent, and no floating point, enters the hash.
 * See Step 1.8.
 */
object DatasetManifestFactory {

    private const val HASH_ALGORITHM = "SHA-256"

    /** Hash of a candle series. Stable across runs, platforms, and file layouts. */
    fun computeContentHash(candles: List<HistoricalCandle>): String {
        val digest = MessageDigest.getInstance(HASH_ALGORITHM)
        val canonical = StringBuilder()

        candles.forEach { c ->
            canonical.setLength(0)
            canonical.append(c.timestampUtcMs).append('|')
                .append(PriceParser.unitsToCanonicalString(c.openUnits)).append('|')
                .append(PriceParser.unitsToCanonicalString(c.highUnits)).append('|')
                .append(PriceParser.unitsToCanonicalString(c.lowUnits)).append('|')
                .append(PriceParser.unitsToCanonicalString(c.closeUnits)).append('|')
                .append(c.tickVolume).append('\n')
            digest.update(canonical.toString().toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Dataset ID: a short, stable identifier derived from the content hash.
     *
     * Deriving it from content rather than from a timestamp means the same
     * data always maps to the same dataset, which keeps replay reproducible.
     */
    fun datasetIdFromHash(hash: String): String = "ds_" + hash.take(16)

    fun build(
        candles: List<HistoricalCandle>,
        instrument: String,
        validation: ValidationReport,
        closure: ClosureReport,
        sourceDescription: String,
        duplicateCount: Int,
    ): DatasetManifest {
        val hash = computeContentHash(candles)
        val prices = candles.flatMap { listOf(it.lowUnits, it.highUnits) }

        return DatasetManifest(
            datasetId = datasetIdFromHash(hash),
            instrument = instrument,
            startTimestampUtcMs = candles.firstOrNull()?.timestampUtcMs ?: 0L,
            endTimestampUtcMs = candles.lastOrNull()?.timestampUtcMs ?: 0L,
            candleCount = candles.size,
            minimumPriceUnits = prices.minOrNull() ?: 0,
            maximumPriceUnits = prices.maxOrNull() ?: 0,
            missingMinuteCount = validation.missingMinutes.size,
            duplicateCount = duplicateCount,
            validationStatus = validation.status,
            sourceDescription = sourceDescription,
            contentHash = hash,
            weekendClosureDays = closure.weekendDays,
            holidayClosureDays = closure.holidayDays,
            unexpectedEmptyDays = closure.unexpectedEmptyDays,
        )
    }
}
