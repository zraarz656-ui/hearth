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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.tradequest.app.core.market.MarketFormat
import com.tradequest.app.core.market.MarketRepository

/**
 * Market Library (Step 2.6).
 *
 * Lists every imported dataset with its period, duration, candle count, news
 * count, validation status and estimated storage size, and shows a detail pane
 * for the selected dataset.
 *
 * There is deliberately no delete action here yet. Deleting a dataset that a
 * season is replaying must be refused, and that check needs the season rules
 * from Phase 3, so the action arrives with them rather than half-implemented.
 */
@Composable
fun MarketLibraryScreen(
    repository: MarketRepository,
    modifier: Modifier = Modifier,
) {
    val viewModel: MarketLibraryViewModel = viewModel(
        factory = MarketLibraryViewModel.factory(repository),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    MarketLibraryContent(
        state = state,
        onSelect = viewModel::selectDataset,
        modifier = modifier,
    )
}

@Composable
private fun MarketLibraryContent(
    state: MarketLibraryState,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        MarketLibraryState.Loading -> CentredMessage(modifier) {
            CircularProgressIndicator()
        }

        MarketLibraryState.Empty -> EmptyLibrary(modifier)

        is MarketLibraryState.Failed -> CentredMessage(modifier) {
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
            modifier = modifier.fillMaxSize(),
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
                item { DatasetDetail(selected) }
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
private fun DatasetDetail(dataset: DatasetSummary) {
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
