package com.tradequest.app.core.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * Phase 1 Step 1.10 tests, plus the acceptance checks recorded in
 * `docs/adr-0001-price-scale.md`.
 */
class CandleImportTest {

    private val utc = ZoneId.of("UTC")
    private fun config() = CsvImportConfig.dukascopy()

    // ---------- Correct import ----------

    @Test
    fun `imports a well formed csv`() {
        val candles = SyntheticFixtures.bullish(count = 20)
        val result = CsvImporter.import(SyntheticFixtures.toCsv(candles), config())

        assertEquals(20, result.candles.size)
        assertTrue(result.validation.isValid)
        assertTrue(result.importedSuccessfully)
        assertEquals(0, result.validation.missingMinutes.size)
    }

    @Test
    fun `prices survive a round trip through csv exactly`() {
        val candles = SyntheticFixtures.bullish(count = 10)
        val result = CsvImporter.import(SyntheticFixtures.toCsv(candles), config())
        assertEquals(candles, result.candles)
    }

    @Test
    fun `imports three decimal prices without loss`() {
        val csv = """
            timestamp_utc,open,high,low,close,tick_volume
            2024-01-15T00:00:00Z,2047.204,2047.525,2047.101,2047.499,42
        """.trimIndent()

        val result = CsvImporter.import(csv, config())
        val c = result.candles.single()

        assertEquals(2_047_204, c.openUnits)
        assertEquals(2_047_525, c.highUnits)
        assertEquals(2_047_101, c.lowUnits)
        assertEquals(2_047_499, c.closeUnits)
        assertEquals(42, c.tickVolume)
    }

    @Test
    fun `honours a different column order`() {
        val csv = """
            time,close,open,low,high,vol
            2024-01-15T00:00:00Z,2047.499,2047.204,2047.101,2047.525,7
        """.trimIndent()

        val result = CsvImporter.import(
            csv,
            config().copy(
                timestampColumn = "time",
                closeColumn = "close",
                openColumn = "open",
                lowColumn = "low",
                highColumn = "high",
                volumeColumn = "vol",
            ),
        )

        val c = result.candles.single()
        assertEquals(2_047_204, c.openUnits)
        assertEquals(2_047_525, c.highUnits)
        assertEquals(7, c.tickVolume)
    }

    @Test
    fun `honours a semicolon delimiter and comma decimal separator`() {
        val csv = """
            timestamp_utc;open;high;low;close;tick_volume
            2024-01-15T00:00:00Z;2047,204;2047,525;2047,101;2047,499;9
        """.trimIndent()

        val result = CsvImporter.import(
            csv,
            config().copy(delimiter = ';', decimalSeparator = ',', groupingSeparator = null),
        )

        assertEquals(2_047_204, result.candles.single().openUnits)
    }

    @Test
    fun `reads positional columns when there is no header`() {
        val csv = """
            2024-01-15T00:00:00Z,2047.204,2047.525,2047.101,2047.499,11
            2024-01-15T00:01:00Z,2047.499,2047.600,2047.400,2047.550,12
        """.trimIndent()

        val result = CsvImporter.import(csv, config().copy(hasHeader = false))

        assertEquals(2, result.candles.size)
        assertEquals(2_047_204, result.candles[0].openUnits)
        assertEquals(11, result.candles[0].tickVolume)
    }

    @Test
    fun `reads epoch millisecond timestamps`() {
        val csv = """
            timestamp_utc,open,high,low,close,tick_volume
            1705276800000,2047.204,2047.525,2047.101,2047.499,5
        """.trimIndent()

        val result = CsvImporter.import(
            csv,
            config().copy(timestampFormat = CsvImportConfig.TimestampFormat.EPOCH_MILLIS),
        )

        assertEquals(SyntheticFixtures.MONDAY_00_UTC, result.candles.single().timestampUtcMs)
    }

    // ---------- Bad timestamps ----------

    @Test(expected = CsvImportException::class)
    fun `rejects an unparseable timestamp`() {
        val csv = """
            timestamp_utc,open,high,low,close,tick_volume
            not-a-date,2047.204,2047.525,2047.101,2047.499,5
        """.trimIndent()
        CsvImporter.import(csv, config())
    }

    @Test
    fun `reports the offending line number for a bad timestamp`() {
        val csv = """
            timestamp_utc,open,high,low,close,tick_volume
            2024-01-15T00:00:00Z,2047.204,2047.525,2047.101,2047.499,5
            garbage,2047.204,2047.525,2047.101,2047.499,5
        """.trimIndent()

        val error = runCatching { CsvImporter.import(csv, config()) }.exceptionOrNull()
        assertTrue(error is CsvImportException)
        assertEquals(3, (error as CsvImportException).lineNumber)
    }

