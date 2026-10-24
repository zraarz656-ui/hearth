package com.tradequest.app.feature.news

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tradequest.app.core.challenge.BlindClock
import com.tradequest.app.core.challenge.BlindLabel
import com.tradequest.app.core.news.NewsRelease
import com.tradequest.app.core.simulation.warp.WarpPermission
import com.tradequest.app.core.simulation.warp.WarpTarget
import com.tradequest.app.core.simulation.calendar.NewYorkTradingCalendar
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the News calendar is showing. */
sealed interface NewsCalendarState {
    /** The first load has not finished. */
    data object Loading : NewsCalendarState

    /** No season is running, so there is no schedule to project. */
    data object NoSeason : NewsCalendarState

    /** The season's dataset has no releases. */
    data object Empty : NewsCalendarState

    data class Loaded(
        val events: List<NewsEventRow>,
        /** The simulated instant the rows were projected against. */
        val simUtcMs: Long,
        /** The season's start, the origin of the blind clock. */
        val seasonStartUtcMs: Long,
        /** True when Blind Mode hides absolute dates (Step 12.5). */
        val blind: Boolean,
        /** The dataset label to show, already masked when [blind]. */
        val datasetLabel: String,
    ) : NewsCalendarState

    /** The schedule could not be read. */
    data class Failed(val message: String) : NewsCalendarState
}

/**
 * One calendar row, with the fields Step 12.4 permits at the projected instant.
 *
 * # Why this is not a [NewsRelease]
 *
 * [NewsRelease] is the domain's projection and carries the outcome on its
 * `Released` variant. A UI row needs the outcome *as a display string*, or a note
 * that it is not yet available, and it needs the time already formatted for Blind
 * Mode. Building that here means the composable does no projection of its own and
 * cannot accidentally render a field it was not given.
 *
 * [actual] and [reactionNote] are therefore null not only when the value is absent
 * but when it is not yet visible — which is the correct thing for the UI to know,
 * and it never learns the difference.
 */
data class NewsEventRow(
    val newsId: Long,
    /** Time of day in UTC, e.g. `14:30`. Always shown. */
    val timeOfDay: String,
    /** The absolute date, or null in Blind Mode (Step 12.5). */
    val absoluteDate: String?,
    /** The blind coordinate, e.g. `W2 D3 14:30`, or null outside Blind Mode. */
    val blindCoordinate: String?,
    val headline: String,
    val categoryLabel: String,
    val impact: com.tradequest.app.core.news.NewsImpact,
    val forecast: String?,
    val previous: String?,
    /** The released figure, or null when it is not yet visible. */
    val actual: String?,
    /** The reaction note, or null when its window has not elapsed. */
    val reactionNote: String?,
    /** True once the release has happened at the projected instant. */
    val isReleased: Boolean,
)

/**
 * Backs the News calendar (Steps 12.2-12.7).
 *
 * Reads only through [NewsScheduleSource], so the point-in-time rule and the warp
 * restrictions stay on the domain side of the boundary.
 */
