package com.tradequest.app.feature.challenge

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tradequest.app.ui.components.UnfinishedScreen

@Composable
fun ChallengeDashboardScreen(modifier: Modifier = Modifier) {
    UnfinishedScreen(
        featureName = "Challenge Dashboard",
        plannedPhase = "Planned for Phase 11",
        modifier = modifier,
    )
}
