package com.tradequest.app.core.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 10 overlay geometry: the lines a chart draws and the pip distances a drag
 * produces (Steps 10.3, 10.4).
 *
 * # Why these are asserted here and not through a Compose test
 *
 * The project has no Compose UI-test infrastructure, and the plan's Step 10.10
 * asks for line dragging to be verified. The part of dragging that can be *wrong*
 * — which side a stop belongs on, what pip distance a dragged price means, whether
 * a line exists at all — is arithmetic, and it lives in [TradeOverlay]. A Compose
 * test could only confirm that a canvas was drawn, which is not the property at
 * risk. So the arithmetic is tested directly and the composable is left thin.
 *
 * Prices are integer thousandths of a USD; one pip is 0.10 USD, so 100 units.
 */
class TradeOverlayTest {

    private val contract = ContractSpec.XAUUSD

    private fun position(
        side: OrderSide,
        entry: Int,
        stop: Int? = null,
        target: Int? = null,
        volume: Int = 10,
    ): Position = Position(
        positionId = "p1",
        seasonId = "s1",
        openedAtSimUtcMs = 0L,
        side = side,
        volumeHundredths = volume,
        openPriceUnits = entry,
        stopLossPips = null,
        takeProfitPips = null,
        trailingStop = null,
        breakeven = null,
        status = PositionStatus.OPEN,
        closedAtSimUtcMs = null,
        closePriceUnits = null,
        activeStopUnits = stop,
        targetUnits = target,
        favorableExtremeUnits = entry,
        breakevenApplied = false,
        entryCommissionMicros = 0L,
        realizedPnlMicros = 0L,
        realizedVolumeHundredths = 0,
    )

    private fun order(
        side: OrderSide,
        price: Int?,
        stopPips: Int? = null,
        targetPips: Int? = null,
        type: OrderType = OrderType.LIMIT,
    ): TradeOrder = TradeOrder(
        orderId = "o1",
        seasonId = "s1",
        placedAtSimUtcMs = 0L,
        type = type,
        side = side,
        volumeHundredths = 10,
        requestedPriceUnits = price,
        stopLossPips = stopPips,
        takeProfitPips = targetPips,
        trailingStop = null,
        breakeven = null,
        status = OrderStatus.PENDING,
        resolvedAtSimUtcMs = null,
        rejectionReason = null,
    )

    // ---------------------------------------------------- Step 10.3: lines exist

    @Test
    fun `an open position with a stop and target draws three lines`() {
        val overlay = TradeOverlay.of(
            position(side = OrderSide.BUY, entry = 2_000_000, stop = 1_995_000, target = 2_010_000),
        )
        assertEquals(3, overlay.lines.size)
        assertEquals(2_000_000, overlay.entry?.priceUnits)
        assertEquals(1_995_000, overlay.stop?.priceUnits)
        assertEquals(2_010_000, overlay.target?.priceUnits)
    }

    @Test
    fun `an open position with no stop or target draws only the entry`() {
        val overlay = TradeOverlay.of(position(side = OrderSide.BUY, entry = 2_000_000))
        assertEquals(1, overlay.lines.size)
        assertNull(overlay.stop)
        assertNull(overlay.target)
    }

    @Test
    fun `a filled position's entry is not draggable but its stop and target are`() {
        val overlay = TradeOverlay.of(
            position(side = OrderSide.BUY, entry = 2_000_000, stop = 1_995_000, target = 2_010_000),
        )
        // The fill is committed history; letting a drag rewrite it would change
        // what the trader actually did.
        assertFalse(overlay.entry!!.isDraggable)
        assertTrue(overlay.stop!!.isDraggable)
        assertTrue(overlay.target!!.isDraggable)
    }

    @Test
    fun `a pending order's entry is draggable`() {
        val overlay = TradeOverlay.of(order(side = OrderSide.BUY, price = 2_000_000), contract)!!
        assertTrue(overlay.entry!!.isDraggable)
        assertTrue(overlay.isPending)
    }

    @Test
    fun `a market order has no overlay because it has no entry level`() {
        // A market order has no requested price, so there is nothing to draw an
        // entry line at. Returning null is the honest answer.
        val overlay = TradeOverlay.of(
            order(side = OrderSide.BUY, price = null, type = OrderType.MARKET),
            contract,
        )
        assertNull(overlay)
    }

    // ------------------------------------------- Step 10.4: stop/target geometry

    @Test
    fun `a buy's stop sits below entry and its target above`() {
        val stop = TradeOverlay.stopPriceFor(OrderSide.BUY, 2_000_000, 50, contract)
        val target = TradeOverlay.targetPriceFor(OrderSide.BUY, 2_000_000, 50, contract)
        assertEquals(2_000_000 - 5_000, stop)
        assertEquals(2_000_000 + 5_000, target)
    }

