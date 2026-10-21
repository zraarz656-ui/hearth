package com.tradequest.app.debug

import android.content.Context
import com.tradequest.app.core.database.market.ImportOutcome
import com.tradequest.app.core.database.market.ImportRequest
import com.tradequest.app.core.database.market.MarketDatabase
import com.tradequest.app.core.database.market.RoomMarketRepository
import com.tradequest.app.core.market.CandleValidator
import com.tradequest.app.core.market.CsvImportConfig
import com.tradequest.app.core.market.CsvImporter
import com.tradequest.app.core.market.DatasetClosure
import com.tradequest.app.core.market.DatasetManifestFactory
import com.tradequest.app.core.market.MarketClosureDetector
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Seeds a real dataset into `market.db` on a debug build, when the library is
 * empty.
 *
 * # Why this exists
 *
 * Phase 1 can build a validated CSV, but nothing can put one into the app:
 * Step 15.1 is the only import entry point and Phase 15 is last on purpose, so
 * until it lands the Market Library is correctly empty and there is nothing to
 * chart. That is fine for the test suite, which uses synthetic fixtures, but it
 * means an installed debug build shows two empty screens. This fills that gap so
 * Phase 8's rendering can actually be exercised on a device.
 *
 * # This is not Phase 15
 *
 * It is debug-only instrumentation, the on-device equivalent of the fixtures in
 * `app/src/test`. It lives in `app/src/debug/`, which the build never compiles
 * into a release variant, so no release build contains it. It must be deleted,
 * not extended, once Phase 15 provides the real import UI.
 *
 * # Why it reuses the production pipeline
 *
 * The CSV goes through the same [CsvImporter], [CandleValidator],
 * [MarketClosureDetector] and [DatasetManifestFactory] the real import will use,
 * and is written with the same `storeImport` call. A hand-built manifest could
 * seed a dataset that the real importer would have rejected, which would make
 * any bug found on device unreproducible.
 */
internal object DebugDatasetSeeder {

    /** Asset path of the bundled CSV. Kept next to its generated manifest for provenance. */
    private const val CSV_ASSET = "datasets/XAUUSD_2024-01-02_2024-01-22.csv"

    private const val DISPLAY_NAME = "XAUUSD Jan 2024 (debug seed)"

    /**
     * Seeds the bundled dataset if the library is empty. Never overwrites.
     *
     * Runs off the main thread: parsing 20k candles and writing them is not
     * something to do during `Application.onCreate`. `storeImport` is itself
     * transactional and idempotent by content hash, so a second launch on a
     * populated library is a no-op even if this races.
     */
    fun seedIfEmpty(context: Context, database: MarketDatabase) {
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch { seed(context, database) }
    }

    /**
     * The seeding work, callable directly so it can be tested.
     *
     * Returns a one-line summary of what happened. Split out of [seedIfEmpty] so
     * a test can await the result instead of racing a detached coroutine.
     */
    suspend fun seed(context: Context, database: MarketDatabase): String {
        val repository = RoomMarketRepository(database)
        return try {
            if (repository.countDatasets() > 0) {
                DebugDiagnostics.log("seed", "market.db already has datasets; not seeding")
                return "skipped: library not empty"
            }

            val csv = context.assets.open(CSV_ASSET)
                .bufferedReader()
                .use { it.readText() }

            val config = CsvImportConfig.dukascopy(
                sourceTimeZone = ZoneId.of("UTC"),
                sourceDescription = "debug seed from app/src/debug/assets/$CSV_ASSET",
            )
            val result = CsvImporter.import(csv, config)

            if (!result.importedSuccessfully) {
                val message = "FAILED: CSV did not validate: status=${result.validation.status} " +
                    "errors=${result.validation.errors.size} " +
                    "first=${result.validation.errors.firstOrNull()}"
                DebugDiagnostics.log("seed", message)
                return message
            }

            val validation = result.validation
            val closure = MarketClosureDetector.classify(result.candles, validation)
            val manifest = DatasetManifestFactory.build(
                candles = result.candles,
                instrument = "XAUUSD",
                validation = validation,
                closure = closure,
                sourceDescription = config.sourceDescription,
                duplicateCount = result.validation.duplicateTimestamps.size,
            )

            val closures = closure.days.mapNotNull { day ->
                day.reason?.let { reason ->
                    val start = day.date.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
                    DatasetClosure(
                        datasetId = manifest.datasetId,
                        startUtcMs = start,
                        endUtcMs = start + DAY_MS,
                        reason = reason.name,
                        missingMinuteCount = day.missingMinuteCount,
                    )
                }
            }

            val outcome = repository.storeImport(
                ImportRequest(
                    manifest = manifest,
                    candles = result.candles,
                    displayName = DISPLAY_NAME,
                    closures = closures,
                    validationStatus = validation.status,
                    errorCount = validation.errors.size,
                    warningCount = validation.warnings.size,
                    coveragePercent = manifest.coveragePercent,
                    importedAtUtcMs = System.currentTimeMillis(),
                ),
            )

            val message = when (outcome) {
                is ImportOutcome.Stored ->
                    "OK datasetId=${manifest.datasetId} candles=${manifest.candleCount} " +
                        "status=${validation.status} closures=${closures.size} " +
                        "coverage=${manifest.coveragePercent}"
                is ImportOutcome.AlreadyPresent -> "already present: ${manifest.datasetId}"
                else -> "WARN not stored: $outcome"
            }
            DebugDiagnostics.log("seed", message)
            message
        } catch (error: Exception) {
            // A failed seed must never crash the app; the empty state is a
            // valid fallback and the log carries the reason.
            val message = "EXCEPTION ${error::class.java.name}: ${error.message}"
            DebugDiagnostics.log("seed", message)
            message
        }
    }

    private const val DAY_MS = 86_400_000L
}
