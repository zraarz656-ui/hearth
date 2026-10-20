package com.tradequest.app.feature.news

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tradequest.app.ui.components.UnfinishedScreen

@Composable
fun NewsScreen(modifier: Modifier = Modifier) {
    UnfinishedScreen(
        featureName = "News",
        plannedPhase = "Planned for Phase 12",
        modifier = modifier,
    )
}
