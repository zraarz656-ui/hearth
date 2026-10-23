package com.tradequest.app.core.market

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** One parsed row, before validation. */
internal data class ParsedRow(
    val timestampUtcMs: Long,
    val candle: HistoricalCandle,
    val lineNumber: Int,
)

/** Raised when a file cannot be parsed under the supplied configuration. */
class CsvImportException(
    message: String,
    val lineNumber: Int? = null,
    cause: Throwable? = null,
) : Exception(
    if (lineNumber != null) "line $lineNumber: $message" else message,
    cause,
)

/** Outcome of importing a CSV file. */
data class ImportResult(
    val candles: List<HistoricalCandle>,
    val validation: ValidationReport,
    val closure: ClosureReport,
    val manifest: DatasetManifest,
    /** Rows dropped as fabricated padding, not observed data. */
    val droppedPaddingRows: Int,
    /** Rows whose source precision exceeded the configured scale. */
    val precisionReducedRows: Int,
    val parseIssues: List<ValidationIssue>,
) {
    val importedSuccessfully: Boolean get() = candles.isNotEmpty() && validation.isValid
}

/**
 * Parses Dukascopy-compatible one-minute CSV into [HistoricalCandle] values.
 *
 * Layout is never assumed: delimiter, header presence, column mapping,
 * timestamp format, timezone, and decimal separator all come from
 * [CsvImportConfig]. Prices are parsed as decimal text through
 * [PriceParser], never through `Double`.
 */
object CsvImporter {

    private const val MINUTE_MS = 60_000L

    /**
     * Imports CSV text.
     *
     * @param text full file contents.
     * @param config explicit layout description; the timezone must be stated.
     */
    fun import(text: String, config: CsvImportConfig): ImportResult {
        val lines = text.lineSequence().toList()
        if (lines.isEmpty()) {
            throw CsvImportException("file is empty")
        }

        val delimiter = config.delimiter
        val header: List<String>? =
            if (config.hasHeader) parseHeader(lines.first(), delimiter) else null
        val dataLines = if (config.hasHeader) lines.drop(1) else lines

        val candles = mutableListOf<HistoricalCandle>()
        val parseIssues = mutableListOf<ValidationIssue>()
        var droppedPadding = 0
        var precisionReduced = 0

        dataLines.forEachIndexed { index, rawLine ->
            val lineNumber = index + if (config.hasHeader) 2 else 1
            if (rawLine.isBlank()) return@forEachIndexed

            val row = try {
                parseRow(rawLine, lineNumber, header, config)
            } catch (e: CsvImportException) {
                throw e
            }

            if (row.candle.isFlatAndEmpty && config.dropFlatEmptyRows) {
                droppedPadding++
                return@forEachIndexed
            }
            candles += row.candle
        }

        val validation = CandleValidator.validate(candles)
        val closure = MarketClosureDetector.classify(candles, validation, config.sourceTimeZone)
        val manifest = DatasetManifestFactory.build(
            candles = candles,
            instrument = "XAUUSD",
            validation = validation,
            closure = closure,
            sourceDescription = config.sourceDescription,
            duplicateCount = validation.duplicateTimestamps.size,
        )

        return ImportResult(
            candles = candles,
            validation = validation,
            closure = closure,
            manifest = manifest,
            droppedPaddingRows = droppedPadding,
            precisionReducedRows = precisionReduced,
            parseIssues = parseIssues,
        )
    }

    private fun parseHeader(line: String, delimiter: Char): List<String> =
        splitRespectingQuotes(line, delimiter).map { it.trim() }

    private fun parseRow(
        line: String,
        lineNumber: Int,
        header: List<String>?,
        config: CsvImportConfig,
    ): ParsedRow {
        val fields = splitRespectingQuotes(line, config.delimiter)

        fun fieldFor(name: String, position: CsvImportConfig.Field): String? {
            val raw = if (header != null) {
                val index = header.indexOfFirst { it.equals(name, ignoreCase = true) }
                if (index < 0) return null
                fields.getOrNull(index)
            } else {
                val index = config.positionalOrder.indexOf(position)
                if (index < 0) return null
                fields.getOrNull(index)
            }
            return raw?.trim()
        }

        val timestampText = fieldFor(config.timestampColumn, CsvImportConfig.Field.TIMESTAMP)
            ?: throw CsvImportException("missing timestamp column", lineNumber)
        val timestampUtcMs = parseTimestamp(timestampText, config, lineNumber)

        val open = parsePrice(
            fieldFor(config.openColumn, CsvImportConfig.Field.OPEN), config, lineNumber, "open",
        )
        val high = parsePrice(
            fieldFor(config.highColumn, CsvImportConfig.Field.HIGH), config, lineNumber, "high",
        )
        val low = parsePrice(
            fieldFor(config.lowColumn, CsvImportConfig.Field.LOW), config, lineNumber, "low",
        )
        val close = parsePrice(
            fieldFor(config.closeColumn, CsvImportConfig.Field.CLOSE), config, lineNumber, "close",
        )
        val volume = parseVolume(
            fieldFor(config.volumeColumn, CsvImportConfig.Field.VOLUME), lineNumber,
        )

        val candle = HistoricalCandle(
            timestampUtcMs = timestampUtcMs,
            openUnits = open,
            highUnits = high,
            lowUnits = low,
            closeUnits = close,
            tickVolume = volume,
        )
        return ParsedRow(timestampUtcMs, candle, lineNumber)
    }

