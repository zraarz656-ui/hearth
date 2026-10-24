package com.tradequest.app.feature.news

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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tradequest.app.R
import com.tradequest.app.core.news.NewsImpact
import com.tradequest.app.core.simulation.warp.WarpPermission

/**
 * Economic calendar (Steps 12.2-12.7).
 *
 * Lists the season's scheduled releases and shows a detail dialog for the selected
 * one. Before a release the detail shows the name, the scheduled time, the forecast
 * and the previous reading, and says the actual is not out yet; after it, the actual
 * appears and the reaction note appears once its price window has elapsed.
 *
 * That split is not implemented here. The ViewModel is handed releases that already
 * cannot carry a hidden outcome, so this screen has no visibility logic to get wrong.
 *
 * Time Warp lives on this screen because the plan groups it with the news phase and
 * because "next high-impact news" is one of its four destinations, so the control sits
 * beside the schedule it reasons about.
 */
@Composable
fun NewsScreen(
    source: NewsScheduleSource,
    modifier: Modifier = Modifier,
) {
    val viewModel: NewsViewModel = viewModel(factory = NewsViewModel.factory(source))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val warpMessage by viewModel.warpMessage.collectAsStateWithLifecycle()
    val permission by viewModel.warpPermission.collectAsStateWithLifecycle()

    NewsContent(
        state = state,
        warpMessage = warpMessage,
        permission = permission,
        onWarp = viewModel::warp,
        onDismissWarpMessage = viewModel::dismissWarpMessage,
        modifier = modifier,
    )
}

@Composable
private fun NewsContent(
    state: NewsCalendarState,
    warpMessage: String?,
    permission: WarpPermission,
    onWarp: (WarpKind) -> Unit,
    onDismissWarpMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf<NewsEventRow?>(null) }
    var filter by remember { mutableStateOf(ImpactFilter.ALL) }

    Box(modifier.fillMaxSize()) {
        when (state) {
            NewsCalendarState.Loading -> CentredMessage { CircularProgressIndicator() }

            NewsCalendarState.NoSeason -> CentredMessage {
                Icon(Icons.Filled.Info, contentDescription = null)
                MessageTitle(stringResource(R.string.news_no_season_title))
                MessageBody(stringResource(R.string.news_no_season_body))
            }

            NewsCalendarState.Empty -> CentredMessage {
                Icon(Icons.Filled.Info, contentDescription = null)
                MessageTitle(stringResource(R.string.news_empty_title))
                MessageBody(stringResource(R.string.news_empty_body))
            }

            is NewsCalendarState.Failed -> CentredMessage {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                MessageTitle(stringResource(R.string.news_error_title))
                MessageBody(state.message)
            }

            is NewsCalendarState.Loaded -> LoadedCalendar(
                state = state,
                filter = filter,
                onFilterChange = { filter = it },
                onSelect = { selected = it },
                onWarp = onWarp,
                permission = permission,
            )
        }

        if (warpMessage != null) {
            AlertDialog(
                onDismissRequest = onDismissWarpMessage,
                title = { Text(stringResource(R.string.warp_blocked_title)) },
                text = { Text(warpMessage) },
                confirmButton = {
                    TextButton(onClick = onDismissWarpMessage) {
                        Text(stringResource(R.string.news_detail_close))
                    }
                },
            )
        }

        selected?.let { row ->
            NewsDetailDialog(row = row, onDismiss = { selected = null })
        }
    }
}

/** Which impacts the list shows. */
private enum class ImpactFilter { ALL, HIGH, MEDIUM }

