package com.tradequest.app.feature.trading

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tradequest.app.core.accounting.MoneyScale
import com.tradequest.app.core.risk.RiskLockBps
import com.tradequest.app.core.trading.OrderSide

/** The green/red used for buy and sell throughout the trading surfaces. */
internal val BuyGreen = Color(0xFF1B9E5A)
internal val SellRed = Color(0xFFC0392B)

/**
 * The account strip: balance, equity and open exposure (Step 10.9).
 *
 * # Why equity and balance are both shown
 *
 * Balance is what the account is worth with everything closed; equity is what it
 * is worth now, with open positions marked. A trader needs both — the difference
 * between them *is* the floating PnL — and showing only balance would hide a losing
 * position until it closed.
 */
@Composable
internal fun AccountStrip(panel: TradingPanelState) {
    val account = panel.account
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "Balance",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = account?.balanceMicros?.let(MoneyScale::formatMicros) ?: "—",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Floating",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val floating = account?.unrealizedPnlMicros
            Text(
                text = floating?.let(MoneyScale::formatMicros) ?: "—",
                style = MaterialTheme.typography.titleSmall,
                color = pnlColour(floating),
                fontWeight = FontWeight.Bold,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "Open",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${panel.openPositionCount} pos · ${panel.pendingOrderCount} ord",
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

/**
 * A signed money figure in green or red, and neutral when it is zero or unknown.
 *
 * Zero is neutral rather than green: a flat position is not a gain, and colouring
 * it would train the eye to read "no loss" as "winning".
 */
internal fun pnlColour(micros: Long?): Color = when {
    micros == null || micros == 0L -> Color.Unspecified
    micros > 0L -> BuyGreen
    else -> SellRed
}

/**
 * The Buy and Sell buttons (Step 10.1).
 *
 * Both open the ticket rather than submitting immediately. A one-tap market order
 * is the kind of control that turns a mis-tap into a live position, and this app's
 * whole point is deliberate practice; the ticket is one extra tap for a much
 * smaller class of mistake.
 */
@Composable
internal fun BuySellBar(
    onBuy: () -> Unit,
    onSell: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(
            onClick = onBuy,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = BuyGreen),
        ) {
            Text("Buy", fontWeight = FontWeight.Bold)
        }
        Button(
            onClick = onSell,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = SellRed),
        ) {
            Text("Sell", fontWeight = FontWeight.Bold)
        }
    }
}

/** The risk-lock preset row (Step 10.6). */
@Composable
internal fun RiskLockRow(
    selected: RiskLockBps,
    onSelected: (RiskLockBps) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Risk",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (preset in RiskLockBps.PRESETS) {
            FilterChip(
                selected = preset.bps == selected.bps,
                onClick = { onSelected(preset) },
                label = { Text(preset.label) },
            )
        }
    }
}

/** The side of a trade as a label, in the colour the chart uses for it. */
internal fun sideColour(side: OrderSide): Color = if (side == OrderSide.BUY) BuyGreen else SellRed
