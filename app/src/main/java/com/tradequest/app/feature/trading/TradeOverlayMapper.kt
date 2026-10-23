package com.tradequest.app.feature.trading

import com.tradequest.app.core.chart.buffer.CandleBuffer
import com.tradequest.app.core.chart.renderer.OverlayGroup
import com.tradequest.app.core.chart.renderer.OverlayLine
import com.tradequest.app.core.chart.renderer.OverlayLineKind
import com.tradequest.app.core.chart.renderer.TradeMarker
import com.tradequest.app.core.trading.ClosedTrade
import com.tradequest.app.core.trading.ContractSpec
import com.tradequest.app.core.trading.OrderSide
import com.tradequest.app.core.trading.Position
import com.tradequest.app.core.trading.TradeOrder
import com.tradequest.app.core.trading.TradeOverlay

/**
 * Maps the trading domain onto the chart renderer's overlay types (Steps 10.3,
 * 10.8).
 *
 * # Why the mapping lives in the feature layer
 *
 * `core/chart` draws geometry and must not know what a position is, and
 * `core/trading` must not depend on Compose. Neither can reference the other, so
 * the translation has to happen somewhere that may depend on both — and that is
 * the feature layer. Keeping it in one object rather than inline in the ViewModel
 * means it can be tested against real [Position] and [TradeOrder] values without a
 * chart, which is what `TradeOverlayMapperTest` does.
 *
 * # Why a position needs the contract and an order does not
 *
 * A position stores its stop as an *absolute price* ([Position.activeStopUnits]),
 * so mapping one is a direct read. A pending order stores its stop as a *pip
 * distance* from an entry that has not filled yet, so its stop price has to be
 * derived — and that needs the pip size, which is the contract. The asymmetry is
 * the domain's, not this mapper's.
 */
object TradeOverlayMapper {

    /**
     * One group per open position and per pending order that has a price.
     *
     * A market order still pending is not drawn: it has no requested price, so
     * there is no level to put a line at. That is rare — a market order fills on
     * the command that places it — but the guard keeps the mapper total rather than
     * crashing on a price-less order.
     */
    fun groups(
        positions: List<Position>,
        pendingOrders: List<TradeOrder>,
        contract: ContractSpec,
        previewBracket: PreviewBracket? = null,
    ): List<OverlayGroup> = buildList {
        for (position in positions) {
            add(positionGroup(position))
        }
        for (order in pendingOrders) {
            orderGroup(order, contract)?.let { add(it) }
        }
        previewBracket?.let { add(previewGroup(it)) }
    }

    /**
     * The entry, stop and target lines of an open position.
     *
     * The entry is not draggable — it is a record of what happened — while the stop
     * and target are, because those are decisions the trader is still free to change.
     */
    private fun positionGroup(position: Position): OverlayGroup = OverlayGroup(
        id = position.positionId,
        isBuy = position.side == OrderSide.BUY,
        lines = buildList {
            add(OverlayLine(OverlayLineKind.ENTRY, position.openPriceUnits, isDraggable = false))
            position.activeStopUnits?.let {
                add(OverlayLine(OverlayLineKind.STOP, it, isDraggable = true))
            }
            position.targetUnits?.let {
                add(OverlayLine(OverlayLineKind.TARGET, it, isDraggable = true))
            }
        },
    )

    /**
     * The lines of a pending order, with its stop and target turned from pip
     * distances into prices.
     *
     * A stop or target that would land on the wrong side of the entry is dropped
     * rather than drawn: the engine would reject it, so drawing it would show the
     * trader a level that cannot exist.
     */
    private fun orderGroup(order: TradeOrder, contract: ContractSpec): OverlayGroup? {
        val entry = order.requestedPriceUnits ?: return null
        return OverlayGroup(
            id = order.orderId,
            isBuy = order.side == OrderSide.BUY,
            lines = buildList {
                add(OverlayLine(OverlayLineKind.ENTRY, entry, isDraggable = true))
                order.stopLossPips?.let { pips ->
                    TradeOverlay.stopPriceFor(order.side, entry, pips, contract)
                        .let { add(OverlayLine(OverlayLineKind.STOP, it, isDraggable = true)) }
                }
                order.takeProfitPips?.let { pips ->
                    TradeOverlay.targetPriceFor(order.side, entry, pips, contract)
                        .let { add(OverlayLine(OverlayLineKind.TARGET, it, isDraggable = true)) }
                }
            },
        )
    }

    /** The sizer's not-yet-submitted bracket, drawn with a distinct id (Step 10.5). */
    private fun previewGroup(preview: PreviewBracket): OverlayGroup = OverlayGroup(
        id = PREVIEW_ID,
        isBuy = preview.side == OrderSide.BUY,
        isPreview = true,
        lines = buildList {
            add(OverlayLine(OverlayLineKind.ENTRY, preview.entryPriceUnits, isDraggable = false))
            preview.stopPriceUnits?.let {
                add(OverlayLine(OverlayLineKind.STOP, it, isDraggable = false))
            }
            preview.targetPriceUnits?.let {
                add(OverlayLine(OverlayLineKind.TARGET, it, isDraggable = false))
            }
        },
    )

    /**
     * Entry and exit arrows for closed trades (Step 10.8).
     *
     * # Why closed trades and not open positions
     *
     * A marker is an annotation on the bar where something happened. An open
     * position's entry is already shown by its entry line, which spans the plot and
     * is the thing a trader drags; an arrow would duplicate it. A closed trade has
     * no lines left — its bracket is gone — so the arrow is the only record of where
     * it was entered and exited, and that record is what a trader reviews.
     *
     * # Why the bar index is resolved here
     *
     * The buffer maps a timestamp to a bar once, at mapping time, rather than the
     * renderer searching per frame. A trade whose open or close falls outside the
     * buffered history — before the dataset's first bar, or after its last — is
     * skipped, because there is no bar to draw it on.
     */
    fun markers(closedTrades: List<ClosedTrade>, buffer: CandleBuffer): List<TradeMarker> = buildList {
        for (trade in closedTrades) {
            val entryIndex = buffer.indexAtOrBefore(trade.openTimeSimUtcMs)
            if (entryIndex >= 0) {
                add(
                    TradeMarker(
                        index = entryIndex,
                        priceUnits = trade.openPriceUnits,
                        isEntry = true,
                        isBuy = trade.side == OrderSide.BUY,
                    ),
                )
            }
            val exitIndex = buffer.indexAtOrBefore(trade.closeTimeSimUtcMs)
            if (exitIndex >= 0) {
                add(
                    TradeMarker(
                        index = exitIndex,
                        priceUnits = trade.closePriceUnits,
                        isEntry = false,
                        isBuy = trade.side == OrderSide.BUY,
                    ),
                )
            }
        }
    }

    /**
     * A bracket the sizer is previewing but has not submitted.
     *
     * A separate type from a position because it has no id, no volume and no
     * lifecycle — it is four prices and a side.
     */
    data class PreviewBracket(
        val side: OrderSide,
        val entryPriceUnits: Int,
        val stopPriceUnits: Int?,
        val targetPriceUnits: Int?,
    )

    /** The id the preview group is drawn under, so a test can find it. */
    const val PREVIEW_ID: String = "preview"
}