@Composable
private fun LoadedCalendar(
    state: NewsCalendarState.Loaded,
    filter: ImpactFilter,
    onFilterChange: (ImpactFilter) -> Unit,
    onSelect: (NewsEventRow) -> Unit,
    onWarp: (WarpKind) -> Unit,
    permission: WarpPermission,
) {
    val visible = remember(state.events, filter) {
        state.events.filter { row ->
            when (filter) {
                ImpactFilter.ALL -> true
                ImpactFilter.HIGH -> row.impact == NewsImpact.HIGH
                ImpactFilter.MEDIUM -> row.impact == NewsImpact.MEDIUM
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        CalendarHeader(state = state)
        WarpBar(permission = permission, onWarp = onWarp)
        FilterRow(filter = filter, onFilterChange = onFilterChange)
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(visible, key = { it.newsId }) { row ->
                NewsRow(row = row, onClick = { onSelect(row) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun CalendarHeader(state: NewsCalendarState.Loaded) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.news_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (state.blind) {
                AssistChip(
                    onClick = {},
                    label = { Text(stringResource(R.string.news_blind_badge)) },
                )
            }
        }
        Text(
            text = state.datasetLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.blind) {
            Text(
                text = stringResource(R.string.news_blind_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * The four warps of Step 12.6, disabled when Step 12.7 refuses.
 *
 * A refusal is shown as a hint under the row rather than by hiding the buttons: the
 * player should be able to see that Time Warp exists and why it is currently
 * unavailable, which is more useful than a control that vanishes.
 */
@Composable
private fun WarpBar(permission: WarpPermission, onWarp: (WarpKind) -> Unit) {
    val enabled = permission is WarpPermission.Allowed
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(R.string.warp_title),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            WarpChip(
                label = stringResource(R.string.warp_skip_weekend),
                enabled = enabled,
                onClick = { onWarp(WarpKind.SKIP_WEEKEND) },
            )
            WarpChip(
                label = stringResource(R.string.warp_next_london),
                enabled = enabled,
                onClick = { onWarp(WarpKind.NEXT_LONDON) },
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            WarpChip(
                label = stringResource(R.string.warp_next_new_york),
                enabled = enabled,
                onClick = { onWarp(WarpKind.NEXT_NEW_YORK) },
            )
            WarpChip(
                label = stringResource(R.string.warp_next_news),
                enabled = enabled,
                onClick = { onWarp(WarpKind.NEXT_HIGH_IMPACT_NEWS) },
            )
        }
        if (!enabled) {
            Text(
                text = stringResource(R.string.warp_practice_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun WarpChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = false,
        enabled = enabled,
        onClick = onClick,
        label = { Text(label) },
    )
}

@Composable
private fun FilterRow(filter: ImpactFilter, onFilterChange: (ImpactFilter) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        FilterChip(
            selected = filter == ImpactFilter.ALL,
            onClick = { onFilterChange(ImpactFilter.ALL) },
            label = { Text(stringResource(R.string.news_filter_all)) },
        )
        FilterChip(
            selected = filter == ImpactFilter.HIGH,
            onClick = { onFilterChange(ImpactFilter.HIGH) },
            label = { Text(stringResource(R.string.news_filter_high)) },
        )
        FilterChip(
            selected = filter == ImpactFilter.MEDIUM,
            onClick = { onFilterChange(ImpactFilter.MEDIUM) },
            label = { Text(stringResource(R.string.news_filter_medium)) },
        )
    }
}

/**
 * One calendar row.
 *
 * The time shown is the blind coordinate in Blind Mode and the absolute date
 * otherwise. Both come from the row and only one is ever non-null, so there is no
 * branch here that could pick the wrong one.
 */
@Composable
private fun NewsRow(row: NewsEventRow, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        ImpactBar(impact = row.impact)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = row.headline,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = buildString {
                    append(row.absoluteDate ?: row.blindCoordinate.orEmpty())
                    append("  ")
                    append(row.timeOfDay)
                    append(" UTC")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = buildString {
                    append(row.categoryLabel)
                    append(" · ")
                    append(impactLabel(row.impact))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusTag(isReleased = row.isReleased)
    }
}

/**
 * The red / amber / grey marker beside a row (Step 12.3).
 *
 * Red for high and amber for medium, as the plan specifies. Low and unknown share a
 * neutral grey: an unknown impact is not drawn as low, because that would assert
 * something the data does not say.
 */
@Composable
private fun ImpactBar(impact: NewsImpact) {
    val colour = when (impact) {
        NewsImpact.HIGH -> Color(0xFFE53935)
        NewsImpact.MEDIUM -> Color(0xFFFFB300)
        NewsImpact.LOW -> Color(0xFF9E9E9E)
        NewsImpact.UNKNOWN -> Color(0xFFBDBDBD)
    }
    Box(
        Modifier
            .width(4.dp)
            .height(36.dp)
            .background(colour, RoundedCornerShape(2.dp)),
    )
}

@Composable
private fun StatusTag(isReleased: Boolean) {
    Surface(
        color = if (isReleased) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text = stringResource(
                if (isReleased) R.string.news_released else R.string.news_scheduled,
            ),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * The release detail (Step 12.4).
 *
 * Before release: name, time, forecast and previous, and an explicit line saying the
 * actual is not out yet — an empty field would read as "no data" rather than "not
 * yet", which is a different thing. After release: the actual, and the reaction note
 * if its window has elapsed.
 */
/**
 * The release detail, for a caller outside this feature (Steps 12.3, 12.4).
 *
 * A named entry point rather than making [NewsDetailDialog] public: the chart's tap
 * opens the same dialog the calendar does, so there is one place that decides what a
 * release reveals. A second renderer for the chart would be a second chance to leak
 * an actual.
 */
@Composable
fun NewsReleaseDetailDialog(row: NewsEventRow, onDismiss: () -> Unit) =
    NewsDetailDialog(row = row, onDismiss = onDismiss)

@Composable
private fun NewsDetailDialog(row: NewsEventRow, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.news_detail_title)) },
        text = {
            Column {
                Text(row.headline, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                DetailLine(
                    label = null,
                    value = "${row.absoluteDate ?: row.blindCoordinate.orEmpty()} " +
                        "${row.timeOfDay} UTC",
                )
                DetailLine(
                    label = null,
                    value = "${row.categoryLabel} · ${impactLabel(row.impact)}",
                )
                Spacer(Modifier.height(12.dp))
                DetailLine(stringResource(R.string.news_detail_forecast), row.forecast)
                DetailLine(stringResource(R.string.news_detail_previous), row.previous)
                if (row.isReleased) {
                    DetailLine(stringResource(R.string.news_detail_actual), row.actual)
                    if (row.reactionNote != null) {
                        DetailLine(
                            stringResource(R.string.news_detail_reaction),
                            row.reactionNote,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.news_detail_awaiting_window),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.news_detail_not_released),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.news_detail_close))
            }
        },
    )
}

@Composable
private fun DetailLine(label: String?, value: String?) {
    Row(Modifier.padding(vertical = 2.dp)) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(96.dp),
            )
        }
        Text(
            text = value ?: stringResource(R.string.news_detail_no_value),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun impactLabel(impact: NewsImpact): String = stringResource(
    when (impact) {
        NewsImpact.HIGH -> R.string.news_impact_high
        NewsImpact.MEDIUM -> R.string.news_impact_medium
        NewsImpact.LOW -> R.string.news_impact_low
        NewsImpact.UNKNOWN -> R.string.news_impact_unknown
    },
)

@Composable
private fun MessageTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun MessageBody(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp),
    )
}

@Composable
private fun CentredMessage(content: @Composable () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        content()
    }
}