    @Test(expected = CsvImportException::class)
    fun `rejects a timezone-less timestamp under an explicit pattern`() {
        val csv = """
            timestamp_utc,open,high,low,close,tick_volume
            2024-01-15 00:00:00,2047.204,2047.525,2047.101,2047.499,5
        """.trimIndent()
        CsvImporter.import(csv, config().copy(timestampFormat = CsvImportConfig.TimestampFormat.ISO_8601_UTC))
    }

    @Test
    fun `applies the declared source timezone rather than assuming utc`() {
        val csv = """
            timestamp_utc,open,high,low,close,tick_volume
            2024-01-15 00:00:00,2047.204,2047.525,2047.101,2047.499,5
        """.trimIndent()

        val result = CsvImporter.import(
            csv,
            config().copy(
                timestampFormat = CsvImportConfig.TimestampFormat.EXPLICIT_PATTERN,
                timestampPattern = "yyyy-MM-dd HH:mm:ss",
                sourceTimeZone = ZoneId.of("America/New_York"),
            ),
        )

        // 2024-01-15 00:00 New York (EST, UTC-5) is 05:00 UTC.
        assertEquals(1_705_294_800_000L, result.candles.single().timestampUtcMs)
    }

    // ---------- Invalid OHLC ----------

    @Test
    fun `rejects every invalid ohlc relationship`() {
        val report = CandleValidator.validate(SyntheticFixtures.invalidOhlc())

        assertFalse(report.isValid)
        val codes = report.errors.map { it.code }.toSet()
        assertTrue(codes.contains("HIGH_LT_OPEN"))
        assertTrue(codes.contains("LOW_GT_OPEN"))
        assertTrue(codes.contains("HIGH_LT_LOW"))
    }

    @Test
    fun `rejects non positive prices`() {
        val candles = listOf(
            SyntheticFixtures.candle(SyntheticFixtures.MONDAY_00_UTC, 0, 100, -50, 50),
        )
        val report = CandleValidator.validate(candles)
        assertFalse(report.isValid)
        assertTrue(report.errors.any { it.code == "NON_POSITIVE_PRICE" })
    }

    @Test
    fun `rejects negative volume`() {
        val candles = listOf(
            SyntheticFixtures.candle(SyntheticFixtures.MONDAY_00_UTC, 100, 200, 50, 150, -1),
        )
        val report = CandleValidator.validate(candles)
        assertFalse(report.isValid)
        assertTrue(report.errors.any { it.code == "NEGATIVE_VOLUME" })
    }

    @Test
    fun `rejects out of order timestamps`() {
        val candles = listOf(
            SyntheticFixtures.candle(SyntheticFixtures.MONDAY_00_UTC + 60_000, 100, 200, 50, 150),
            SyntheticFixtures.candle(SyntheticFixtures.MONDAY_00_UTC, 100, 200, 50, 150),
        )
        val report = CandleValidator.validate(candles)
        assertFalse(report.isValid)
        assertTrue(report.errors.any { it.code == "OUT_OF_ORDER" })
    }

    @Test(expected = CsvImportException::class)
    fun `rejects a non numeric price`() {
        val csv = """
            timestamp_utc,open,high,low,close,tick_volume
            2024-01-15T00:00:00Z,abc,2047.525,2047.101,2047.499,5
        """.trimIndent()
        CsvImporter.import(csv, config())
    }

    @Test(expected = CsvImportException::class)
    fun `rejects a missing price field`() {
        val csv = """
            timestamp_utc,open,high,low,close,tick_volume
            2024-01-15T00:00:00Z,,2047.525,2047.101,2047.499,5
        """.trimIndent()
        CsvImporter.import(csv, config())
    }

    // ---------- Duplicate candles ----------

    @Test
    fun `detects duplicate timestamps`() {
        val report = CandleValidator.validate(SyntheticFixtures.duplicateTimestamps())
        assertFalse(report.isValid)
        assertEquals(1, report.duplicateTimestamps.size)
        assertTrue(report.errors.any { it.code == "DUPLICATE_TIMESTAMP" })
    }

    // ---------- Gaps ----------

    @Test
    fun `detects an unexpected intraday gap`() {
        val candles = SyntheticFixtures.unexpectedGap(before = 30, missingMinutes = 45, after = 30)
        val report = CandleValidator.validate(candles)

        assertTrue("structural errors should not be present", report.isValid)
        assertEquals(45, report.missingMinutes.size)
        assertEquals(1, report.unexpectedGaps.size)
        assertEquals(45, report.unexpectedGaps.single().minuteCount)
    }

