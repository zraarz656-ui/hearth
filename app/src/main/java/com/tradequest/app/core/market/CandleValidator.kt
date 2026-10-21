package com.tradequest.app.core.market

/** Severity of a validation finding. */
enum class Severity { ERROR, WARNING }

/** A single validation finding, with the offending timestamp when known. */
data class ValidationIssue(
    val severity: Severity,
    val code: String,
    val message: String,
    val timestampUtcMs: Long? = null,
) {
    override fun toString(): String {
        val where = timestampUtcMs?.let { " @$it" } ?: ""
        return "[$severity $code]$where $message"
    }
}

/** Outcome of validating a candle series. */
data class ValidationReport(
    val issues: List<ValidationIssue>,
    val candleCount: Int,
    val duplicateTimestamps: List<Long>,
    val missingMinutes: List<Long>,
    val unexpectedGaps: List<GapRange>,
) {
    val errors: List<ValidationIssue> get() = issues.filter { it.severity == Severity.ERROR }
    val warnings: List<ValidationIssue> get() = issues.filter { it.severity == Severity.WARNING }

    /** True when no ERROR-level finding was recorded. */
    val isValid: Boolean get() = errors.isEmpty()

    val status: String
        get() = when {
            !isValid -> "INVALID"
            warnings.isNotEmpty() -> "VALID_WITH_WARNINGS"
            else -> "VALID"
        }

    /** A contiguous run of missing minutes. */
    data class GapRange(val startUtcMs: Long, val endUtcMs: Long, val minuteCount: Int)
}

/**
 * Validates candle series against the Phase 1 Step 1.3 rule set.
 *
 * Structural problems are errors and must block import. Gaps and spikes are
 * reported but do not invalidate a dataset: real market data legitimately
 * contains quiet periods, and filling them would fabricate history.
 */
object CandleValidator {

    private const val MINUTE_MS = 60_000L

    /**
     * A candle range beyond this many times the median range is flagged as a
     * suspicious spike. Gold can move sharply on news, so this only catches
     * clearly corrupt values.
     */
    private const val SPIKE_MULTIPLIER = 20

    fun validate(candles: List<HistoricalCandle>): ValidationReport {
        val issues = mutableListOf<ValidationIssue>()

        if (candles.isEmpty()) {
            issues += ValidationIssue(Severity.ERROR, "EMPTY", "candle series is empty")
            return ValidationReport(issues, 0, emptyList(), emptyList(), emptyList())
        }

        candles.forEach { c -> validateSingle(c, issues) }
        validateOrdering(candles, issues)
        validateSpikes(candles, issues)

        val duplicates = findDuplicates(candles)
        if (duplicates.isNotEmpty()) {
            issues += ValidationIssue(
                Severity.ERROR,
                "DUPLICATE_TIMESTAMP",
                "${duplicates.size} duplicate timestamp(s)",
                duplicates.first(),
            )
        }

        val missing = findMissingMinutes(candles)
        val gaps = groupGaps(missing)

        return ValidationReport(
            issues = issues,
            candleCount = candles.size,
            duplicateTimestamps = duplicates,
            missingMinutes = missing,
            unexpectedGaps = gaps,
        )
    }

