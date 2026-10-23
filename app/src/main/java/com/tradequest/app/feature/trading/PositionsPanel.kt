package com.tradequest.app.feature.trading

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tradequest.app.core.accounting.MoneyScale
import com.tradequest.app.core.market.PriceScale
import com.tradequest.app.core.trading.ClosedTrade
import com.tradequest.app.core.trading.ContractSpec
import com.tradequest.app.core.trading.OrderSide
import com.tradequest.app.core.trading.Position
import com.tradequest.app.core.trading.TradeOrder
import com.tradequest.app.core.trading.TradeOverlay

/**
 * The open positions, pending orders and recent history (Steps 10.7, 10.9).
 *
 * # Why this is one panel rather than three screens
 *
 * A trader managing a position is looking at all three at once: what is open, what
 * is resting, and what just closed. Splitting them would make "did my last trade
 * hit target?" a navigation rather than a glance.
 *
 * # Why the marks arrive as a map rather than being derived here
 *
 * The mark is the engine's exit-side price, computed with the spread and the news
 * window the engine knows about. Re-deriving it in the UI would be a second copy of
 * the marking rule, and the two would eventually disagree — the exact defect the
 * engine's `markPricesAt` exists to prevent.
 */
@Composable
internal fun PositionsPanel(
    panel: TradingPanelState,
    contract: ContractSpec,
    onClose: (String) -> Unit,
    onCancel: (String) -> Unit,
    onBreakeven: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(16.dp)
            .heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Positions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = onDismiss) { Text("Close") }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (panel.positions.isEmpty() && panel.pendingOrders.isEmpty()) {
                item {
                    Text(
                        text = "No open positions or pending orders.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(panel.positions, key = { it.positionId }) { position ->
                PositionRow(
                    position = position,
                    markUnits = panel.markPrices[position.positionId],
                    contract = contract,
                    onClose = { onClose(position.positionId) },
                    onBreakeven = { onBreakeven(position.positionId) },
                )
            }

            items(panel.pendingOrders, key = { it.orderId }) { order ->
                PendingOrderRow(
                    order = order,
                    contract = contract,
                    onCancel = { onCancel(order.orderId) },
                )
            }

            if (panel.closedTrades.isNotEmpty()) {
                item {
                    Text(
                        text = "Closed",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(panel.closedTrades.take(CLOSED_HISTORY_LIMIT), key = { it.tradeId }) { trade ->
                    ClosedTradeRow(trade)
                }
            }
        }
    }
}

@Composable
private fun PositionRow(
    position: Position,
    markUnits: Int?,
    contract: ContractSpec,
    onClose: () -> Unit,
    onBreakeven: () -> Unit,
) {
    val accent = sideColour(position.side)
    // The PnL uses the engine's mark, so the figure matches what a close would
    // realise. Without a mark the position is shown flat rather than with a guess.
    val pnl = markUnits?.let {
        TradeOverlay.unrealizedPnlMicros(position, it, contract)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${position.side.name} ${formatLots(position.volumeHundredths)}",
                fontWeight = FontWeight.Bold,
                color = accent,
            )
            Text(
                text = pnl?.let(MoneyScale::formatMicros) ?: "—",
                fontWeight = FontWeight.Bold,
                color = pnlColour(pnl),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Entry ${PriceScale.format(position.openPriceUnits)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Mark ${markUnits?.let(PriceScale::format) ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "SL ${position.activeStopUnits?.let(PriceScale::format) ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "TP ${position.targetUnits?.let(PriceScale::format) ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Close")
            }
            // Breakeven is offered only while it can still help: once the stop is at
            // or past entry the rule has already done its job.
            val stopAtEntry = position.activeStopUnits != null &&
                position.activeStopUnits == position.openPriceUnits
            if (!stopAtEntry) {
                OutlinedButton(onClick = onBreakeven) { Text("Breakeven") }
            }
        }
    }
}

@Composable
private fun PendingOrderRow(order: TradeOrder, contract: ContractSpec, onCancel: () -> Unit) {
    val accent = sideColour(order.side)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${order.type.name} ${order.side.name} ${formatLots(order.volumeHundredths)}",
                fontWeight = FontWeight.Bold,
                color = accent,
            )
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
        Text(
            text = "At ${order.requestedPriceUnits?.let(PriceScale::format) ?: "market"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ClosedTradeRow(trade: ClosedTrade) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "${trade.side.name} ${formatLots(trade.volumeHundredths)} · ${trade.closeReason.name}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = MoneyScale.formatMicros(trade.netPnlMicros),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = pnlColour(trade.netPnlMicros),
        )
    }
}

/**
 * How many closed trades the panel lists.
 *
 * A cap rather than the whole season: the list is a glance, not a report, and a
 * season of trades in a LazyColumn behind a chart would cost more to build than it
 * is worth. The full history belongs to the review screen, which arrives later.
 */
private const val CLOSED_HISTORY_LIMIT: Int = 20
