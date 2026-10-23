package com.tradequest.app.feature.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 10 order-ticket parsing (Step 10.2).
 *
 * # What is being verified
 *
 * That a typed lot size and a typed price become the integers the engine takes, and
 * that a partial or nonsense entry is rejected rather than silently becoming zero.
 * The second matters most: a field that turns "0." into "0" would submit a
 * zero-lot order the trader did not ask for.
 */
class OrderTicketParsingTest {

    // -------------------------------------------------------------- lot sizes

    @Test
    fun `a lot size round-trips through its display format`() {
        assertEquals("0.20", formatLots(20))
        assertEquals("1.00", formatLots(100))
        assertEquals("0.05", formatLots(5))
        assertEquals("0.00", formatLots(0))
    }

    @Test
    fun `a typed lot size becomes hundredths`() {
        assertEquals(20, parseLots("0.20"))
        assertEquals(100, parseLots("1"))
        assertEquals(5, parseLots("0.05"))
    }

    @Test
    fun `a lot size with a comma decimal separator parses`() {
        // A numeric keypad on a comma-locale produces one, so it must not be a
        // dead keystroke.
        assertEquals(20, parseLots("0,20"))
    }

    @Test
    fun `a half-typed lot size is rejected rather than read as zero`() {
        // Null means "ignore this keystroke", so the field keeps what it had.
        assertNull(parseLots(""))
        assertNull(parseLots("."))
        assertNull(parseLots("abc"))
    }

    @Test
    fun `a negative lot size is rejected`() {
        assertNull(parseLots("-1"))
    }

    // ----------------------------------------------------------------- prices

    @Test
    fun `a typed price becomes integer units`() {
        assertEquals(2_000_000, parsePrice("2000"))
        assertEquals(2_047_204, parsePrice("2047.204"))
    }

    @Test
    fun `a price with a thousands separator parses`() {
        assertEquals(2_000_000, parsePrice("2,000"))
    }

    @Test
    fun `a nonsense price is rejected rather than read as zero`() {
        assertNull(parsePrice(""))
        assertNull(parsePrice("abc"))
    }
}
