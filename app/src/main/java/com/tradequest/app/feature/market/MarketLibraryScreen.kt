package com.tradequest.app.feature.market

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tradequest.app.ui.components.UnfinishedScreen

@Composable
fun MarketLibraryScreen(modifier: Modifier = Modifier) {
    UnfinishedScreen(
        featureName = "Market Library",
        plannedPhase = "Planned for Phase 2",
        modifier = modifier,
    )
}
