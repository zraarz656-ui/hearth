package com.tradequest.app.feature.history

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tradequest.app.ui.components.UnfinishedScreen

@Composable
fun HistoryScreen(modifier: Modifier = Modifier) {
    UnfinishedScreen(
        featureName = "Challenge History",
        plannedPhase = "Planned for Phase 11",
        modifier = modifier,
    )
}
