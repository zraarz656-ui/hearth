package com.tradequest.app.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tradequest.app.ui.components.UnfinishedScreen

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    UnfinishedScreen(
        featureName = "Home",
        plannedPhase = "Planned for Phase 0/11",
        modifier = modifier,
    )
}
