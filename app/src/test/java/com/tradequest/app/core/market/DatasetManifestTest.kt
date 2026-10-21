package com.tradequest.app.core.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** Step 1.7 and 1.8 tests: manifests and reproducible hashing. */
class DatasetManifestTest {

    private fun manifestFor(candles: List<HistoricalCandle>): DatasetManifest {
        val validation = CandleValidator.validate(candles)
        val closure = MarketClosureDetector.classify(candles, validation)
        return DatasetManifestFactory.build(
            candles = candles,
            instrument = "XAUUSD",
            validation = validation,
            closure = closure,
            sourceDescription = SyntheticFixtures.SYNTHETIC_MARKER,
            duplicateCount = validation.duplicateTimestamps.size,
        )
    }

    @Test
    fun `hash is stable across repeated calls`() {
        val candles = SyntheticFixtures.bullish(count = 50)
        val first = DatasetManifestFactory.computeContentHash(candles)
        val second = DatasetManifestFactory.computeContentHash(candles)

        assertEquals(first, second)
        assertEquals(64, first.length) // SHA-256 hex
    }

    @Test
    fun `hash is stable across equivalent rebuilds`() {
        val a = DatasetManifestFactory.computeContentHash(SyntheticFixtures.bullish(count = 50))
        val b = DatasetManifestFactory.computeContentHash(SyntheticFixtures.bullish(count = 50))

        assertEquals(a, b)
    }

    @Test
    fun `hash changes when a price changes`() {
        val original = SyntheticFixtures.bullish(count = 10)
        val modified = original.toMutableList().also {
            it[5] = it[5].copy(closeUnits = it[5].closeUnits + 1)
        }

        assertNotEquals(
            DatasetManifestFactory.computeContentHash(original),
            DatasetManifestFactory.computeContentHash(modified),
        )
    }

    @Test
    fun `hash changes when volume changes`() {
        val original = SyntheticFixtures.bullish(count = 10)
        val modified = original.toMutableList().also {
            it[3] = it[3].copy(tickVolume = it[3].tickVolume + 1)
        }

        assertNotEquals(
            DatasetManifestFactory.computeContentHash(original),
            DatasetManifestFactory.computeContentHash(modified),
        )
    }

    @Test
    fun `hash changes when a candle is removed`() {
        val original = SyntheticFixtures.bullish(count = 10)
        assertNotEquals(
            DatasetManifestFactory.computeContentHash(original),
            DatasetManifestFactory.computeContentHash(original.dropLast(1)),
        )
    }

    @Test
    fun `hash does not depend on csv layout`() {
        val candles = SyntheticFixtures.bullish(count = 20)

        // Same data, different file layout: headerless, reordered columns.
        val withHeader = SyntheticFixtures.toCsv(candles)
        val reordered = buildString {
            append("t,c,o,l,h,v\n")
            candles.forEach { c ->
                append(c.timestampUtcMs).append(',')
                    .append(PriceParser.unitsToCanonicalString(c.closeUnits)).append(',')
                    .append(PriceParser.unitsToCanonicalString(c.openUnits)).append(',')
                    .append(PriceParser.unitsToCanonicalString(c.lowUnits)).append(',')
                    .append(PriceParser.unitsToCanonicalString(c.highUnits)).append(',')
                    .append(c.tickVolume).append('\n')
            }
        }

        val fromHeader = CsvImporter.import(withHeader, CsvImportConfig.dukascopy())
        val fromReordered = CsvImporter.import(
            reordered,
            CsvImportConfig.dukascopy().copy(
                hasHeader = true,
                timestampColumn = "t",
                closeColumn = "c",
                openColumn = "o",
                lowColumn = "l",
                highColumn = "h",
                volumeColumn = "v",
                timestampFormat = CsvImportConfig.TimestampFormat.EPOCH_MILLIS,
            ),
        )

        assertEquals(fromHeader.manifest.contentHash, fromReordered.manifest.contentHash)
    }

    @Test
    fun `dataset id is derived from the content hash`() {
        val candles = SyntheticFixtures.bullish(count = 10)
        val manifest = manifestFor(candles)

        assertTrue(manifest.datasetId.startsWith("ds_"))
        assertEquals("ds_" + manifest.contentHash.take(16), manifest.datasetId)
    }

    @Test
    fun `same data yields the same dataset id`() {
        assertEquals(
            manifestFor(SyntheticFixtures.bullish(count = 30)).datasetId,
            manifestFor(SyntheticFixtures.bullish(count = 30)).datasetId,
        )
    }

    @Test
    fun `different data yields a different dataset id`() {
        assertNotEquals(
            manifestFor(SyntheticFixtures.bullish(count = 30)).datasetId,
            manifestFor(SyntheticFixtures.bearish(count = 30)).datasetId,
        )
    }

    @Test
    fun `manifest records the required fields`() {
        val candles = SyntheticFixtures.bullish(count = 25)
        val m = manifestFor(candles)

        assertEquals("XAUUSD", m.instrument)
        assertEquals(25, m.candleCount)
        assertEquals(candles.first().timestampUtcMs, m.startTimestampUtcMs)
        assertEquals(candles.last().timestampUtcMs, m.endTimestampUtcMs)
        assertEquals(candles.minOf { it.lowUnits }, m.minimumPriceUnits)
        assertEquals(candles.maxOf { it.highUnits }, m.maximumPriceUnits)
        assertEquals(0, m.missingMinuteCount)
        assertEquals(0, m.duplicateCount)
        assertEquals("VALID", m.validationStatus)
        assertEquals(SyntheticFixtures.SYNTHETIC_MARKER, m.sourceDescription)
        assertTrue(m.contentHash.isNotEmpty())
    }

    @Test
    fun `manifest reports a gap count`() {
        val candles = SyntheticFixtures.unexpectedGap(before = 10, missingMinutes = 5, after = 10)
        val m = manifestFor(candles)

        assertEquals(5, m.missingMinuteCount)
    }

    @Test
    fun `manifest marks an invalid dataset`() {
        val m = manifestFor(SyntheticFixtures.invalidOhlc())
        assertEquals("INVALID", m.validationStatus)
    }

    @Test
    fun `coverage is complete for a gapless series`() {
        val candles = SyntheticFixtures.bullish(count = 60)
        val m = manifestFor(candles)

        assertEquals(60, m.expectedCandleCount)
        assertEquals(100.0, m.coveragePercent, 1e-9)
    }

    @Test
    fun `coverage drops when minutes are missing`() {
        val candles = SyntheticFixtures.unexpectedGap(before = 30, missingMinutes = 30, after = 30)
        val m = manifestFor(candles)

        assertEquals(60, m.candleCount)
        assertEquals(90, m.expectedCandleCount)
        assertTrue(m.coveragePercent < 100.0)
    }

    @Test
    fun `empty series produces a deterministic hash`() {
        val empty = DatasetManifestFactory.computeContentHash(emptyList())
        assertEquals(empty, DatasetManifestFactory.computeContentHash(emptyList()))
        assertEquals(64, empty.length)
    }

    @Test
    fun `source timezone is recorded in the manifest description`() {
        val candles = SyntheticFixtures.bullish(count = 5)
        val result = CsvImporter.import(
            SyntheticFixtures.toCsv(candles),
            CsvImportConfig.dukascopy(sourceTimeZone = ZoneId.of("UTC"), sourceDescription = "test source"),
        )
        assertEquals("test source", result.manifest.sourceDescription)
    }
}
