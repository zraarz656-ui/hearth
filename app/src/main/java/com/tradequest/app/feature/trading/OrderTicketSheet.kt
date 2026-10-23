package com.tradequest.app.feature.trading

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tradequest.app.core.accounting.MoneyScale
import com.tradequest.app.core.market.PriceScale
import com.tradequest.app.core.trading.BracketEconomics
import com.tradequest.app.core.trading.OrderSide
import com.tradequest.app.core.trading.OrderType

/**
 * The order ticket (Step 10.2).
 *
 * # Why the fields are text, not sliders
 *
 * A price is a precise value a trader has in mind; a slider makes them hunt for
 * it. Text fields also let the ticket accept an exact stop the trader calculated
 * elsewhere, which is how a disciplined trader actually works.
 *
 * # Why the size field is disabled when the risk lock is on
 *
 * When the lock sizes the trade, the size is derived from the stop and the
 * account, so an editable field would be a control that silently reverts. Disabling
 * it says "this is computed" rather than letting the trader type a number that
 * gets ignored.
 */
@Composable
internal fun OrderTicketSheet(
    ticket: OrderTicket,
    panel: TradingPanelState,
    onUpdate: ((OrderTicket) -> OrderTicket) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val accent = sideColour(ticket.side)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${ticket.side.name} · ${ticket.type.name}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }

        OrderTypeRow(
            selected = ticket.type,
            onSelected = { type -> onUpdate { it.copy(type = type) } },
        )

        if (ticket.type != OrderType.MARKET) {
            PriceField(
                label = "Entry price",
                value = ticket.priceUnits,
                onValue = { units -> onUpdate { it.copy(priceUnits = units) } },
            )
        }

        StopAndTargetFields(
            ticket = ticket,
            onUpdate = onUpdate,
        )

        RiskLockToggle(
            ticket = ticket,
            panel = panel,
            onUpdate = onUpdate,
        )

        BracketPreview(economics = panel.bracketPreview)

        Button(
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = accent),
        ) {
            Text("Place ${ticket.side.name.lowercase()} order", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun OrderTypeRow(selected: OrderType, onSelected: (OrderType) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (type in OrderType.entries) {
            FilterChip(
                selected = type == selected,
                onClick = { onSelected(type) },
                label = { Text(type.name) },
            )
        }
    }
}

/**
 * The lot-size and risk-lock controls (Steps 10.2, 10.5, 10.6).
 *
 * The lot size is expressed in hundredths to match the engine, and shown as lots
 * (20 hundredths = "0.20"). Parsing is forgiving of a trailing or leading space
 * and of a comma, because a numeric keyboard on some locales produces one.
 */
@Composable
private fun RiskLockToggle(
    ticket: OrderTicket,
    panel: TradingPanelState,
    onUpdate: ((OrderTicket) -> OrderTicket) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = ticket.useRiskLock,
            onClick = { onUpdate { it.copy(useRiskLock = !it.useRiskLock) } },
            label = { Text("Size from risk lock") },
        )
        if (ticket.useRiskLock) {
            val lots = panel.riskLockLotUnits
            Text(
                text = if (lots == null) {
                    "Set a stop to size from the lock"
                } else {
                    "Lock ${panel.riskLock.label} → ${formatLots(lots)} lots"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LotSizeField(
                volumeHundredths = ticket.volumeHundredths,
                onValue = { volume -> onUpdate { it.copy(volumeHundredths = volume) } },
            )
        }
    }
}

@Composable
private fun LotSizeField(volumeHundredths: Int, onValue: (Int) -> Unit) {
    OutlinedTextField(
        value = formatLots(volumeHundredths),
        onValueChange = { text ->
            val hundredths = parseLots(text)
            if (hundredths != null) onValue(hundredths)
        },
        label = { Text("Lots") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The stop and target, entered as pip distances (Step 10.2).
 *
 * Distances rather than prices because that is what the engine stores and what a
 * trader plans in: "a 50-pip stop" is a decision, "$1,995.00" is its consequence.
 * The consequence is shown as the overlay and in the R:R, so nothing is lost.
 */
@Composable
private fun StopAndTargetFields(
    ticket: OrderTicket,
    onUpdate: ((OrderTicket) -> OrderTicket) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PipsField(
            label = "Stop (pips)",
            value = ticket.stopLossPips,
            onValue = { pips -> onUpdate { it.copy(stopLossPips = pips) } },
            modifier = Modifier.weight(1f),
        )
        PipsField(
            label = "Target (pips)",
            value = ticket.takeProfitPips,
            onValue = { pips -> onUpdate { it.copy(takeProfitPips = pips) } },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PipsField(
    label: String,
    value: Int?,
    onValue: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value?.toString().orEmpty(),
        onValueChange = { text ->
            val trimmed = text.trim()
            // An empty field means "no stop", not zero. Zero is not a valid distance
            // and the engine rejects it, so clearing the field must clear the stop.
            onValue(if (trimmed.isEmpty()) null else trimmed.toIntOrNull())
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun PriceField(label: String, value: Int?, onValue: (Int?) -> Unit) {
    OutlinedTextField(
        value = value?.let(PriceScale::format).orEmpty(),
        onValueChange = { text ->
            val trimmed = text.trim()
            onValue(if (trimmed.isEmpty()) null else parsePrice(trimmed))
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The risk, reward and R:R of the planned bracket (Step 10.5).
 *
 * The R:R is the headline: it is the number that decides whether a trade is worth
 * taking, and it is the one a trader most often miscalculates by hand.
 */
@Composable
private fun BracketPreview(economics: BracketEconomics?) {
    if (economics == null || !economics.isComplete) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        PreviewFigure("Risk", economics.riskMicros?.let(MoneyScale::formatMicros), SellRed)
        PreviewFigure("Reward", economics.rewardMicros?.let(MoneyScale::formatMicros), BuyGreen)
        PreviewFigure("R:R", economics.riskReward?.label, MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun PreviewFigure(label: String, value: String?, colour: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value ?: "—",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = colour,
        )
    }
}

/** Formats hundredths of a lot as a two-decimal lot size. 20 -> "0.20". */
internal fun formatLots(volumeHundredths: Int): String {
    val whole = volumeHundredths / 100
    val frac = kotlin.math.abs(volumeHundredths % 100)
    return "$whole.${frac.toString().padStart(2, '0')}"
}

/**
 * Parses a lot size to hundredths, or null when the text is not a number.
 *
 * Null means "ignore this keystroke" rather than "zero", so a half-typed "-" or
 * "abc" leaves the field as it was. Zero is allowed through: it is not a trade, but
 * the ticket refuses it on submit with a message, which is clearer than a field
 * that silently refuses to empty.
 */
internal fun parseLots(text: String): Int? {
    val normalised = text.trim().replace(',', '.')
    if (normalised.isEmpty()) return null
    val lots = normalised.toDoubleOrNull() ?: return null
    if (lots < 0) return null
    return kotlin.math.round(lots * 100).toInt()
}

/** Parses a price string to integer units, or null when it is not a number. */
internal fun parsePrice(text: String): Int? {
    val normalised = text.trim().replace(",", "")
    if (normalised.isEmpty()) return null
    return PriceScale.usdToUnits(normalised.toDoubleOrNull() ?: return null)
}
