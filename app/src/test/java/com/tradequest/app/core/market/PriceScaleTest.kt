package com.tradequest.app.core.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Price-scale and hashing tests.
 *
 * The four `adr0001 acceptance` tests are the acceptance checks recorded in
 * `docs/adr-0001-price-scale.md`. They must all hold for the thousandths
 * decision to be sound.
 */
class PriceScaleTest {

    // ---------- ADR-0001 acceptance checks ----------

    /**
     * 1. Price units fit in Int32 with wide headroom.
     *
     * Gold has traded as high as ~2075 USD in the covered era; 5000 USD is a
     * generous bound. Thousandths of that must stay far below Int32_MAX.
     */
    @Test
    fun `adr0001 acceptance price units fit in Int32`() {
        val generousMaxUsd = 5_000
        val maxUnits = generousMaxUsd * PriceScale.UNITS_PER_USD

        assertTrue(
            "price units $maxUnits must fit in Int32",
            maxUnits < Int.MAX_VALUE,
        )
        // At least 400x headroom, so a price cannot realistically overflow.
        assertTrue(maxUnits * 400L < Int.MAX_VALUE)
    }

    /**
     * 2. A 0.001 USD step is representable.
     *
     * This is the whole point of the decision. Under cents, the step would
     * round away.
     */
    @Test
    fun `adr0001 acceptance thousandth step is representable`() {
        val a = PriceParser.parseToUnits("2047.204")
        val b = PriceParser.parseToUnits("2047.205")

        assertEquals(1, b - a)
        assertEquals(2_047_204, a)
    }

    /**
     * 3. Conversion to micro-dollars is exact.
     *
     * Thousandths (1e-3) to micro-dollars (1e-6) is a multiply by 1000, a
     * power of ten, so no rounding may occur. If this test fails, a scale is
     * wrong somewhere.
     */
    @Test
    fun `adr0001 acceptance conversion to micro dollars is exact`() {
        val units = PriceParser.parseToUnits("2047.204")
        val microDollars = units.toLong() * 1000L

        assertEquals(2_047_204_000L, microDollars)
        assertEquals(
            BigDecimal("2047.204000"),
            BigDecimal(microDollars).movePointLeft(6).setScale(6),
        )
    }

    /**
     * 4. Money arithmetic needs Long, and this is independent of the price
     * scale: the overflow lives in the micro-dollar money scale.
     *
     * A 30 USD move at 1 lot overflows Int32 whether prices are stored as
     * cents or thousandths. Cents does not avoid the overflow, it only hides
     * it about 100x further out.
     */
    @Test
    fun `adr0001 acceptance money arithmetic requires Long regardless of scale`() {
        // XAUUSD: 1 lot = 100 troy ounces, stored as 100 hundredths of a lot.
        // PnL in micro-dollars = moveUsd * lotUnits * 1e6, since 100 hundredths
        // of a lot is 100 ounces. The 1e6 money scale, not the price scale, is
        // what drives the magnitude.
        val lotUnits = 100
        val moveUsd = 30L

        val microDollars = moveUsd * lotUnits * 1_000_000L
        assertEquals(3_000_000_000L, microDollars)
        assertTrue(microDollars > Int.MAX_VALUE.toLong())

        // The Int32 ceiling is reached after roughly a 21 USD move at 1 lot.
        val maxSafeUsd = Int.MAX_VALUE.toLong() / (lotUnits * 1_000_000L)
        assertEquals(21L, maxSafeUsd)

        // Gold routinely moves more than that in a day, so Long is mandatory.
        assertTrue(40L > maxSafeUsd)
    }

    // ---------- Parsing exactness ----------

    @Test
    fun `parses three decimal prices exactly`() {
        assertEquals(2_047_204, PriceParser.parseToUnits("2047.204"))
        assertEquals(2_047_525, PriceParser.parseToUnits("2047.525"))
        assertEquals(2_000_000, PriceParser.parseToUnits("2000.000"))
        assertEquals(2_000_001, PriceParser.parseToUnits("2000.001"))
    }

    @Test
    fun `parses fewer decimals exactly`() {
        assertEquals(2_047_000, PriceParser.parseToUnits("2047"))
        assertEquals(2_047_500, PriceParser.parseToUnits("2047.5"))
        assertEquals(2_047_520, PriceParser.parseToUnits("2047.52"))
    }

    @Test
    fun `does not lose precision through double`() {
        // 0.1 + 0.2 style error: the classic binary float trap.
        val text = "2047.1"
        val viaBigDecimal = PriceParser.parseToUnits(text)
        val viaDouble = (text.toDouble() * 1000).toInt()

        assertEquals(2_047_100, viaBigDecimal)
        assertEquals(viaBigDecimal, viaDouble)

        // A value where naive double scaling would drift.
        val tricky = "0.07"
        assertEquals(70, PriceParser.parseToUnits(tricky))
    }

    @Test
    fun `rounds half up away from zero when source precision is finer`() {
        val rounded = PriceParser.parseToUnitsDetailed("2047.2045")
        assertTrue(rounded.precisionReduced)
        assertEquals(2_047_205, rounded.units)

        val negative = PriceParser.parseToUnitsDetailed("-1.0005")
        assertTrue(negative.precisionReduced)
        assertEquals(-1_001, negative.units)
    }

    @Test
    fun `does not flag reduction for exact three decimal input`() {
        val parsed = PriceParser.parseToUnitsDetailed("2047.204")
        assertFalse(parsed.precisionReduced)
        assertEquals(3, parsed.decimals)
    }

    @Test
    fun `canonical string round trips`() {
        val units = 2_047_204
        val text = PriceParser.unitsToCanonicalString(units)
        assertEquals("2047.204", text)
        assertEquals(units, PriceParser.parseToUnits(text))
    }

    @Test
    fun `canonical string keeps trailing zeros`() {
        assertEquals("2000.000", PriceParser.unitsToCanonicalString(2_000_000))
        assertEquals("2047.500", PriceParser.unitsToCanonicalString(2_047_500))
    }

    @Test(expected = NumberFormatException::class)
    fun `rejects non numeric price text`() {
        PriceParser.parseToUnits("abc")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects empty price text`() {
        PriceParser.parseToUnits("   ")
    }

    // ---------- Formatting and pips ----------

    @Test
    fun `formats price units as three decimals`() {
        assertEquals("2047.204", PriceScale.format(2_047_204))
        assertEquals("2047.000", PriceScale.format(2_047_000))
        assertEquals("0.001", PriceScale.format(1))
        assertEquals("-2047.204", PriceScale.format(-2_047_204))
    }

    @Test
    fun `converts pips to price units`() {
        assertEquals(100, PriceScale.UNITS_PER_PIP)
        assertEquals(500, PriceScale.pipsToUnits(5))
        assertEquals(5, PriceScale.unitsToPips(500))
    }

    @Test
    fun `usd and units convert both ways`() {
        assertEquals(2_047_204, PriceScale.usdToUnits(2047.204))
        assertEquals(2047.204, PriceScale.unitsToUsd(2_047_204), 1e-9)
    }

    @Test
    fun `rounds usd half up`() {
        assertEquals(1, PriceScale.usdToUnits(0.0005))
        assertEquals(-1, PriceScale.usdToUnits(-0.0005))
    }
}
