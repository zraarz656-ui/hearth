package com.tradequest.app.feature.trading

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tradequest.app.core.chart.coordinates.PriceAxisRange
import com.tradequest.app.core.chart.renderer.ChartFrameAssembler
import com.tradequest.app.core.chart.renderer.ChartRenderState
import com.tradequest.app.core.chart.renderer.ChartStyle
import com.tradequest.app.core.chart.timeframe.Timeframe
import com.tradequest.app.core.chart.viewport.ChartViewport
import com.tradequest.app.core.market.DatasetSummary
import com.tradequest.app.core.market.HistoricalCandle
import com.tradequest.app.core.market.MarketRepository
import com.tradequest.app.core.simulation.path.MarketPathBuilder
import com.tradequest.app.core.simulation.path.MarketPathWindow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Trading screen is showing. */
sealed interface TradingState {
    /** No dataset has been imported, so there is nothing to chart. */
    data object NoDataset : TradingState

    data object Loading : TradingState

    /** A dataset is charted. */
    data class Ready(
        val dataset: DatasetSummary,
        val timeframe: Timeframe,
        /**
         * The one value Compose observes. Replaced wholesale on a timeframe or
         * dataset change; a pan only changes the viewport inside it.
         */
        val renderState: ChartRenderState,
    ) : TradingState

    data class Failed(val message: String) : TradingState
}

/**
 * Backs the Trading screen (Phase 8).
 *
 * # What it does
 *
 * Loads one dataset's minutes, aggregates them to the selected timeframe, builds
 * the path window, and hands the chart a [ChartRenderState]. It reads through
 * [MarketRepository] only, so the dataset-isolation rules cannot be bypassed.
 *
 * # Why the whole series is loaded once
 *
 * Step 8.4 requires culling at draw time, not at read time, so the chart can pan
 * without a database round-trip per frame. The repository bounds a single read at
 * 200,000 rows, which covers a full dataset, and `AGENTS.md` asks that the chart
 * hold its data rather than re-query per frame.
 *
 * # The forming candle
 *
 * When [sessions] reports a running season, the chart is live: the buffer holds
 * only candles completed before the simulated instant and a forming candle is
 * derived from the path window, so the newest bar grows as the clock advances.
 * With no season it is a plain historical chart. Both go through
 * [ChartFrameAssembler], so the reveal boundary is enforced in one tested place
 * rather than here.
 *
 * # Why the tick re-reads the clock but not the dataset
 *
 * The clock advances continuously; the dataset does not change during a season.
 * So the tick loop only re-derives the forming candle and re-aggregates when the
 * bucket boundary has been crossed. Re-reading the dataset every frame would be
 * a database query per frame, which is exactly what the buffer exists to avoid.
 */
