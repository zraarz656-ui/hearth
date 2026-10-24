package com.tradequest.app.feature.market

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tradequest.app.R
import com.tradequest.app.core.market.DatasetSummary
import com.tradequest.app.core.challenge.ChallengeLauncher
import com.tradequest.app.core.market.MarketFormat
import com.tradequest.app.core.market.MarketRepository

/**
 * Market Library (Step 2.6, Steps 11.1-11.3).
 *
 * Lists every imported dataset with its period, duration, candle count, news
 * count, validation status and estimated storage size, and shows a detail pane
 * for the selected dataset.
 *
 * The detail pane carries the "Start challenge" action: Step 11.1 says selecting a
 * dataset creates a season, and Step 11.3 requires confirmation before a running
 * challenge is abandoned, so the action is explicit rather than a side effect of
 * tapping a row. A selection is a thing to look at; starting a challenge is a thing
 * to decide.
 *
 * There is deliberately no delete action here yet. Deleting a dataset that a
 * season is replaying must be refused, and that check needs the season rules
 * from Phase 3, so the action arrives with them rather than half-implemented.
 */
@Composable
fun MarketLibraryScreen(
    repository: MarketRepository,
    launcher: ChallengeLauncher,
    modifier: Modifier = Modifier,
) {
    val viewModel: MarketLibraryViewModel = viewModel(
        factory = MarketLibraryViewModel.factory(repository, launcher),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val challengeStart by viewModel.challengeStart.collectAsStateWithLifecycle()
    val blindMode by viewModel.blindMode.collectAsStateWithLifecycle()

    MarketLibraryContent(
        state = state,
        challengeStart = challengeStart,
        blindMode = blindMode,
        onSelect = viewModel::selectDataset,
        onStartChallenge = viewModel::startChallenge,
        onBlindModeChange = viewModel::setBlindMode,
        onConfirmAbandon = viewModel::confirmAbandon,
        onDismissChallengeStart = viewModel::dismissChallengeStart,
        modifier = modifier,
    )
}

@Composable
private fun MarketLibraryContent(
    state: MarketLibraryState,
    challengeStart: ChallengeStartMessage?,
    blindMode: Boolean,
    onSelect: (String) -> Unit,
    onStartChallenge: (String) -> Unit,
    onBlindModeChange: (Boolean) -> Unit,
    onConfirmAbandon: (String) -> Unit,
    onDismissChallengeStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        when (state) {
            MarketLibraryState.Loading -> CentredMessage {
                CircularProgressIndicator()
            }

            MarketLibraryState.Empty -> EmptyLibrary()

            is MarketLibraryState.Failed -> CentredMessage {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(R.string.market_library_error_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            is MarketLibraryState.Loaded -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column {
                        Text(
                            text = stringResource(R.string.market_library_title),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = pluralStringResource(
                                R.plurals.market_library_count,
                                state.datasets.size,
                                state.datasets.size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                state.selected?.let { selected ->
                    item {
                        DatasetDetail(
                            dataset = selected,
                            blindMode = blindMode,
                            onBlindModeChange = onBlindModeChange,
                            onStartChallenge = { onStartChallenge(selected.datasetId) },
                        )
                    }
                    item {
                        Text(
                            text = stringResource(R.string.market_library_all_datasets),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }

                items(state.datasets, key = { it.datasetId }) { dataset ->
                    DatasetRow(
                        dataset = dataset,
                        isSelected = dataset.datasetId == state.selectedDatasetId,
                        onClick = { onSelect(dataset.datasetId) },
                    )
                }
            }
        }

        ChallengeStartEffect(
            message = challengeStart,
            onConfirmAbandon = onConfirmAbandon,
            onDismiss = onDismissChallengeStart,
        )
    }
}

/**
 * Renders the challenge-start prompt and messages (Steps 11.1-11.3).
 *
 * # Why the confirmation is a dialog and the rest are messages
 *
 * Only the abandonment needs an answer. A dialog with no dismiss is wrong — the
 * trader must be able to keep the running challenge — so it has both a confirm and
 * a cancel. The outcomes are informational, and are dismissed by the trader rather
 * than auto-clearing, so a message cannot vanish before it is read.
 */
@Composable
private fun ChallengeStartEffect(
    message: ChallengeStartMessage?,
    onConfirmAbandon: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    when (message) {
        null -> Unit

        is ChallengeStartMessage.ConfirmAbandon -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.challenge_confirm_abandon_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.challenge_confirm_abandon_body,
                        message.datasetDisplayName,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { onConfirmAbandon(message.datasetId) }) {
                    Text(stringResource(R.string.challenge_confirm_abandon_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.challenge_confirm_abandon_cancel))
                }
            },
        )

        else -> AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    text = stringResource(
                        if (message is ChallengeStartMessage.Started) {
                            R.string.challenge_started_title
                        } else {
                            R.string.challenge_start_failed_title
                        },
                    ),
                )
            },
            text = {
                Text(
                    text = when (message) {
                        is ChallengeStartMessage.Started ->
                            stringResource(R.string.challenge_started_body, message.datasetDisplayName)

                        is ChallengeStartMessage.DatasetNotFound ->
                            stringResource(R.string.challenge_start_no_dataset)

                        is ChallengeStartMessage.DatasetNotReplayable ->
                            stringResource(R.string.challenge_start_empty_dataset)

                        is ChallengeStartMessage.Failed -> message.message

                        is ChallengeStartMessage.ConfirmAbandon -> ""
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.challenge_start_dismiss))
                }
            },
        )
    }
}

