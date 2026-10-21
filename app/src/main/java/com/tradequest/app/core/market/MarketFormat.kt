package com.tradequest.app.core.market

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Display formatting for the Market Library.
 *
 * Kept in `core.market` so both the ViewModel and any future screen share one
 * definition. All money here is a `Long` of micro-dollars and all prices are
 * `Int` thousandths; nothing is converted through `Double` for display of
 * prices, because [PriceScale.format] is exact.
 */
object MarketFormat {

    private val dateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.of("UTC"))

    private val dateTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM yyyy HH:mm 'UTC'").withZone(ZoneId.of("UTC"))

    /** Formats a date, in UTC. */
    fun date(utcMs: Long): String = dateFormatter.format(Instant.ofEpochMilli(utcMs))

    /** Formats a date and time, in UTC. */
    fun dateTime(utcMs: Long): String = dateTimeFormatter.format(Instant.ofEpochMilli(utcMs))

    /** Formats a covered span such as "3 months 4 days". */
    fun span(days: Long): String = when {
        days <= 0L -> "under a day"
        days == 1L -> "1 day"
        days < 45L -> "$days days"
        days < 365L -> {
            val months = days / 30
            val remainder = days % 30
            if (remainder == 0L) "$months months" else "$months months $remainder days"
        }
        else -> {
            val years = days / 365
            val remainder = days % 365
            if (remainder == 0L) "$years years" else "$years years $remainder days"
        }
    }

    /** Formats a candle count with a thousands separator. */
    fun count(value: Int): String = "%,d".format(value)

    /**
     * Formats a byte count as KiB or MiB.
     *
     * Returns a labelled estimate because the underlying measurement cannot
     * attribute bytes to a single dataset exactly.
     */
    fun estimatedBytes(bytes: Long?): String {
        if (bytes == null) return "size unavailable"
        if (bytes < 1024) return "$bytes B"
        val kib = bytes / 1024.0
        if (kib < 1024) return "~%.0f KiB".format(kib)
        return "~%.1f MiB".format(kib / 1024.0)
    }
}
