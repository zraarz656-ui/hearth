package com.tradequest.app.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tradequest.app.R
import com.tradequest.app.core.challenge.ChallengeFormat
import com.tradequest.app.core.challenge.ChallengeHistoryEntry
import com.tradequest.app.core.challenge.ChallengeScoreWeights
import com.tradequest.app.feature.challenge.ChallengeOverviewSource
import com.tradequest.app.feature.challenge.DatasetBest
import com.tradequest.app.ui.components.challengeOutcomeLabel
import com.tradequest.app.ui.components.challengeTitleLabel
import com.tradequest.app.ui.components.seasonStatusLabel

/**
 * Challenge history and personal bests (Steps 11.9-11.11).
 *
 * Lists every finished attempt — passed, failed, abandoned or completed — newest
 * first, then the best result per dataset with the first attempt broken out. It
 * reads through [ChallengeOverviewSource], so the screen never touches a database
 * and never decides an outcome: an attempt is here because it was recorded when it
 * ended.
 */
@Composable
fun HistoryScreen(
    source: ChallengeOverviewSource,
    modifier: Modifier = Modifier,
) {
    val viewModel: HistoryViewModel = viewModel(
        factory = HistoryViewModel.factory(source),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    HistoryContent(state = state, modifier = modifier)
}

@Composable
private fun HistoryContent(
    state: HistoryUiState,
    modifier: Modifier = Modifier,
) {
    when (state) {
        HistoryUiState.Loading -> Centered(modifier) {
            CircularProgressIndicator()
        }

        is HistoryUiState.Failed -> Centered(modifier) {
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

        is HistoryUiState.Loaded -> LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.challenge_history_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                )
            }

            if (state.history.isEmpty()) {
                item { EmptyNote(stringResource(R.string.challenge_history_empty)) }
            } else {
                items(state.history, key = { it.recordId }) { entry -> HistoryRow(entry) }
            }

            item { SectionHeader(stringResource(R.string.challenge_best_title)) }
            if (state.personalBests.isEmpty()) {
                item { EmptyNote(stringResource(R.string.challenge_best_empty)) }
            } else {
                items(state.personalBests, key = { it.datasetId }) { best -> BestRow(best) }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun HistoryRow(entry: ChallengeHistoryEntry) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = entry.datasetDisplayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = seasonStatusLabel(entry.status),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        MetricRow(
            label = pluralStringResource(
                R.plurals.challenge_attempts,
                entry.attemptOrdinal,
                entry.attemptOrdinal,
            ),
            value = challengeTitleLabel(entry.title),
        )
        MetricRow(
            label = stringResource(R.string.challenge_trading_days),
            value = entry.tradingDayCount.toString(),
        )
        MetricRow(
            label = challengeOutcomeLabel(entry.outcome),
            value = stringResource(
                R.string.challenge_points_of_max,
                entry.score.total,
                ChallengeScoreWeights.TOTAL_POINTS,
            ),
        )
        MetricRow(
            label = stringResource(R.string.challenge_profit),
            value = ChallengeFormat.money(entry.profitMicros),
        )
    }
}

@Composable
private fun BestRow(best: DatasetBest) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            text = best.datasetDisplayName,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        MetricRow(
            label = stringResource(R.string.challenge_best_overall),
            value = bestSummary(best.bestOverall),
        )
        val first = best.bestFirstAttempt
        if (first != null) {
            MetricRow(
                label = stringResource(R.string.challenge_best_first_attempt),
                value = bestSummary(first),
            )
        }
    }
}

@Composable
private fun bestSummary(entry: ChallengeHistoryEntry): String {
    val score = stringResource(
        R.string.challenge_points_of_max,
        entry.score.total,
        ChallengeScoreWeights.TOTAL_POINTS,
    )
    return "$score · ${ChallengeFormat.money(entry.profitMicros)}"
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
private fun Centered(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        content()
    }
}

