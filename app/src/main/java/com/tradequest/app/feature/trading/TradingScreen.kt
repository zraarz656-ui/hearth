package com.tradequest.app.feature.trading

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tradequest.app.ui.components.UnfinishedScreen

@Composable
fun TradingScreen(modifier: Modifier = Modifier) {
    UnfinishedScreen(
        featureName = "Trading",
        plannedPhase = "Planned for Phases 8-10",
        modifier = modifier,
    )
}