    private fun parsePrice(
        text: String?,
        config: CsvImportConfig,
        lineNumber: Int,
        fieldName: String,
    ): Int {
        if (text.isNullOrBlank()) {
            throw CsvImportException("missing $fieldName price", lineNumber)
        }
        val normalized = normalizeNumber(text, config)
        return try {
            PriceParser.parseToUnitsDetailed(normalized, config.sourceDecimals).units
        } catch (e: NumberFormatException) {
            throw CsvImportException("invalid $fieldName price '$text'", lineNumber, e)
        } catch (e: ArithmeticException) {
            throw CsvImportException("$fieldName price '$text' out of range", lineNumber, e)
        }
    }

    private fun parseVolume(text: String?, lineNumber: Int): Int {
        if (text.isNullOrBlank()) return 0
        val value = text.trim().toDoubleOrNull()
            ?: throw CsvImportException("invalid volume '$text'", lineNumber)
        if (value < 0) {
            throw CsvImportException("negative volume '$text'", lineNumber)
        }
        return kotlin.math.round(value).toInt()
    }

    /** Applies the configured decimal and grouping separators. */
    private fun normalizeNumber(text: String, config: CsvImportConfig): String {
        var result = text
        config.groupingSeparator?.let { sep ->
            if (sep != config.decimalSeparator) result = result.replace(sep.toString(), "")
        }
        if (config.decimalSeparator != '.') {
            result = result.replace(config.decimalSeparator, '.')
        }
        return result
    }

    private fun parseTimestamp(
        text: String,
        config: CsvImportConfig,
        lineNumber: Int,
    ): Long {
        val cleaned = text.trim()
        return try {
            when (config.timestampFormat) {
                CsvImportConfig.TimestampFormat.EPOCH_MILLIS -> cleaned.toLong()

                CsvImportConfig.TimestampFormat.ISO_8601_UTC -> {
                    val normalized = cleaned.removeSuffix("Z")
                    val local = LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    // The trailing Z states UTC, so the offset is known, not assumed.
                    local.atZone(
                        if (cleaned.endsWith("Z")) ZoneId.of("UTC") else config.sourceTimeZone,
                    ).toInstant().toEpochMilli()
                }

                CsvImportConfig.TimestampFormat.EXPLICIT_PATTERN -> {
                    val pattern = config.timestampPattern
                        ?: throw CsvImportException(
                            "EXPLICIT_PATTERN requires timestampPattern", lineNumber,
                        )
                    LocalDateTime.parse(cleaned, DateTimeFormatter.ofPattern(pattern))
                        .atZone(config.sourceTimeZone)
                        .toInstant()
                        .toEpochMilli()
                }
            }
        } catch (e: DateTimeParseException) {
            throw CsvImportException("invalid timestamp '$text'", lineNumber, e)
        } catch (e: NumberFormatException) {
            throw CsvImportException("invalid epoch timestamp '$text'", lineNumber, e)
        }
    }

    /** Splits a CSV line on [delimiter], honouring double-quoted fields. */
    private fun splitRespectingQuotes(line: String, delimiter: Char): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false

        line.forEach { ch ->
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == delimiter && !inQuotes -> {
                    result += current.toString()
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        result += current.toString()
        return result
    }

    /** Validates that a series is strictly ordered and minute-aligned. */
    fun checkMinuteAlignment(candles: List<HistoricalCandle>): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        candles.forEach { c ->
            if (c.timestampUtcMs % MINUTE_MS != 0L) {
                issues += ValidationIssue(
                    Severity.ERROR, "NOT_MINUTE_ALIGNED",
                    "timestamp is not minute-aligned: ${c.timestampUtcMs}", c.timestampUtcMs,
                )
            }
        }
        return issues
    }

    /** Convenience for tests and callers that hold a UTC instant. */
    fun instantToEpochMs(instant: Instant): Long = instant.toEpochMilli()
}