class TradingViewModel(
    private val repository: MarketRepository,
    private val style: ChartStyle,
    private val sessions: TradingSessionSource,
    /**
     * Where database reads run.
     *
     * Injected so a test can drive the whole screen deterministically: a test
     * scheduler only controls dispatchers it owns, so the real `Dispatchers.IO`
     * would run the load on a thread the test cannot wait for.
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Where aggregation runs. Injected for the same reason as [ioDispatcher]. */
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _state = MutableStateFlow<TradingState>(TradingState.Loading)
    val state: StateFlow<TradingState> = _state.asStateFlow()

    private var timeframe: Timeframe = Timeframe.H1
    private var candles: List<HistoricalCandle> = emptyList()
    private var window: MarketPathWindow? = null
    private var dataset: DatasetSummary? = null

    /**
     * The viewport the user has panned or zoomed to.
     *
     * The chart owns its viewport during a gesture, so this is only the seed for
     * the next frame. It is remembered so a clock tick, which republishes the
     * whole state, does not snap the chart back to its opening position.
     */
    private var lastViewport: ChartViewport? = null

    /** The bucket boundary the current buffer was built for. See the tick loop. */
    private var lastBucketStart: Long? = null

    /** The poll loop, held so a refresh cannot start a second one. */
    private var tickJob: Job? = null

    init {
        viewModelScope.launch { load() }
    }

    /**
     * Loads the dataset the running season uses, or the first dataset when there
     * is no season.
     *
     * The season's dataset is preferred because a live chart must show the series
     * the season replays. Charting a different one would show the trader prices
     * from a series they never chose.
     */
    private suspend fun load() {
        _state.value = TradingState.Loading
        try {
            val binding = withContext(ioDispatcher) { sessions.currentSeason() }
            val datasets = withContext(ioDispatcher) { repository.listDatasets() }

            val summary = binding
                ?.let { b -> datasets.firstOrNull { it.datasetId == b.datasetId } }
                ?: datasets.firstOrNull()

            if (summary == null) {
                _state.value = TradingState.NoDataset
                return
            }
            dataset = summary
            rebuild(summary)
            startFollowingClock()
        } catch (error: Exception) {
            _state.value = TradingState.Failed(error.message ?: "Could not load the chart")
        }
    }

    /** Re-reads and re-aggregates the active dataset. */
    private suspend fun rebuild(summary: DatasetSummary) {
        val bounds = withContext(ioDispatcher) { repository.timeBounds(summary.datasetId) }
        if (bounds == null) {
            _state.value = TradingState.Failed("The dataset has no candles")
            return
        }

        // The exclusive end is one minute past the last candle.
        val loaded = withContext(ioDispatcher) {
            repository.candlesInRange(
                datasetId = summary.datasetId,
                fromUtcMs = bounds.first,
                toUtcMs = bounds.second + ONE_MINUTE_MS,
            )
        }
        candles = loaded
        window = MarketPathWindow(summary.datasetId, MarketPathBuilder.build(summary.datasetId, loaded))
        publish(summary)
    }

    /**
     * Follows the simulation clock while a season runs (Step 8.10).
     *
     * # Why the cadence is not here
     *
     * The instants arrive from [TradingSessionSource.simInstants] rather than a
     * `delay` loop in the ViewModel. `SimulationClock` has no ticker of its own, so
     * the polling has to happen somewhere, but a perpetual loop here would make
     * every unit test hang: a test scheduler never reaches idle while a `delay` is
     * outstanding. Keeping the cadence behind the port leaves the ViewModel a pure
     * consumer of instants, which a test can emit one at a time.
     *
     * The flow completes when no season is running, which ends the collection.
     * That is why there is no separate "is a season running" check.
     */
    private fun startFollowingClock() {
        if (tickJob?.isActive == true) return
        tickJob = viewModelScope.launch {
            sessions.simInstants().collect { simMs ->
                refreshFormingCandle(simMs)
            }
        }
    }

    /**
     * Republishes the frame for the current instant.
     *
     * Re-aggregates only when the bucket boundary has been crossed, because that
     * is the only moment the revealed buffer changes. Within a bucket the buffer
     * is untouched and only the forming candle moves, so a tick costs one small
     * object rather than a copy of the series.
     */
    private suspend fun refreshFormingCandle(simMs: Long) {
        val summary = dataset ?: return
        val bucketStart = timeframe.bucketStartUtcMs(simMs)

        if (bucketStart != lastBucketStart) {
            lastBucketStart = bucketStart
            publish(summary)
            return
        }

        val current = _state.value as? TradingState.Ready ?: return
        // Prefer the viewport the user most recently scrolled to. The one on the
        // state is only updated when a frame is published, so a gesture since the
        // last publish would otherwise be ignored and the chart would jump back.
        val viewport = lastViewport ?: current.renderState.viewport
        val rebuilt = withContext(computeDispatcher) {
            ChartFrameAssembler.build(
                candles = candles,
                timeframe = timeframe,
                style = style,
                window = window,
                simulatedUtcMs = simMs,
                viewport = viewport,
                fallback = LAST_RESORT_RANGE,
            )
        }

        // Skip the state write when nothing moved, so a still minute does not
        // invalidate Compose on every tick.
        if (rebuilt.formingCandle == current.renderState.formingCandle) return
        lastViewport = rebuilt.viewport
        _state.value = TradingState.Ready(summary, timeframe, rebuilt)
    }

    /** Switches the charted timeframe, re-aggregating from the loaded minutes. */
    fun selectTimeframe(next: Timeframe) {
        if (next == timeframe) return
        timeframe = next
        // The viewport was fitted to the old timeframe's bar count, so it is no
        // longer meaningful; the next frame opens at the live edge instead.
        lastViewport = null
        lastBucketStart = null
        val summary = dataset ?: return
        // Aggregation over 200,000 minutes is real work, so it is off the main
        // thread even though it is CPU-bound rather than IO.
        viewModelScope.launch { publish(summary) }
    }

    /** Re-reads the dataset, for a pull-to-refresh after an import. */
    fun refresh() {
        viewModelScope.launch { load() }
    }

    /**
     * Records the viewport the user has scrolled to.
     *
     * Called by the chart after a gesture, so a clock tick does not snap the view
     * back to the live edge while the trader is looking at older candles.
     *
     * The price axis is *not* recomputed here. Auto-scale fits the axis to the
     * visible extremes, and the chart re-fits it at draw time from the viewport it
     * is about to draw (see `CandlestickChart`). Doing it here instead would mean a
     * coroutine and a state write per gesture frame — about sixty a second — whose
     * completions can arrive out of order and briefly show the previous frame's
     * scale.
     */
    fun onViewportChanged(viewport: ChartViewport) {
        lastViewport = viewport
    }

    /**
     * Builds the render state for the current dataset, timeframe and instant.
     *
     * A suspend function because aggregation runs on a background dispatcher.
     */
    private suspend fun publish(summary: DatasetSummary) {
        val simMs = sessions.currentSimUtcMs()
        lastBucketStart = simMs?.let { timeframe.bucketStartUtcMs(it) }

        val viewport = lastViewport ?: ChartViewport.DEFAULT.scrolledToEnd(openingBarCount(simMs))

        val renderState = withContext(computeDispatcher) {
            ChartFrameAssembler.build(
                candles = candles,
                timeframe = timeframe,
                style = style,
                window = window,
                simulatedUtcMs = simMs,
                viewport = viewport,
                fallback = LAST_RESORT_RANGE,
            )
        }
        lastViewport = renderState.viewport

        _state.value = TradingState.Ready(
            dataset = summary,
            timeframe = timeframe,
            renderState = renderState,
        )
    }

    /**
     * A rough bar count for the opening viewport, before the buffer is built.
     *
     * Only used to seed the scroll position; [ChartFrameAssembler] clamps it to
     * the real bar count. A live chart's bar count is the number of revealed
     * buckets, which is not worth computing twice.
     */
    private fun openingBarCount(simMs: Long?): Int {
        if (simMs == null) return candles.size
        val plan = com.tradequest.app.core.chart.renderer.ChartRevealPlanner
            .plan(candles, timeframe, simMs)
        return plan.revealed.size
    }

    companion object {
        private const val ONE_MINUTE_MS: Long = 60_000L

        /** Used only when a dataset somehow yields no visible candle. */
        private val LAST_RESORT_RANGE = PriceAxisRange.around(2_050_000f)

        fun factory(
            repository: MarketRepository,
            style: ChartStyle,
            sessions: TradingSessionSource,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    TradingViewModel(repository, style, sessions) as T
            }
    }
}
