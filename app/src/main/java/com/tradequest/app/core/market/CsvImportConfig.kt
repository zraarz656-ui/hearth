package com.tradequest.app.core.market

import java.time.ZoneId

/**
 * Declares how a CSV file is laid out and how its values should be read.
 *
 * The plan requires that timestamp format, timezone, column order, decimal
 * separator, and header layout are never assumed. Every one of them is
 * explicit here, and a caller must state the source timezone.
 */
data class CsvImportConfig(
    /** Column delimiter. */
    val delimiter: Char = ',',

    /** True when the first line is a header naming the columns. */
    val hasHeader: Boolean = true,

    /**
     * Timezone the file's timestamps are expressed in.
     *
     * Required, with no default: a timezone-less timestamp must never be
     * silently interpreted. See Step 1.4.
     */
    val sourceTimeZone: ZoneId,

    /**
     * Format of the timestamp column: [TimestampFormat.ISO_8601_UTC],
     * [TimestampFormat.EPOCH_MILLIS], or [TimestampFormat.EXPLICIT_PATTERN].
     */
    val timestampFormat: TimestampFormat = TimestampFormat.ISO_8601_UTC,

    /** Pattern used when [timestampFormat] is [TimestampFormat.EXPLICIT_PATTERN]. */
    val timestampPattern: String? = null,

    /** Header names, or positional indices when the file has no header. */
    val timestampColumn: String = "timestamp_utc",
    val openColumn: String = "open",
    val highColumn: String = "high",
    val lowColumn: String = "low",
    val closeColumn: String = "close",
    val volumeColumn: String = "tick_volume",

    /**
     * Optional spread column. Dukascopy CSVs carry it; it is not part of
     * [HistoricalCandle], so it is read only for validation.
     */
    val spreadColumn: String? = null,

    /** Decimal separator used for price fields. */
    val decimalSeparator: Char = '.',

    /** Thousands grouping separator, stripped before parsing. */
    val groupingSeparator: Char? = ',',

    /**
     * Field order used when [hasHeader] is false. Any column not listed is
     * treated as absent.
     */
    val positionalOrder: List<Field> = listOf(
        Field.TIMESTAMP, Field.OPEN, Field.HIGH, Field.LOW, Field.CLOSE, Field.VOLUME,
    ),

    /** Expected source precision, used to flag unexpected precision reduction. */
    val sourceDecimals: Int = PriceParser.SOURCE_DECIMALS,

    /**
     * True to drop flat, zero-volume padding rows that Dukascopy emits for
     * closed periods. These are fabricated, not observed.
     */
    val dropFlatEmptyRows: Boolean = true,

    /** Human-readable description recorded in the dataset manifest. */
    val sourceDescription: String = "Unspecified source",
) {
    enum class Field { TIMESTAMP, OPEN, HIGH, LOW, CLOSE, VOLUME, SPREAD }

    enum class TimestampFormat { ISO_8601_UTC, EPOCH_MILLIS, EXPLICIT_PATTERN }

    /**
     * The default layout for files produced by `tools/dukascopy/`.
     *
     * That tool writes UTC ISO-8601 timestamps with a trailing `Z`, a header
     * row, and prices at 3 decimals, so the timezone is genuinely known.
     */
    companion object {
        fun dukascopy(
            sourceTimeZone: ZoneId = ZoneId.of("UTC"),
            sourceDescription: String = "Dukascopy XAUUSD 1-minute bid candles",
        ) = CsvImportConfig(
            sourceTimeZone = sourceTimeZone,
            timestampFormat = TimestampFormat.ISO_8601_UTC,
            sourceDescription = sourceDescription,
        )
    }
}