    @Test
    fun `a sell's stop sits above entry and its target below`() {
        // The mirror of the buy case. Getting this backwards would put the stop on
        // the profitable side, which the engine would fill instantly.
        val stop = TradeOverlay.stopPriceFor(OrderSide.SELL, 2_000_000, 50, contract)
        val target = TradeOverlay.targetPriceFor(OrderSide.SELL, 2_000_000, 50, contract)
        assertEquals(2_000_000 + 5_000, stop)
        assertEquals(2_000_000 - 5_000, target)
    }

    @Test
    fun `a dragged stop yields the pip distance for its side`() {
        // A buy dragged down 50 pips: adverse to the position, so positive.
        assertEquals(
            50,
            TradeOverlay.pipDistanceFor(
                OrderSide.BUY, 2_000_000, 1_995_000, contract, TradeDirection.ADVERSE,
            ),
        )
        // A sell dragged up 50 pips: also adverse.
        assertEquals(
            50,
            TradeOverlay.pipDistanceFor(
                OrderSide.SELL, 2_000_000, 2_005_000, contract, TradeDirection.ADVERSE,
            ),
        )
    }

    @Test
    fun `a dragged target yields the pip distance for its side`() {
        // The mirror of the stop: a buy's target is above entry and a sell's below,
        // both favourable. This is the case a single-convention helper got wrong.
        assertEquals(
            100,
            TradeOverlay.pipDistanceFor(
                OrderSide.BUY, 2_000_000, 2_010_000, contract, TradeDirection.FAVOURABLE,
            ),
        )
        assertEquals(
            100,
            TradeOverlay.pipDistanceFor(
                OrderSide.SELL, 2_000_000, 1_990_000, contract, TradeDirection.FAVOURABLE,
            ),
        )
    }

    @Test
    fun `a level on the wrong side of entry is negative in the requested direction`() {
        // A buy's stop above entry is adverse-positive? No: it is on the favourable
        // side, so as an adverse distance it is negative and must be rejected rather
        // than read as a valid 50-pip stop.
        val pips = TradeOverlay.pipDistanceFor(
            OrderSide.BUY, 2_000_000, 2_005_000, contract, TradeDirection.ADVERSE,
        )
        assertTrue("expected a negative distance, got $pips", pips < 0)
    }

    @Test
    fun `a dragged level rounds to the nearest pip`() {
        // 149 units is 1.49 pips, which rounds to 1; 151 is 1.51, which rounds to 2.
        assertEquals(
            1,
            TradeOverlay.pipDistanceFor(
                OrderSide.BUY, 2_000_000, 1_999_851, contract, TradeDirection.ADVERSE,
            ),
        )
        assertEquals(
            2,
            TradeOverlay.pipDistanceFor(
                OrderSide.BUY, 2_000_000, 1_999_849, contract, TradeDirection.ADVERSE,
            ),
        )
    }

    // ------------------------------------------------------ Step 10.3: live PnL

    @Test
    fun `a buy's unrealised PnL rises as price rises`() {
        // 0.10 lots = 10 units of hundredths. A 1 USD move on 0.10 lots of 100oz
        // gold is 0.10 * 100 * 1 = $10 = 10,000,000 micros.
        val pnl = TradeOverlay.unrealizedPnlMicros(
            position = position(side = OrderSide.BUY, entry = 2_000_000),
            markPriceUnits = 2_001_000,
            contract = contract,
        )
        assertEquals(10_000_000L, pnl)
    }

    @Test
    fun `a sell's unrealised PnL rises as price falls`() {
        val pnl = TradeOverlay.unrealizedPnlMicros(
            position = position(side = OrderSide.SELL, entry = 2_000_000),
            markPriceUnits = 1_999_000,
            contract = contract,
        )
        assertEquals(10_000_000L, pnl)
    }

    @Test
    fun `a losing position reports a negative PnL`() {
        val pnl = TradeOverlay.unrealizedPnlMicros(
            position = position(side = OrderSide.BUY, entry = 2_000_000),
            markPriceUnits = 1_999_000,
            contract = contract,
        )
        assertEquals(-10_000_000L, pnl)
    }

    @Test
    fun `unrealised PnL scales with the position size`() {
        val small = TradeOverlay.unrealizedPnlMicros(
            position = position(side = OrderSide.BUY, entry = 2_000_000, volume = 10),
            markPriceUnits = 2_001_000,
            contract = contract,
        )
        val large = TradeOverlay.unrealizedPnlMicros(
            position = position(side = OrderSide.BUY, entry = 2_000_000, volume = 100),
            markPriceUnits = 2_001_000,
            contract = contract,
        )
        assertEquals(small * 10, large)
    }
}
