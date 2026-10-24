package com.tradequest.app.core.news

/**
 * What an economic release measures (Step 12.1).
 *
 * A closed set rather than a free string: the calendar groups and filters by
 * category, and a free string would let "Inflation", "inflation" and "CPI"
 * become three categories.
 *
 * [OTHER] absorbs anything unrecognised, so an unknown category is still
 * displayed and grouped rather than dropped.
 */
enum class NewsCategory {
    INTEREST_RATE,
    INFLATION,
    EMPLOYMENT,
    GDP,
    RETAIL_SALES,
    MANUFACTURING,
    HOUSING,
    CONSUMER_SENTIMENT,
    ENERGY,
    TRADE,
    OTHER,
    ;

    /** A human-readable label for the calendar. */
    val label: String
        get() = when (this) {
            INTEREST_RATE -> "Interest rate"
            INFLATION -> "Inflation"
            EMPLOYMENT -> "Employment"
            GDP -> "GDP"
            RETAIL_SALES -> "Retail sales"
            MANUFACTURING -> "Manufacturing"
            HOUSING -> "Housing"
            CONSUMER_SENTIMENT -> "Consumer sentiment"
            ENERGY -> "Energy"
            TRADE -> "Trade"
            OTHER -> "Other"
        }

    companion object {
        /** Parses a stored category, tolerating case, whitespace and `_`/`-`/space. */
        fun fromStored(raw: String?): NewsCategory =
            when (raw?.trim()?.uppercase()?.replace('-', '_')?.replace(' ', '_')) {
                "INTEREST_RATE", "RATE", "RATES", "FOMC", "CENTRAL_BANK" -> INTEREST_RATE
                "INFLATION", "CPI", "PPI" -> INFLATION
                "EMPLOYMENT", "JOBS", "NFP", "UNEMPLOYMENT" -> EMPLOYMENT
                "GDP" -> GDP
                "RETAIL_SALES", "RETAIL" -> RETAIL_SALES
                "MANUFACTURING", "PMI" -> MANUFACTURING
                "HOUSING", "HOME_SALES" -> HOUSING
                "CONSUMER_SENTIMENT", "SENTIMENT" -> CONSUMER_SENTIMENT
                "ENERGY", "OIL", "CRUDE" -> ENERGY
                "TRADE", "BALANCE_OF_TRADE" -> TRADE
                else -> OTHER
            }
    }
}
