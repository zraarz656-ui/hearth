package com.tradequest.app.feature.challenge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tradequest.app.R
import com.tradequest.app.ui.components.challengeOutcomeLabel
import com.tradequest.app.ui.components.challengeTitleLabel
import com.tradequest.app.ui.components.seasonStatusLabel
import com.tradequest.app.core.challenge.ChallengeDashboardState
import com.tradequest.app.core.challenge.ChallengeFormat
import com.tradequest.app.core.challenge.ChallengeScoreWeights

/**
 * The running challenge's dashboard (Step 11.8).
 *
 * Shows balance, equity, profit, target progress, the remaining daily and overall
 * loss budgets, the trading days, the strikes, the score and the season status. The
 * history of finished attempts and the personal bests live on their own screen
 * (`feature/history`), because the plan asks for them separately and a trader
 * reading their live budget is not also scanning old results.
 *
 * It reads through [ChallengeOverviewSource] only, so the screen never touches a
 * database and never decides a pass or a fail: the verdict is stored by whoever
 * held the engine's state, and this renders it.
 */
@Composable
fun ChallengeDashboardScreen(
    source: ChallengeOverviewSource,
    modifier: Modifier = Modifier,
) {
    val viewModel: ChallengeDashboardViewModel = viewModel(
        factory = ChallengeDashboardViewModel.factory(source),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    ChallengeDashboardContent(state = state, modifier = modifier)
}

@Composable
private fun ChallengeDashboardContent(
    state: ChallengeDashboardUiState,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is ChallengeDashboardUiState.Loading -> Centered(modifier) {
            CircularProgressIndicator()
        }

        is ChallengeDashboardUiState.Failed -> Centered(modifier) {
            Text(
                text = stringResource(R.string.challenge_error_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        is ChallengeDashboardUiState.Loaded -> LoadedContent(state = state, modifier = modifier)
    }
}

@Composable
private fun LoadedContent(
    state: ChallengeDashboardUiState.Loaded,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.challenge_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            )
        }

        val dashboard = state.dashboard
        if (dashboard == null) {
            item { NoSeasonCard() }
        } else {
            item { DashboardCard(dashboard) }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun NoSeasonCard() {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Text(
            text = stringResource(R.string.challenge_no_season_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.challenge_no_season_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun DashboardCard(dashboard: ChallengeDashboardState) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = seasonStatusLabel(dashboard.status),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = challengeTitleLabel(dashboard.score.title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        dashboard.outcome?.let { outcome ->
            Text(
                text = challengeOutcomeLabel(outcome),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        SectionHeader(stringResource(R.string.challenge_section_progress))
        MetricRow(
            label = stringResource(R.string.challenge_balance),
            value = ChallengeFormat.money(dashboard.balanceMicros),
        )
        MetricRow(
            label = stringResource(R.string.challenge_equity),
            value = ChallengeFormat.money(dashboard.equityMicros),
        )
        MetricRow(
            label = stringResource(R.string.challenge_profit),
            value = ChallengeFormat.money(dashboard.profitMicros),
        )
        MetricRow(
            label = stringResource(R.string.challenge_target),
            value = ChallengeFormat.money(dashboard.profitTargetMicros),
        )

        Text(
            text = stringResource(R.string.challenge_target_progress),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 8.dp),
        )
        LinearProgressIndicator(
            progress = { dashboard.targetProgress },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        Text(
            text = stringResource(
                R.string.challenge_progress_percent,
                (dashboard.targetProgress * 100).toInt(),
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        MetricRow(
            label = stringResource(R.string.challenge_trading_days),
            value = stringResource(
                R.string.challenge_of_days,
                dashboard.tradingDayCount,
                dashboard.minimumTradingDays,
            ),
        )

        SectionHeader(stringResource(R.string.challenge_section_limits))
        MetricRow(
            label = stringResource(R.string.challenge_daily_loss_remaining),
            value = ChallengeFormat.money(dashboard.dailyLossRemainingMicros),
        )
        MetricRow(
            label = stringResource(R.string.challenge_max_loss_remaining),
            value = ChallengeFormat.money(dashboard.overallLossRemainingMicros),
        )
        MetricRow(
            label = stringResource(R.string.challenge_strikes),
            value = stringResource(
                R.string.challenge_of_strikes,
                dashboard.dailyStrikeCount,
                dashboard.maxDailyStrikes,
            ),
        )

        SectionHeader(stringResource(R.string.challenge_section_score))
        ScoreRow(
            label = stringResource(R.string.challenge_score_profit),
            points = dashboard.score.profitPoints,
            max = ChallengeScoreWeights.PROFIT,
        )
        ScoreRow(
            label = stringResource(R.string.challenge_score_risk),
            points = dashboard.score.riskControlPoints,
            max = ChallengeScoreWeights.RISK_CONTROL,
        )
        ScoreRow(
            label = stringResource(R.string.challenge_score_consistency),
            points = dashboard.score.consistencyPoints,
            max = ChallengeScoreWeights.CONSISTENCY,
        )
        ScoreRow(
            label = stringResource(R.string.challenge_score_discipline),
            points = dashboard.score.disciplinePoints,
            max = ChallengeScoreWeights.DISCIPLINE,
        )
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        ScoreRow(
            label = stringResource(R.string.challenge_score_total),
            points = dashboard.score.total,
            max = ChallengeScoreWeights.TOTAL_POINTS,
            emphasised = true,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ScoreRow(label: String, points: Int, max: Int, emphasised: Boolean = false) {
    val weight = if (emphasised) FontWeight.Bold else FontWeight.Normal
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = weight,
        )
        Text(
            text = stringResource(R.string.challenge_points_of_max, points, max),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = weight,
        )
    }
}

@Composable
private fun Centered(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        content()
    }
}
