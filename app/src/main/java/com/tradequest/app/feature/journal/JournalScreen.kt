package com.tradequest.app.feature.journal

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tradequest.app.ui.components.UnfinishedScreen

@Composable
fun JournalScreen(modifier: Modifier = Modifier) {
    UnfinishedScreen(
        featureName = "Journal",
        plannedPhase = "Planned for Phase 14",
        modifier = modifier,
    )
}