class NewsViewModel(
    private val source: NewsScheduleSource,
) : ViewModel() {

    private val _state = MutableStateFlow<NewsCalendarState>(NewsCalendarState.Loading)
    val state: StateFlow<NewsCalendarState> = _state.asStateFlow()

    /** The last warp result to show, or null when there is nothing to say. */
    private val _warpMessage = MutableStateFlow<String?>(null)
    val warpMessage: StateFlow<String?> = _warpMessage.asStateFlow()

    /** The current warp permission, so the screen can disable the buttons (12.7). */
    private val _warpPermission = MutableStateFlow<WarpPermission>(WarpPermission.Allowed)
    val warpPermission: StateFlow<WarpPermission> = _warpPermission.asStateFlow()

    init {
        load()
    }

    /**
     * Loads the schedule for the running season at the current simulated instant.
     *
     * Called on creation and after a warp, so the rows always reflect the instant
     * the clock is actually at. Reloading rather than mutating the rows in place is
     * what makes a warp reveal an actual: the new projection simply has one.
     */
    fun load() {
        viewModelScope.launch {
            try {
                val datasetId = source.activeDatasetId()
                if (datasetId == null) {
                    _state.value = NewsCalendarState.NoSeason
                    return@launch
                }
                val bounds = source.datasetBounds(datasetId)
                val simUtcMs = source.currentSimUtcMs()
                val blind = source.isBlind()
                if (bounds == null || simUtcMs == null) {
                    _state.value = NewsCalendarState.NoSeason
                    return@launch
                }
                val events = source.releasesAt(
                    datasetId = datasetId,
                    fromUtcMs = bounds.first,
                    toUtcMs = bounds.second + ONE_MINUTE_MS,
                    simUtcMs = simUtcMs,
                )
                _warpPermission.value = source.warpPermission()
                if (events.isEmpty()) {
                    _state.value = NewsCalendarState.Empty
                    return@launch
                }
                _state.value = NewsCalendarState.Loaded(
                    events = events.map { it.toRow(simUtcMs, bounds.first, blind) },
                    simUtcMs = simUtcMs,
                    seasonStartUtcMs = bounds.first,
                    blind = blind,
                    datasetLabel = if (blind) BlindLabel.forDataset(datasetId) else datasetId,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _state.value = NewsCalendarState.Failed(
                    error.message ?: "Could not read the news schedule",
                )
            }
        }
    }

    /**
     * Resolves and performs a warp (Steps 12.6, 12.7).
     *
     * The permission is re-read immediately before resolving, because the player
     * may have opened a position since the screen loaded, and warping on a stale
     * permission is exactly what Step 12.7 forbids.
     */
    fun warp(kind: WarpKind) {
        viewModelScope.launch {
            try {
                val permission = source.warpPermission()
                _warpPermission.value = permission
                if (permission is WarpPermission.RefusedExposure) {
                    _warpMessage.value = permission.reason
                    return@launch
                }
                val target = source.resolveWarp(kind)
                if (target == null || target.atUtcMs == null) {
                    _warpMessage.value = when {
                        target == null -> "Start a season before using Time Warp."
                        target.isBeyondDataset -> "There is nothing further in this dataset."
                        else -> "There is nothing to warp to from here."
                    }
                    return@launch
                }
                val reached = source.performWarp(target)
                _warpMessage.value = if (reached == null) {
                    "The warp did not move the clock."
                } else {
                    null
                }
                load()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _warpMessage.value = error.message ?: "The warp failed."
            }
        }
    }

    /** Clears the warp message once the screen has shown it. */
    fun dismissWarpMessage() {
        _warpMessage.value = null
    }

    companion object {
        private const val ONE_MINUTE_MS = 60_000L

        fun factory(source: NewsScheduleSource): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    NewsViewModel(source) as T
            }
    }
}

/**
 * Projects one release into a display row (Steps 12.4, 12.5).
 *
 * The absolute date is emitted only outside Blind Mode, and the blind coordinate
 * only inside it, so the row never carries both and a screen cannot show the wrong
 * one by accident.
 */
internal fun NewsRelease.toRow(
    simUtcMs: Long,
    seasonStartUtcMs: Long,
    blind: Boolean,
): NewsEventRow {
    val instant = Instant.ofEpochMilli(scheduledUtcMs)
        .atZone(ZoneOffset.UTC)
    val hh = instant.hour.toString().padStart(2, '0')
    val mm = instant.minute.toString().padStart(2, '0')
    val dayIndex = BlindClock.tradingDayIndex(
        startUtcMs = seasonStartUtcMs,
        atUtcMs = scheduledUtcMs,
        isTradingDay = { NewYorkTradingCalendar.isMarketOpen(it) },
    )
    return NewsEventRow(
        newsId = newsId,
        timeOfDay = "$hh:$mm",
        // In Blind Mode the absolute date is omitted rather than blanked, so a
        // composable that renders `absoluteDate` unconditionally shows nothing
        // instead of an empty line that hints a date exists.
        absoluteDate = if (blind) null else instant.toLocalDate().toString(),
        blindCoordinate = if (blind) BlindClock.format(scheduledUtcMs, dayIndex) else null,
        headline = headline,
        categoryLabel = category.label,
        impact = impact,
        forecast = forecast,
        previous = previous,
        actual = (this as? NewsRelease.Released)?.actual,
        reactionNote = (this as? NewsRelease.Released)?.reactionNote,
        isReleased = isReleased,
    )
}