    @Test
    fun `classifies a weekend gap as an expected closure`() {
        val candles = SyntheticFixtures.weekendGap()
        val validation = CandleValidator.validate(candles)
        val closure = MarketClosureDetector.classify(candles, validation, utc)

        assertTrue(closure.weekendDays >= 1)
        val saturday = closure.days.firstOrNull { it.date.dayOfWeek.value == 6 }
        assertTrue("Saturday should be classified as weekend closure",
            saturday != null && saturday.reason == ClosureReason.WEEKEND)
    }

    @Test
    fun `classifies an unexpected empty weekday as unexpected not closure`() {
        // Tuesday 2024-01-16 and Friday 2024-01-19, leaving Wednesday and
        // Thursday 2024-01-17/18 with no data at all.
        val tuesday = 1_705_363_200_000L // 2024-01-16 00:00 UTC
        val friday = 1_705_622_400_000L  // 2024-01-19 00:00 UTC
        val candles = SyntheticFixtures.bullish(tuesday, 60) +
            SyntheticFixtures.bullish(friday, 60)

        val validation = CandleValidator.validate(candles)
        val closure = MarketClosureDetector.classify(candles, validation, utc)

        assertEquals(2, closure.unexpectedEmptyDays)
        assertTrue(closure.hasUnexpectedGaps)
    }

    @Test
    fun `does not excuse a fully empty friday as weekend closure`() {
        // Friday 2024-01-19 carries no data, sitting between Thursday and
        // Saturday. It must be reported as unexpected rather than assumed
        // to be a normal closure.
        val thursday = 1_705_622_400_000L - 86_400_000L // 2024-01-18 00:00 UTC
        val saturday = 1_705_708_800_000L               // 2024-01-20 00:00 UTC

        val candles = SyntheticFixtures.bullish(thursday, 60) +
            SyntheticFixtures.bullish(saturday, 60)
        val validation = CandleValidator.validate(candles)
        val closure = MarketClosureDetector.classify(candles, validation, utc)

        val fridayDay = closure.days.first { it.date.dayOfWeek.value == 5 }
        assertEquals(ClosureReason.UNEXPECTED, fridayDay.reason)
    }

    @Test
    fun `does not treat every missing minute as closure`() {
        val candles = SyntheticFixtures.unexpectedGap(missingMinutes = 10)
        val validation = CandleValidator.validate(candles)
        val closure = MarketClosureDetector.classify(candles, validation, utc)

        assertEquals(10, closure.totalMissingMinutes)
        assertEquals(0, closure.weekendDays)
    }

    // ---------- Suspicious spikes ----------

    @Test
    fun `flags a suspicious spike without invalidating the dataset`() {
        val report = CandleValidator.validate(SyntheticFixtures.priceSpike())

        assertTrue("a spike is a warning, not an error", report.isValid)
        assertTrue(report.warnings.any { it.code == "SUSPICIOUS_SPIKE" })
    }

    // ---------- Padding rows ----------

    @Test
    fun `drops flat zero volume padding rows`() {
        val csv = """
            timestamp_utc,open,high,low,close,tick_volume
            2024-01-15T00:00:00Z,2047.204,2047.525,2047.101,2047.499,42
            2024-01-15T00:01:00Z,2047.499,2047.499,2047.499,2047.499,0
        """.trimIndent()

        val result = CsvImporter.import(csv, config())

        assertEquals(1, result.candles.size)
        assertEquals(1, result.droppedPaddingRows)
    }

    @Test
    fun `keeps flat rows that carry volume`() {
        val csv = """
            timestamp_utc,open,high,low,close,tick_volume
            2024-01-15T00:00:00Z,2047.499,2047.499,2047.499,2047.499,7
        """.trimIndent()

        val result = CsvImporter.import(csv, config())

        assertEquals(1, result.candles.size)
        assertEquals(0, result.droppedPaddingRows)
    }

    // ---------- DST ----------

    @Test
    fun `keeps the utc timeline continuous across a dst transition`() {
        val candles = SyntheticFixtures.dstTransition()
        val validation = CandleValidator.validate(candles)

        assertEquals(120, candles.size)
        assertEquals(0, validation.missingMinutes.size)
        assertTrue(validation.isValid)
    }

    // ---------- Doji ----------

    @Test
    fun `imports doji candles`() {
        val candles = SyntheticFixtures.doji(count = 5)
        val result = CsvImporter.import(SyntheticFixtures.toCsv(candles), config())

        assertTrue(result.validation.isValid)
        assertTrue(result.candles.all { it.isDoji })
    }

    // ---------- Fixtures are labelled synthetic ----------

    @Test
    fun `fixtures are marked synthetic`() {
        assertTrue(SyntheticFixtures.SYNTHETIC_MARKER.contains("SYNTHETIC"))
        assertTrue(SyntheticFixtures.SYNTHETIC_MARKER.contains("NOT HISTORICAL"))
    }
}
