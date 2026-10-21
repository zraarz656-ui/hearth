package com.tradequest.app.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tradequest.app.ui.components.UnfinishedScreen

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    UnfinishedScreen(
        featureName = "Settings",
        plannedPhase = "Planned for Phases 14-15",
        modifier = modifier,
    )
}