@Composable
private fun CentredMessage(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier) {
    CentredMessage(modifier) {
        Icon(
            imageVector = Icons.Filled.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.market_library_empty_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = stringResource(R.string.market_library_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun DatasetRow(
    dataset: DatasetSummary,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = dataset.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                ValidationBadge(dataset)
            }
            Text(
                text = "${dataset.instrument}  ·  " +
                    "${MarketFormat.date(dataset.startTimestampUtcMs)} – " +
                    MarketFormat.date(dataset.endTimestampUtcMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun DatasetDetail(
    dataset: DatasetSummary,
    blindMode: Boolean,
    onBlindModeChange: (Boolean) -> Unit,
    onStartChallenge: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = dataset.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                ValidationBadge(dataset)
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            DetailRow(stringResource(R.string.market_detail_instrument), dataset.instrument)
            DetailRow(
                stringResource(R.string.market_detail_period),
                "${MarketFormat.date(dataset.startTimestampUtcMs)} – " +
                    MarketFormat.date(dataset.endTimestampUtcMs),
            )
            DetailRow(
                stringResource(R.string.market_detail_duration),
                MarketFormat.span(dataset.spanDays),
            )
            DetailRow(
                stringResource(R.string.market_detail_candles),
                MarketFormat.count(dataset.candleCount),
            )
            DetailRow(
                stringResource(R.string.market_detail_news),
                MarketFormat.count(dataset.newsCount),
            )
            DetailRow(
                stringResource(R.string.market_detail_size),
                MarketFormat.estimatedBytes(dataset.estimatedStorageBytes),
            )
            DetailRow(stringResource(R.string.market_detail_id), dataset.datasetId)

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.market_detail_blind_mode),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.market_detail_blind_mode_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = blindMode, onCheckedChange = onBlindModeChange)
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onStartChallenge,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.challenge_start_action))
            }
            Text(
                text = stringResource(R.string.challenge_start_action_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            if (dataset.hasUnexpectedGaps) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.width(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.market_detail_gaps_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
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
private fun ValidationBadge(dataset: DatasetSummary) {
    val colour = when {
        dataset.hasUnexpectedGaps -> MaterialTheme.colorScheme.error
        dataset.validationStatus == "VALID" -> SuccessGreen
        else -> MaterialTheme.colorScheme.primary
    }
    val labelRes = when {
        dataset.hasUnexpectedGaps -> R.string.market_status_gaps
        dataset.validationStatus == "VALID" -> R.string.market_status_valid
        else -> R.string.market_status_warnings
    }

    Box(
        modifier = Modifier
            .background(colour.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (dataset.hasUnexpectedGaps) {
                    Icons.Filled.Warning
                } else {
                    Icons.Filled.CheckCircle
                },
                contentDescription = null,
                tint = colour,
                modifier = Modifier.width(14.dp),
            )
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelSmall,
                color = colour,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/** Matches the chart bullish token so "valid" reads the same across screens. */
private val SuccessGreen = Color(0xFF4CAF50)
