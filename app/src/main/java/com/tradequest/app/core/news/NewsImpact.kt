package com.tradequest.app.core.news

/**
 * How much an economic release is expected to move the market (Step 12.1).
 *
 * Ordered by severity, so `HIGH > MEDIUM > LOW` holds and a "highest impact in
 * this window" query can compare ordinals rather than test for each value.
 *
 * [UNKNOWN] exists for stored rows whose impact string this build does not
 * recognise. Folding those into [LOW] would be a silent lie: an unrecognised
 * value could be a high-impact release, and the calendar's colour flag exists
 * precisely so the user is not surprised by one. An event with [UNKNOWN] is
 * shown plainly, with no flag, which is the honest rendering of "we do not
 * know".
 */
enum class NewsImpact {
    HIGH,
    MEDIUM,
    LOW,
    UNKNOWN,
    ;

    /** True when the calendar draws a flag for this impact (Steps 12.3, 12.4). */
    val isFlagged: Boolean get() = this == HIGH || this == MEDIUM

    companion object {
        /**
         * Parses a stored impact, tolerating case and surrounding whitespace.
         *
         * Never throws. A dataset is external input, and one malformed impact
         * string must not take the whole calendar down with it.
         */
        fun fromStored(raw: String?): NewsImpact =
            when (raw?.trim()?.uppercase()) {
                "HIGH", "H" -> HIGH
                "MEDIUM", "MED", "M" -> MEDIUM
                "LOW", "L" -> LOW
                else -> UNKNOWN
            }
    }
}
