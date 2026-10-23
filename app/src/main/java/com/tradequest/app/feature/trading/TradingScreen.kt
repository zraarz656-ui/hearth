package com.tradequest.app.feature.trading

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tradequest.app.core.chart.renderer.ChartStyle
import com.tradequest.app.core.chart.timeframe.Timeframe
import com.tradequest.app.core.chart.viewport.ChartViewport
import com.tradequest.app.core.market.MarketRepository
import com.tradequest.app.core.risk.RiskLockBps
import com.tradequest.app.core.trading.ContractSpec
import com.tradequest.app.core.trading.OrderSide
import com.tradequest.app.ui.chart.CandlestickChart
import com.tradequest.app.ui.theme.ThemeMode
import com.tradequest.app.ui.theme.chartStyleFor

/**
 * The Trading screen (Phase 8).
 *
 * Shows the candlestick chart with a timeframe selector. Replay controls, order
 * entry and the crosshair arrive in Phases 9 and 10; this screen deliberately
 * shows only what exists rather than disabled controls that do nothing.
 */
@Composable
fun TradingScreen(
    repository: MarketRepository,
    sessionSource: TradingSessionSource,
    modifier: Modifier = Modifier,
    themeMode: ThemeMode = ThemeMode.DARK,
) {
    val style = chartStyleFor(themeMode)
    val viewModel: TradingViewModel = viewModel(
        factory = TradingViewModel.factory(repository, style, sessionSource),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val panel by viewModel.panel.collectAsStateWithLifecycle()
    val ticket by viewModel.ticket.collectAsStateWithLifecycle()
    val sheet by viewModel.sheet.collectAsStateWithLifecycle()

    TradingContent(
        state = state,
        panel = panel,
        ticket = ticket,
        sheet = sheet,
        style = style,
        onTimeframeSelected = viewModel::selectTimeframe,
        onViewportChange = viewModel::onViewportChanged,
        onBuy = { viewModel.openTicket(OrderSide.BUY) },
        onSell = { viewModel.openTicket(OrderSide.SELL) },
        onTicketUpdate = viewModel::updateTicket,
        onTicketSubmit = viewModel::submitTicket,
        onTicketDismiss = viewModel::dismissSheet,
        onPositionsToggle = viewModel::togglePositionsPanel,
        onClosePosition = viewModel::closePosition,
        onCancelOrder = viewModel::cancelOrder,
        onBreakeven = viewModel::moveToBreakeven,
        onRiskLockSelected = viewModel::selectRiskLock,
        onOrderLineGrab = viewModel::grabOrderLine,
        onOrderLineDrag = viewModel::dragGrabbedLine,
        onOrderLineRelease = viewModel::releaseGrabbedLine,
        modifier = modifier,
    )
}

@Composable
private fun TradingContent(
    state: TradingState,
    panel: TradingPanelState,
    ticket: OrderTicket?,
    sheet: TradingSheet,
    style: ChartStyle,
    onTimeframeSelected: (Timeframe) -> Unit,
    onViewportChange: (ChartViewport) -> Unit,
    onBuy: () -> Unit,
    onSell: () -> Unit,
    onTicketUpdate: ((OrderTicket) -> OrderTicket) -> Unit,
    onTicketSubmit: () -> Unit,
    onTicketDismiss: () -> Unit,
    onPositionsToggle: () -> Unit,
    onClosePosition: (String, Int?) -> Unit,
    onCancelOrder: (String) -> Unit,
    onBreakeven: (String) -> Unit,
    onRiskLockSelected: (RiskLockBps) -> Unit,
    onOrderLineGrab: (Int, Int) -> Boolean,
    onOrderLineDrag: (Int) -> Unit,
    onOrderLineRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (state) {
            TradingState.Loading -> CentredMessage("Loading chart…", showSpinner = true)

            TradingState.NoDataset -> CentredMessage(
                "No market data yet.\nImport a dataset in the Market Library to chart it.",
            )

            is TradingState.Failed -> CentredMessage("Could not load the chart.\n${state.message}")

            is TradingState.Ready -> Column(Modifier.fillMaxSize()) {
                ChartHeader(datasetName = state.dataset.displayName)
                TimeframeRow(
                    selected = state.timeframe,
                    onSelected = onTimeframeSelected,
                )

                if (panel.isLive) {
                    AccountStrip(panel)
                }

                Box(Modifier.fillMaxWidth().weight(1f)) {
                    CandlestickChart(
                        state = state.renderState,
                        onViewportChange = onViewportChange,
                        onOrderLineGrab = onOrderLineGrab,
                        onOrderLineDrag = onOrderLineDrag,
                        onOrderLineRelease = onOrderLineRelease,
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxSize()
                            .background(style.background),
                    )

                    // The panel and the ticket are an overlay rather than a column
                    // entry, so opening one does not resize the chart and change the
                    // price scale under the trader's finger.
                    if (sheet != TradingSheet.NONE) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter),
                        ) {
                            if (sheet == TradingSheet.POSITIONS) {
                                PositionsPanel(
                                    panel = panel,
                                    contract = ContractSpec.XAUUSD,
                                    onClose = { id -> onClosePosition(id, null) },
                                    onCancel = onCancelOrder,
                                    onBreakeven = onBreakeven,
                                    onDismiss = onPositionsToggle,
                                )
                            }
                            ticket?.let {
                                OrderTicketSheet(
                                    ticket = it,
                                    panel = panel,
                                    onUpdate = onTicketUpdate,
                                    onSubmit = onTicketSubmit,
                                    onDismiss = onTicketDismiss,
                                )
                            }
                        }
                    }

                    panel.lastMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(8.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(6.dp),
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }

                if (panel.isLive) {
                    RiskLockRow(selected = panel.riskLock, onSelected = onRiskLockSelected)
                    if (panel.hasExposure) {
                        OutlinedButton(
                            onClick = onPositionsToggle,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        ) {
                            Text("Positions (${panel.openPositionCount}/${panel.pendingOrderCount})")
                        }
                    }
                    BuySellBar(onBuy = onBuy, onSell = onSell)
                }
            }
        }
    }
}

@Composable
private fun ChartHeader(datasetName: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "XAUUSD",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = datasetName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TimeframeRow(
    selected: Timeframe,
    onSelected: (Timeframe) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (timeframe in Timeframe.entries) {
            FilterChip(
                selected = timeframe == selected,
                onClick = { onSelected(timeframe) },
                label = { Text(timeframe.label) },
                shape = RoundedCornerShape(6.dp),
            )
        }
    }
}

@Composable
private fun CentredMessage(message: String, showSpinner: Boolean = false) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (showSpinner) {
                CircularProgressIndicator(Modifier.padding(bottom = 16.dp))
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}
