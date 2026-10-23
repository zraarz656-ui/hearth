package com.tradequest.app.feature.trading

import com.tradequest.app.core.chart.buffer.CandleBuffer
import com.tradequest.app.core.chart.renderer.OverlayLineKind
import com.tradequest.app.core.market.HistoricalCandle
import com.tradequest.app.core.trading.ContractSpec
import com.tradequest.app.core.trading.OrderSide
import com.tradequest.app.core.trading.OrderStatus
import com.tradequest.app.core.trading.OrderType
import com.tradequest.app.core.trading.Position
import com.tradequest.app.core.trading.PositionStatus
import com.tradequest.app.core.trading.TradeOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 10 overlay mapping (Steps 10.3, 10.8).
 *
 * # What is being verified
 *
 * That a position's stored levels become the right lines with the right
 * draggability, that a pending order's pip distances become the right prices, that
 * a preview bracket is marked as a preview, and that a closed trade's markers land
 * on the bar it actually happened in.
 */
class TradeOverlayMapperTest {

    private val contract = ContractSpec.XAUUSD

    private fun position(
        id: String = "p1",
        side: OrderSide = OrderSide.BUY,
        entry: Int = 2_000_000,
        stop: Int? = null,
        target: Int? = null,
    ): Position = Position(
        positionId = id,
        seasonId = "s1",
        openedAtSimUtcMs = 0L,
        side = side,
        volumeHundredths = 10,
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

    private fun pendingOrder(
        id: String = "o1",
        side: OrderSide = OrderSide.BUY,
        price: Int = 2_000_000,
        stopPips: Int? = null,
        targetPips: Int? = null,
    ): TradeOrder = TradeOrder(
        orderId = id,
        seasonId = "s1",
        placedAtSimUtcMs = 0L,
        type = OrderType.LIMIT,
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

    // ----------------------------------------------------------- position lines

    @Test
    fun `a position's entry is not draggable but its stop and target are`() {
        val groups = TradeOverlayMapper.groups(
            positions = listOf(position(stop = 1_995_000, target = 2_010_000)),
            pendingOrders = emptyList(),
            contract = contract,
        )
        val group = groups.single()
        assertEquals("p1", group.id)
        assertFalse(group.entry!!.isDraggable)
        assertTrue(group.stop!!.isDraggable)
        assertTrue(group.target!!.isDraggable)
        assertFalse(group.isPreview)
    }

    @Test
    fun `a position with no stop or target maps to a single entry line`() {
        val group = TradeOverlayMapper.groups(
            positions = listOf(position()),
            pendingOrders = emptyList(),
            contract = contract,
        ).single()
        assertEquals(1, group.lines.size)
        assertNull(group.stop)
        assertNull(group.target)
    }

    @Test
    fun `a sell position maps as not a buy`() {
        val group = TradeOverlayMapper.groups(
            positions = listOf(position(side = OrderSide.SELL)),
            pendingOrders = emptyList(),
            contract = contract,
        ).single()
        assertFalse(group.isBuy)
    }

    // ------------------------------------------------------ pending order lines

    @Test
    fun `a pending order's pip distances become prices on the right sides`() {
        // A buy limit at 2,000,000 with a 50-pip stop and 100-pip target.
        val group = TradeOverlayMapper.groups(
            positions = emptyList(),
            pendingOrders = listOf(pendingOrder(stopPips = 50, targetPips = 100)),
            contract = contract,
        ).single()
        assertEquals(2_000_000, group.entry!!.priceUnits)
        // Stop below, target above, for a buy. 50 pips = 5,000 units.
        assertEquals(1_995_000, group.stop!!.priceUnits)
        assertEquals(2_010_000, group.target!!.priceUnits)
        // A pending order's entry is draggable: it has not been filled.
        assertTrue(group.entry!!.isDraggable)
    }

    @Test
    fun `a pending sell order's levels are mirrored`() {
        val group = TradeOverlayMapper.groups(
            positions = emptyList(),
            pendingOrders = listOf(
                pendingOrder(side = OrderSide.SELL, price = 2_000_000, stopPips = 50, targetPips = 100),
            ),
            contract = contract,
        ).single()
        assertEquals(2_005_000, group.stop!!.priceUnits)
        assertEquals(1_990_000, group.target!!.priceUnits)
    }

    @Test
    fun `an order with no requested price maps to nothing`() {
        val order = pendingOrder().copy(type = OrderType.MARKET, requestedPriceUnits = null)
        val groups = TradeOverlayMapper.groups(
            positions = emptyList(),
            pendingOrders = listOf(order),
            contract = contract,
        )
        assertTrue(groups.isEmpty())
    }

    // -------------------------------------------------------------- preview

    @Test
    fun `a preview bracket is marked as a preview and is not draggable`() {
        val groups = TradeOverlayMapper.groups(
            positions = emptyList(),
            pendingOrders = emptyList(),
            contract = contract,
            previewBracket = TradeOverlayMapper.PreviewBracket(
                side = OrderSide.BUY,
                entryPriceUnits = 2_000_000,
                stopPriceUnits = 1_995_000,
                targetPriceUnits = 2_010_000,
            ),
        )
        val preview = groups.single()
        assertEquals(TradeOverlayMapper.PREVIEW_ID, preview.id)
        assertTrue(preview.isPreview)
        // A preview is not a live order, so nothing on it should be grabbable.
        assertTrue(preview.lines.none { it.isDraggable })
    }

    @Test
    fun `positions, orders and a preview all appear together`() {
        val groups = TradeOverlayMapper.groups(
            positions = listOf(position(id = "p1")),
            pendingOrders = listOf(pendingOrder(id = "o1")),
            contract = contract,
            previewBracket = TradeOverlayMapper.PreviewBracket(
                side = OrderSide.BUY,
                entryPriceUnits = 2_050_000,
                stopPriceUnits = null,
                targetPriceUnits = null,
            ),
        )
        assertEquals(3, groups.size)
        assertEquals(setOf("p1", "o1", TradeOverlayMapper.PREVIEW_ID), groups.map { it.id }.toSet())
    }

    // -------------------------------------------------------------- markers

    private fun buffer(count: Int, startMs: Long = 1_704_067_200_000L): CandleBuffer {
        val minute = 60_000L
        val candles = (0 until count).map { i ->
            HistoricalCandle(
                timestampUtcMs = startMs + i * minute,
                openUnits = 2_000_000,
                highUnits = 2_000_100,
                lowUnits = 1_999_900,
                closeUnits = 2_000_000,
                tickVolume = 100,
            )
        }
        return CandleBuffer.of(candles)
    }

    @Test
    fun `a closed trade yields an entry and an exit marker on the right bars`() {
        val startMs = 1_704_067_200_000L
        val minute = 60_000L
        val buffer = buffer(10, startMs)
        val trade = com.tradequest.app.core.trading.ClosedTrade(
            tradeId = "t1",
            seasonId = "s1",
            positionId = "p1",
            side = OrderSide.BUY,
            volumeHundredths = 10,
            openPriceUnits = 2_000_000,
            closePriceUnits = 2_001_000,
            openTimeSimUtcMs = startMs + 2 * minute,
            closeTimeSimUtcMs = startMs + 7 * minute,
            grossPnlMicros = 0L,
            commissionMicros = 0L,
            swapMicros = 0L,
            netPnlMicros = 0L,
            closeReason = com.tradequest.app.core.trading.CloseReason.TAKE_PROFIT,
            realizedRMicros = 0L,
        )
        val markers = TradeOverlayMapper.markers(listOf(trade), buffer)
        assertEquals(2, markers.size)

        val entry = markers.first { it.isEntry }
        assertEquals(2, entry.index)
        assertTrue(entry.isBuy)
        assertEquals(2_000_000, entry.priceUnits)

        val exit = markers.first { !it.isEntry }
        assertEquals(7, exit.index)
        assertEquals(2_001_000, exit.priceUnits)
    }

    @Test
    fun `a marker before the buffered history is skipped`() {
        // The buffer starts at minute 0; the trade opened a minute earlier, so there
        // is no bar to draw its entry on.
        val startMs = 1_704_067_200_000L
        val buffer = buffer(5, startMs)
        val trade = com.tradequest.app.core.trading.ClosedTrade(
            tradeId = "t1",
            seasonId = "s1",
            positionId = "p1",
            side = OrderSide.BUY,
            volumeHundredths = 10,
            openPriceUnits = 2_000_000,
            closePriceUnits = 2_001_000,
            openTimeSimUtcMs = startMs - 60_000L,
            closeTimeSimUtcMs = startMs + 60_000L,
            grossPnlMicros = 0L,
            commissionMicros = 0L,
            swapMicros = 0L,
            netPnlMicros = 0L,
            closeReason = com.tradequest.app.core.trading.CloseReason.TAKE_PROFIT,
            realizedRMicros = 0L,
        )
        val markers = TradeOverlayMapper.markers(listOf(trade), buffer)
        // Only the exit marker survives.
        assertEquals(1, markers.size)
        assertFalse(markers.single().isEntry)
        assertNotNull(markers.single())
    }

    @Test
    fun `a trade entirely outside the buffer yields no markers`() {
        val startMs = 1_704_067_200_000L
        val buffer = buffer(5, startMs)
        val trade = com.tradequest.app.core.trading.ClosedTrade(
            tradeId = "t1",
            seasonId = "s1",
            positionId = "p1",
            side = OrderSide.SELL,
            volumeHundredths = 10,
            openPriceUnits = 2_000_000,
            closePriceUnits = 1_999_000,
            openTimeSimUtcMs = startMs - 10 * 60_000L,
            closeTimeSimUtcMs = startMs - 5 * 60_000L,
            grossPnlMicros = 0L,
            commissionMicros = 0L,
            swapMicros = 0L,
            netPnlMicros = 0L,
            closeReason = com.tradequest.app.core.trading.CloseReason.STOP_LOSS,
            realizedRMicros = 0L,
        )
        assertTrue(TradeOverlayMapper.markers(listOf(trade), buffer).isEmpty())
    }
}
