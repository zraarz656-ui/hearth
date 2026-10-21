package com.tradequest.app.core.trading

/**
 * A field change in a [TradingCommand], with the three cases spelled out.
 *
 * # Why not a nullable field
 *
 * `stopLossPips: Int? = null` cannot express "leave it alone" and "remove it"
 * at the same time. A user clearing their stop and a user not mentioning their
 * stop would look identical, and the engine would have to guess. This type makes
 * the difference explicit, so a clear is never mistaken for a no-op.
 */
sealed interface Modification<out T> {

    /** Leave the field as it is. */
    data object Keep : Modification<Nothing>

    /** Set the field to [value]. */
    data class Set<T>(val value: T) : Modification<T>

    /** Remove the field. */
    data object Clear : Modification<Nothing>

    companion object {
        /** Convenience for `Modification.Set(value)`. */
        fun <T> of(value: T): Modification<T> = Set(value)

        /** True when this is not [Keep]. */
        val Modification<*>.isPresent: Boolean
            get() = this !is Keep
    }
}