    private fun validateSingle(candle: HistoricalCandle, issues: MutableList<ValidationIssue>) {
        val ts = candle.timestampUtcMs
        val prices = listOf(
            "open" to candle.openUnits,
            "high" to candle.highUnits,
            "low" to candle.lowUnits,
            "close" to candle.closeUnits,
        )

        prices.forEach { (name, value) ->
            if (value <= 0) {
                issues += ValidationIssue(
                    Severity.ERROR, "NON_POSITIVE_PRICE",
                    "$name price is non-positive: $value", ts,
                )
            }
        }

        if (candle.highUnits < candle.openUnits) {
            issues += ValidationIssue(
                Severity.ERROR, "HIGH_LT_OPEN",
                "high (${candle.highUnits}) < open (${candle.openUnits})", ts,
            )
        }
        if (candle.highUnits < candle.closeUnits) {
            issues += ValidationIssue(
                Severity.ERROR, "HIGH_LT_CLOSE",
                "high (${candle.highUnits}) < close (${candle.closeUnits})", ts,
            )
        }
        if (candle.lowUnits > candle.openUnits) {
            issues += ValidationIssue(
                Severity.ERROR, "LOW_GT_OPEN",
                "low (${candle.lowUnits}) > open (${candle.openUnits})", ts,
            )
        }
        if (candle.lowUnits > candle.closeUnits) {
            issues += ValidationIssue(
                Severity.ERROR, "LOW_GT_CLOSE",
                "low (${candle.lowUnits}) > close (${candle.closeUnits})", ts,
            )
        }
        if (candle.highUnits < candle.lowUnits) {
            issues += ValidationIssue(
                Severity.ERROR, "HIGH_LT_LOW",
                "high (${candle.highUnits}) < low (${candle.lowUnits})", ts,
            )
        }
        if (candle.tickVolume < 0) {
            issues += ValidationIssue(
                Severity.ERROR, "NEGATIVE_VOLUME",
                "negative tick volume: ${candle.tickVolume}", ts,
            )
        }
        if (candle.timestampUtcMs < 0) {
            issues += ValidationIssue(
                Severity.ERROR, "NEGATIVE_TIMESTAMP",
                "negative timestamp: ${candle.timestampUtcMs}", ts,
            )
        }
    }

    private fun validateOrdering(
        candles: List<HistoricalCandle>,
        issues: MutableList<ValidationIssue>,
    ) {
        for (i in 1 until candles.size) {
            if (candles[i].timestampUtcMs < candles[i - 1].timestampUtcMs) {
                issues += ValidationIssue(
                    Severity.ERROR, "OUT_OF_ORDER",
                    "timestamp ${candles[i].timestampUtcMs} follows " +
                        "${candles[i - 1].timestampUtcMs}",
                    candles[i].timestampUtcMs,
                )
                return
            }
        }
    }

    private fun validateSpikes(
        candles: List<HistoricalCandle>,
        issues: MutableList<ValidationIssue>,
    ) {
        val ranges = candles.map { it.rangeUnits.toLong() }.sorted()
        val median = ranges[ranges.size / 2]
        if (median <= 0) return
        val threshold = median * SPIKE_MULTIPLIER

        candles.forEach { c ->
            if (c.rangeUnits > threshold) {
                issues += ValidationIssue(
                    Severity.WARNING, "SUSPICIOUS_SPIKE",
                    "range ${c.rangeUnits} units exceeds ${threshold} (median $median)",
                    c.timestampUtcMs,
                )
            }
        }
    }

    private fun findDuplicates(candles: List<HistoricalCandle>): List<Long> {
        val seen = HashSet<Long>(candles.size)
        val duplicates = mutableListOf<Long>()
        candles.forEach { c ->
            if (!seen.add(c.timestampUtcMs)) duplicates += c.timestampUtcMs
        }
        return duplicates.distinct()
    }

    /**
     * Returns minutes absent between the first and last candle.
     *
     * Absence is a fact to report, never to fill. Whether a given gap is an
     * expected closure is decided by [MarketClosureDetector].
     */
    private fun findMissingMinutes(candles: List<HistoricalCandle>): List<Long> {
        if (candles.size < 2) return emptyList()
        val present = HashSet<Long>(candles.size)
        candles.forEach { present.add(it.timestampUtcMs) }

        val start = candles.first().timestampUtcMs
        val end = candles.last().timestampUtcMs
        val missing = mutableListOf<Long>()
        var t = start
        while (t <= end) {
            if (!present.contains(t)) missing += t
            t += MINUTE_MS
        }
        return missing
    }

    private fun groupGaps(missing: List<Long>): List<ValidationReport.GapRange> {
        if (missing.isEmpty()) return emptyList()
        val gaps = mutableListOf<ValidationReport.GapRange>()
        var start = missing[0]
        var prev = missing[0]
        var count = 1
        for (i in 1 until missing.size) {
            if (missing[i] == prev + MINUTE_MS) {
                count++
            } else {
                gaps += ValidationReport.GapRange(start, prev, count)
                start = missing[i]
                count = 1
            }
            prev = missing[i]
        }
        gaps += ValidationReport.GapRange(start, prev, count)
        return gaps
    }
}
