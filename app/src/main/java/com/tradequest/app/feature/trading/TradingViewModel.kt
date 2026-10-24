package com.tradequest.app.feature.trading

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tradequest.app.core.challenge.ChallengeDashboardState
import com.tradequest.app.core.chart.coordinates.PriceAxisRange
import com.tradequest.app.core.chart.renderer.ChartFrameAssembler
import com.tradequest.app.core.chart.renderer.ChartRenderState
import com.tradequest.app.core.chart.renderer.ChartStyle
import com.tradequest.app.core.chart.renderer.NewsFlagHit
import com.tradequest.app.core.chart.renderer.OverlayLineHit
import com.tradequest.app.core.chart.renderer.OverlayLineHitTester
import com.tradequest.app.core.chart.renderer.OverlayLineKind
import com.tradequest.app.core.chart.timeframe.Timeframe
import com.tradequest.app.core.chart.viewport.ChartTimeframeAnchor
import com.tradequest.app.core.chart.viewport.ChartViewport
import com.tradequest.app.core.market.DatasetSummary
import com.tradequest.app.core.market.HistoricalCandle
import com.tradequest.app.core.market.MarketRepository
import com.tradequest.app.core.news.NewsRelease
import com.tradequest.app.core.accounting.CommissionSchedule
import com.tradequest.app.core.risk.RiskLock
import com.tradequest.app.core.risk.RiskLockBps
import com.tradequest.app.core.simulation.path.MarketPathBuilder
import com.tradequest.app.core.simulation.path.MarketPathWindow
import com.tradequest.app.core.trading.AccountState
import com.tradequest.app.core.trading.BracketMath
import com.tradequest.app.core.trading.BracketPlan
import com.tradequest.app.core.trading.CommandResult
import com.tradequest.app.core.trading.ContractSpec
import com.tradequest.app.core.trading.Modification
import com.tradequest.app.core.trading.OrderSide
import com.tradequest.app.core.trading.OrderType
import com.tradequest.app.core.risk.PositionSizeResult
import com.tradequest.app.core.trading.SpreadModel
import com.tradequest.app.core.trading.TradeDirection
import com.tradequest.app.core.trading.TradeOverlay
import com.tradequest.app.core.trading.TradingCommand
import com.tradequest.app.core.trading.TradingEvent
import com.tradequest.app.feature.news.NewsEventRow
import com.tradequest.app.feature.news.NewsFlagMapper
import com.tradequest.app.feature.news.NewsScheduleSource
import com.tradequest.app.feature.news.toRow
import kotlinx.coroutines.CancellationException
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
    /**
     * Where the chart's news flags come from (Steps 12.2-12.4).
     *
     * The News feature's port, not `MarketRepository`, for the same reason the News
     * screen uses it: the flags must be *projected* releases, so an unreleased event
     * cannot carry its outcome onto the chart. Handing this ViewModel the repository
     * would let it read raw events and decide visibility itself, which is the one
     * decision the projection exists to centralise.
     */
    private val newsSource: NewsScheduleSource? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<TradingState>(TradingState.Loading)
    val state: StateFlow<TradingState> = _state.asStateFlow()

    private var timeframe: Timeframe = Timeframe.H1
    private var candles: List<HistoricalCandle> = emptyList()
    private var window: MarketPathWindow? = null
    private var dataset: DatasetSummary? = null

    /**
     * The viewport the chart last showed.
     *
     * The chart owns its viewport during a gesture, so this is only the seed for
     * the next frame. It is remembered so a clock tick, which republishes the
     * whole state, does not snap the chart back to its opening position.
     *
     * Written by [onViewportChanged] *and* by [publish], so it is "the last
     * viewport", not "the viewport the user chose". [userAdjustedViewport] is what
     * distinguishes the two.
     */
    private var lastViewport: ChartViewport? = null

    /**
     * Whether [lastViewport] came from a gesture rather than from a publish.
     *
     * A timeframe switch carries the centre *moment* across so the trader keeps
     * looking at the same session (Step 9.9). That is only meaningful for a window
     * the user chose: a trader who never scrolled is at the live edge, and the
     * switch should leave them there rather than pinning them to the centre of an
     * opening window they never picked. Without this flag the centre is carried
     * from whatever the opening view happened to be.
     *
     * The distinction was invisible until the opening view was fitted to short
     * series. Before that, an opening window of 120 bars over a 10-bar hourly series
     * put the centre index outside the buffer, `centreTimestamp` returned null, and
     * the live-edge fallback ran by accident. Fitting the opening view made the
     * centre valid, which exposed it.
     */
    private var userAdjustedViewport: Boolean = false

    /** The bucket boundary the current buffer was built for. See the tick loop. */
    private var lastBucketStart: Long? = null

    /**
     * The moment to keep on screen across a timeframe switch (Step 9.9).
     *
     * Captured in [selectTimeframe] from the old buffer and consumed by the next
     * [publish]. Null means there was nothing to carry over, so the chart opens at
     * the live edge.
     */
    private var pendingCentreUtcMs: Long? = null

    /** The poll loop, held so a refresh cannot start a second one. */
    private var tickJob: Job? = null

    /**
     * The live trading session, when a season is running (Phase 10).
     *
     * Null on a historical chart, where there is no clock to trade against and the
     * trade controls are hidden.
     */
    private var liveSession: LiveTradingSession? = null

    private val _panel = MutableStateFlow(TradingPanelState())
    /** The trading panel's state: account, positions, ticket and messages. */
    val panel: StateFlow<TradingPanelState> = _panel.asStateFlow()

    private val _ticket = MutableStateFlow<OrderTicket?>(null)
    /** The open order ticket, or null when it is closed (Step 10.2). */
    val ticket: StateFlow<OrderTicket?> = _ticket.asStateFlow()

    private val _sheet = MutableStateFlow(TradingSheet.NONE)
    /** Which trading sheet is showing over the chart. */
    val sheet: StateFlow<TradingSheet> = _sheet.asStateFlow()

    private val _challenge = MutableStateFlow<ChallengeDashboardState?>(null)
    /**
     * The running season's challenge figures (Step 11.8).
     *
     * Null when no season is running, or when it has not been evaluated yet. The
     * dashboard reads this; the trading panel does not, so a failure here cannot
     * affect trading.
     */
    val challenge: StateFlow<ChallengeDashboardState?> = _challenge.asStateFlow()

    /**
     * The release a tapped flag opened, or null when no detail is showing
     * (Step 12.4).
     *
     * A [NewsEventRow] rather than a [NewsRelease], so the dialog is built from the
     * same projection the calendar shows. The row already has the outcome withheld
     * when it is not yet visible, so the chart cannot reveal more than the calendar.
     */
    private val _newsDetail = MutableStateFlow<NewsEventRow?>(null)
    val newsDetail: StateFlow<NewsEventRow?> = _newsDetail.asStateFlow()

    /**
     * The projected releases for the charted dataset, held so a tap can resolve a
     * flag to its release.
     *
     * The flag itself carries only a bar index and a headline — enough to draw and
     * to name, but not the release. Keeping the projection here means the tap is
     * resolved against the very list the flags were built from, so a tapped flag
     * cannot name a different event than the one drawn.
     */
    private var newsReleases: List<NewsRelease> = emptyList()

    /** The origin of the blind clock: the dataset's first minute (Step 12.5). */
    private var newsSeasonStartUtcMs: Long? = null

    /** Whether the running season hides absolute dates (Step 12.5). */
    private var newsBlind: Boolean = false

    /**
     * The charted dataset's time bounds, cached from the load.
     *
     * Held so a re-projection does not re-query them: the clock crosses a bucket
     * boundary once per bar, and the bounds of a dataset never change, so asking
     * the database each time would be a read per bar for an answer already known.
     */
    private var newsBounds: Pair<Long, Long>? = null

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
            openLiveSession()
        } catch (error: Exception) {
            _state.value = TradingState.Failed(error.message ?: "Could not load the chart")
        }
    }

    /**
     * Opens the live trading session, if a season is running (Step 10.1).
     *
     * # Why this is separate from [load]
     *
     * The chart is useful without a session — a trader can study a dataset as plain
     * history — so a failure or absence here must not fail the screen. It only
     * decides whether the trade controls appear.
     */
    private suspend fun openLiveSession() {
        val session = try {
            sessions.createLiveSession()
        } catch (error: Exception) {
            // A season that cannot be traded leaves the chart readable.
            null
        } ?: run {
            _panel.value = _panel.value.copy(isLive = false)
            return
        }

        val opened = try {
            session.open()
        } catch (error: Exception) {
            null
        }
        if (opened == null) {
            _panel.value = _panel.value.copy(isLive = false)
            return
        }
        liveSession = session
        _panel.value = _panel.value.copy(isLive = true, riskLock = _panel.value.riskLock)
        refreshTradingState()
    }

    /**
     * Re-reads the account, positions, orders and marks from the session.
     *
     * Called after any command and on every clock tick that moves the price, so the
     * numbers on screen follow the fill and the mark rather than the last command.
     */
    private suspend fun refreshTradingState() {
        val session = liveSession ?: return
        val account = session.account()
        val positions = session.openPositions()
        // The mark is the bid, so the PnL shown is what a close would realise. The
        // session exposes the executable bid per position for exactly this.
        val marks = session.bidPrices()
        _panel.value = _panel.value.copy(
            isLive = true,
            account = account,
            positions = positions,
            pendingOrders = session.pendingOrders(),
            closedTrades = session.closedTrades(),
            markPrices = marks,
        )
        syncChallenge(session, account)
    }

    /**
     * Evaluates the running season's challenge and publishes the dashboard
     * (Steps 11.5-11.8).
     *
     * # Why this is not a separate tick loop
     *
     * The challenge is a function of the engine's state, and the engine only moves
     * when [refreshTradingState] runs — on a tick that moved the price, or after a
     * command. Evaluating from a second loop would evaluate a state that had not
     * changed, and could publish a verdict that disagreed with the panel beside it.
     * Deriving it here means the dashboard and the account are always read from the
     * same engine state.
     *
     * # Why a failure does not propagate
     *
     * A challenge evaluation writes to the database, and a write can fail on a
     * device under pressure. That must not take the trading screen down: the panel
     * has already been updated above, so the failure leaves the chart usable and
     * only the dashboard stale. It is caught and dropped for that reason.
     */
    private suspend fun syncChallenge(session: LiveTradingSession, account: AccountState) {
        val tradingState = session.currentState ?: return
        val atSimUtcMs = tradingState.processedToSimUtcMs
        try {
            val evaluation = sessions.syncChallenge(
                tradingState = tradingState,
                account = account,
                equityMicros = account.equityMicros,
                atSimUtcMs = atSimUtcMs,
            )
            if (evaluation != null) {
                _challenge.value = evaluation.dashboard
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Left as-is: a stale dashboard is better than a broken chart.
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
        loadNewsReleases(summary.datasetId, bounds)
        publish(summary)
    }

    /**
     * Reads the dataset's releases, projected as known at the current instant
     * (Steps 12.2-12.4).
     *
     * # Why the whole dataset, not the visible window
     *
     * The chart can be panned anywhere in the series, and the flags are filtered to
     * the visible bars at draw time. Reading only the opening window would leave the
     * chart flagless the moment the trader scrolled, and re-reading on every pan
     * would be a query per gesture. One read of the schedule is bounded by the
     * dataset and is what the mapper is built to take.
     *
     * # Why a failure is not fatal
     *
     * A chart without news flags is still a usable chart. A failed read leaves the
     * list empty and the trading screen working, rather than failing the whole
     * screen over a decoration.
     */
    private suspend fun loadNewsReleases(datasetId: String, bounds: Pair<Long, Long>) {
        newsBounds = bounds
        val source = newsSource ?: return
        newsSeasonStartUtcMs = bounds.first
        newsBlind = try {
            source.isBlind()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            false
        }
        newsReleases = try {
            source.releasesAt(
                datasetId = datasetId,
                fromUtcMs = bounds.first,
                toUtcMs = bounds.second + ONE_MINUTE_MS,
                simUtcMs = sessions.currentSimUtcMs() ?: bounds.first,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            emptyList()
        }
    }

    /**
     * Re-projects the releases against a new simulated instant (Step 12.4).
     *
     * Called when the clock crosses a bucket boundary, so an event that has just
     * been released is re-projected and a tap on its flag shows the actual. The
     * flags themselves do not change — a scheduled event is on the chart before and
     * after it prints — so this does not republish a frame; it only refreshes what a
     * tap would reveal.
     */
    private suspend fun refreshNewsProjection(simUtcMs: Long) {
        val source = newsSource ?: return
        if (newsReleases.isEmpty()) return
        val datasetId = dataset?.datasetId ?: return
        val bounds = newsBounds ?: return
        newsReleases = try {
            source.releasesAt(
                datasetId = datasetId,
                fromUtcMs = bounds.first,
                toUtcMs = bounds.second + ONE_MINUTE_MS,
                simUtcMs = simUtcMs,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            newsReleases
        }
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
            // Re-project against the new instant so an event that has just printed
            // reveals its actual to a tap (Step 12.4). The flags drawn do not change,
            // so this does not republish the frame.
            refreshNewsProjection(simMs)
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
        // The marks follow the price, so the account and position PnL are re-read on
        // every tick that moves the candle. Doing it only on a command would leave
        // the floating PnL frozen at the last fill while the chart moved under it.
        refreshTradingState()
        _state.value = TradingState.Ready(summary, timeframe, rebuilt.withTradingOverlays())
    }

    /** Switches the charted timeframe, re-aggregating from the loaded minutes. */
    fun selectTimeframe(next: Timeframe) {
        if (next == timeframe) return

        // Step 9.9: keep looking at the same *moment* across the switch. The old
        // viewport is a bar count, which is meaningless on a different timeframe, so
        // the centre's timestamp is captured now — while the old buffer is still in
        // hand — and re-applied once the new series has been aggregated. This is
        // what stops a timeframe switch from silently teleporting the user to an
        // unrelated point in history.
        pendingCentreUtcMs = (_state.value as? TradingState.Ready)?.let { ready ->
            // Only a window the user chose is worth carrying. At the opening view the
            // trader has not looked at anything yet, so the live edge is the right
            // place to land — carrying an opening window's centre would pin them to a
            // moment they never picked.
            if (!userAdjustedViewport) null
            else lastViewport?.let { ChartTimeframeAnchor.centreTimestamp(it, ready.renderState.buffer) }
        }

        timeframe = next
        // The viewport itself is no longer meaningful. The centre captured above
        // replaces it; without a centre (an empty or out-of-range window) the next
        // frame opens at the live edge, which is the old behaviour and still the
        // right answer.
        lastViewport = null
        userAdjustedViewport = false
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
        userAdjustedViewport = true
    }

    /**
     * Builds the render state for the current dataset, timeframe and instant.
     *
     * A suspend function because aggregation runs on a background dispatcher.
     */
    private suspend fun publish(summary: DatasetSummary) {
        val simMs = sessions.currentSimUtcMs()
        lastBucketStart = simMs?.let { timeframe.bucketStartUtcMs(it) }

        // Step 9.9: a centre captured by a timeframe switch wins over the live-edge
        // default. `pendingCentreUtcMs` is read and cleared here, not in
        // `selectTimeframe`, because the new buffer does not exist until the
        // assembler has run below — and it must be cleared either way so it cannot
        // leak into a later, unrelated frame.
        val centre = pendingCentreUtcMs
        pendingCentreUtcMs = null

        // `fitToData` only when this call is introducing a series: an opening view,
        // or the first frame after a timeframe switch (which clears `lastViewport`).
        // Every other publish is refreshing a view the user has already adjusted, and
        // fitting there would undo a pinch-out on the next frame. The real bar count
        // is only known after aggregation, so the assembler applies it — the count
        // this method could pass is the *minute* count, which is 20,548 for a dataset
        // whose weekly series has 4 bars.
        val introducing = lastViewport == null
        val viewport = lastViewport
            ?: ChartViewport.DEFAULT.openingFor(openingBarCount(simMs))

        val renderState = withContext(computeDispatcher) {
            ChartFrameAssembler.build(
                candles = candles,
                timeframe = timeframe,
                style = style,
                window = window,
                simulatedUtcMs = simMs,
                viewport = viewport,
                fallback = LAST_RESORT_RANGE,
                anchorUtcMs = centre,
                fitToData = introducing,
            )
        }
        lastViewport = renderState.viewport

        _state.value = TradingState.Ready(
            dataset = summary,
            timeframe = timeframe,
            renderState = renderState.withTradingOverlays(),
        )
    }

    /**
     * Attaches the trade overlays and markers to a frame (Steps 10.3, 10.8).
     *
     * # Why this is a `copy` after the assembler rather than an assembler argument
     *
     * `ChartFrameAssembler` lives in `core/chart` and must not know what a position
     * is. The overlays are a feature-layer concept, so they are attached here, where
     * the feature may depend on both the chart and the trading domain. The assembler
     * stays a pure geometry function and its tests stay about geometry.
     */
    private fun ChartRenderState.withTradingOverlays(): ChartRenderState {
        // The flags are attached before the session check, because a purely
        // historical chart — no season running — still shows when releases happened.
        // They carry no outcome, so there is nothing to withhold (Steps 12.2-12.4).
        val flags = NewsFlagMapper.flags(newsReleases, buffer)
        val session = liveSession ?: return copy(newsFlags = flags)
        val panelState = _panel.value
        return copy(
            overlays = TradeOverlayMapper.groups(
                positions = panelState.positions,
                pendingOrders = panelState.pendingOrders,
                contract = session.contract,
                previewBracket = previewBracketForTicket(session.contract),
            ),
            // The markers index into the buffer being drawn — the *aggregated*
            // series — not the loaded minutes, because the renderer maps an index to
            // a pixel in that buffer. Resolving against the minutes would put every
            // arrow on the wrong bar on any timeframe above M1.
            markers = TradeOverlayMapper.markers(panelState.closedTrades, buffer),
            newsFlags = flags,
        )
    }

    /**
     * The sizer bracket the ticket is previewing, if any (Step 10.5).
     *
     * Built from the ticket's entry, stop and target so the chart shows the bracket
     * the trader is about to submit, before they submit it. A market ticket has no
     * entry price of its own, so it previews nothing.
     */
    private fun previewBracketForTicket(contract: ContractSpec): TradeOverlayMapper.PreviewBracket? {
        val current = _ticket.value ?: return null
        val entry = current.priceUnits ?: return null
        return TradeOverlayMapper.PreviewBracket(
            side = current.side,
            entryPriceUnits = entry,
            stopPriceUnits = current.stopLossPips?.let {
                TradeOverlay.stopPriceFor(current.side, entry, it, contract)
            },
            targetPriceUnits = current.takeProfitPips?.let {
                TradeOverlay.targetPriceFor(current.side, entry, it, contract)
            },
        )
    }

    // ================= Phase 10: trading commands and ticket =================

    /**
     * The instant a command should be stamped with (Step 10.1).
     *
     * The current simulated instant, never the candle's. A command stamped with a
     * bar's time would be a look-back command the moment the clock has moved past
     * it, which the engine refuses — correctly, because it would be filled against
     * price movement that had already happened when the trader acted.
     */
    private fun commandInstant(): Long? = sessions.currentSimUtcMs()

    /** Opens the order ticket for [side] (Step 10.1). */
    fun openTicket(side: OrderSide) {
        if (!_panel.value.isLive) return
        val entry = currentPrice()
        _ticket.value = OrderTicket(side = side, priceUnits = entry)
        _sheet.value = TradingSheet.TICKET
        recomputeBracketPreview()
    }

    /** Closes the order ticket without submitting. */
    fun closeTicket() {
        _ticket.value = null
        if (_sheet.value == TradingSheet.TICKET) _sheet.value = TradingSheet.NONE
    }

    /** Opens or closes the positions panel. */
    fun togglePositionsPanel() {
        _sheet.value = if (_sheet.value == TradingSheet.POSITIONS) {
            TradingSheet.NONE
        } else {
            TradingSheet.POSITIONS
        }
    }

    fun dismissSheet() {
        _sheet.value = TradingSheet.NONE
        _ticket.value = null
    }

    /**
     * Updates the ticket and recomputes the sizer preview (Steps 10.2, 10.5).
     *
     * A single entry point for every field change so the preview can never be stale
     * relative to the fields it is derived from — the bug that appears when each
     * field has its own setter and one forgets to refresh.
     */
    fun updateTicket(transform: (OrderTicket) -> OrderTicket) {
        val current = _ticket.value ?: return
        _ticket.value = transform(current)
        recomputeBracketPreview()
    }

    /**
     * Recomputes the sizer bracket and the risk-lock size for the current ticket.
     *
     * # What is shown
     *
     * The risk, reward and R:R of the bracket, and the lot size the risk lock
     * implies for the stop. Together these are the answer to "if I take this trade
     * as planned, what can I lose, what can I make, and how big should it be?" —
     * which is the whole point of the sizer being on the ticket rather than a
     * separate screen.
     *
     * A ticket with no stop has no risk to size against, so the preview is cleared
     * rather than showing a zero.
     */
    private fun recomputeBracketPreview() {
        val current = _ticket.value
        val session = liveSession
        if (current == null || session == null) {
            _panel.value = _panel.value.copy(bracketPreview = null, riskLockLotUnits = null)
            return
        }
        val entry = current.priceUnits ?: return
        val stopPips = current.stopLossPips
        val targetPips = current.takeProfitPips
        val contract = session.contract

        val plan = BracketPlan(
            side = current.side,
            entryPriceUnits = entry,
            stopPriceUnits = stopPips?.let { TradeOverlay.stopPriceFor(current.side, entry, it, contract) },
            targetPriceUnits = targetPips?.let {
                TradeOverlay.targetPriceFor(current.side, entry, it, contract)
            },
        )
        val lotUnits = if (current.useRiskLock) {
            riskLockSizeMicros(current, stopPips, entry, contract) ?: current.volumeHundredths
        } else {
            current.volumeHundredths
        }
        val economics = BracketMath.economics(plan, lotUnits, contract)

        _panel.value = _panel.value.copy(
            bracketPreview = economics,
            riskLockLotUnits = if (current.useRiskLock) lotUnits else null,
        )
    }

    /**
     * The lot size the risk lock implies for [stopPips], or null when it cannot size.
     *
     * Uses the account's balance as equity and the normal spread, which is what the
     * trader will actually pay outside a news window. A size that cannot be derived
     * — no stop, or a budget below the minimum lot — returns null and the ticket
     * keeps the typed size, so a drag never silently resizes a trade to zero.
     */
    private fun riskLockSizeMicros(
        ticket: OrderTicket,
        stopPips: Int?,
        entryPriceUnits: Int,
        contract: ContractSpec,
    ): Int? {
        val session = liveSession ?: return null
        val pips = stopPips?.takeIf { it > 0 } ?: return null
        val equity = _panel.value.account?.balanceMicros ?: return null
        val result = RiskLock.size(
            equityMicros = equity,
            lock = _panel.value.riskLock,
            stopDistancePips = pips,
            entryPriceUnits = entryPriceUnits,
            spreadUnits = SpreadModel.NORMAL_UNITS,
            slippageAllowanceUnits = 0,
            contract = contract,
            commission = CommissionSchedule.STANDARD,
            availableMarginMicros = null,
        )
        return (result as? PositionSizeResult.Sized)?.lotUnits
    }

    /** Selects the risk lock used for sizing (Step 10.6). */
    fun selectRiskLock(lock: RiskLockBps) {
        _panel.value = _panel.value.copy(riskLock = lock)
        recomputeBracketPreview()
    }

    /**
     * The last traded price, used to seed a market ticket's entry.
     *
     * The forming candle's close when one exists, otherwise the newest revealed
     * candle's. Returns null before any data, in which case the ticket opens with
     * no entry and previews nothing.
     */
    private fun currentPrice(): Int? {
        val ready = _state.value as? TradingState.Ready ?: return null
        // The buffer stores prices as Float for drawing geometry; the engine trades
        // in Int price units. The conversion is exact for any price under 2^24 units
        // ($16,777), which is well above gold, and `CandleBuffer` refuses a candle it
        // cannot represent exactly.
        return ready.renderState.formingCandle?.closeUnits
            ?: ready.renderState.buffer
                .takeIf { !it.isEmpty }
                ?.closes
                ?.get(ready.renderState.buffer.size - 1)
                ?.toInt()
    }

    /**
     * Submits the open ticket (Steps 10.1, 10.2).
     *
     * Refuses locally when the ticket is incomplete — a pending order with no price,
     * or any order with no size — so the trader sees why rather than a command the
     * engine rejects with a less specific reason.
     */
    fun submitTicket() {
        val current = _ticket.value ?: return
        val session = liveSession ?: return
        val at = commandInstant() ?: return

        val volume = if (current.useRiskLock) {
            _panel.value.riskLockLotUnits ?: current.volumeHundredths
        } else {
            current.volumeHundredths
        }
        if (volume <= 0) {
            _panel.value = _panel.value.copy(lastMessage = "Enter a lot size")
            return
        }
        if (current.type != OrderType.MARKET && current.priceUnits == null) {
            _panel.value = _panel.value.copy(lastMessage = "Enter a price for a ${current.type} order")
            return
        }

        viewModelScope.launch {
            val result = session.submit(
                TradingCommand.PlaceOrder(
                    atSimUtcMs = at,
                    orderId = nextOrderId(),
                    type = current.type,
                    side = current.side,
                    volumeHundredths = volume,
                    priceUnits = if (current.type == OrderType.MARKET) null else current.priceUnits,
                    stopLossPips = current.stopLossPips,
                    takeProfitPips = current.takeProfitPips,
                ),
            )
            applyResult(result) { closeTicket() }
        }
    }

    /** Closes a position in full, or [volumeHundredths] of it (Step 10.7). */
    fun closePosition(positionId: String, volumeHundredths: Int? = null) {
        val session = liveSession ?: return
        val at = commandInstant() ?: return
        viewModelScope.launch {
            applyResult(session.closePosition(positionId, at, volumeHundredths))
        }
    }

    /** Cancels a pending order (Step 10.7). */
    fun cancelOrder(orderId: String) {
        val session = liveSession ?: return
        val at = commandInstant() ?: return
        viewModelScope.launch {
            applyResult(session.cancelOrder(orderId, at))
        }
    }

    /** Arms breakeven on a position (Step 10.7). */
    fun moveToBreakeven(positionId: String) {
        val session = liveSession ?: return
        val at = commandInstant() ?: return
        viewModelScope.launch {
            applyResult(session.armBreakeven(positionId, at))
        }
    }

    /**
     * Moves a position's stop to a dragged price (Step 10.4).
     *
     * The dragged price is turned into the pip distance the engine stores. A stop
     * dragged to the wrong side of entry yields a non-positive distance, which the
     * engine rejects; the rejection is surfaced rather than clamped, because
     * silently snapping the stop to one pip would hide the fact that the trader
     * dragged it through their entry.
     */
    fun dragPositionStop(positionId: String, priceUnits: Int) {
        val session = liveSession ?: return
        val position = _panel.value.positions.firstOrNull { it.positionId == positionId } ?: return
        val at = commandInstant() ?: return
        val pips = TradeOverlay.pipDistanceFor(
            side = position.side,
            entryPriceUnits = position.openPriceUnits,
            levelPriceUnits = priceUnits,
            contract = session.contract,
            direction = TradeDirection.ADVERSE,
        )
        viewModelScope.launch {
            applyResult(
                session.modifyPosition(
                    positionId = positionId,
                    atSimUtcMs = at,
                    stopLossPips = Modification.Set(pips),
                ),
            )
        }
    }

    /** Moves a position's target to a dragged price (Step 10.4). */
    fun dragPositionTarget(positionId: String, priceUnits: Int) {
        val session = liveSession ?: return
        val position = _panel.value.positions.firstOrNull { it.positionId == positionId } ?: return
        val at = commandInstant() ?: return
        val pips = TradeOverlay.pipDistanceFor(
            side = position.side,
            entryPriceUnits = position.openPriceUnits,
            levelPriceUnits = priceUnits,
            contract = session.contract,
            direction = TradeDirection.FAVOURABLE,
        )
        viewModelScope.launch {
            applyResult(
                session.modifyPosition(
                    positionId = positionId,
                    atSimUtcMs = at,
                    takeProfitPips = Modification.Set(pips),
                ),
            )
        }
    }

    /** Moves a pending order's entry to a dragged price (Step 10.4). */
    fun dragOrderEntry(orderId: String, priceUnits: Int) {
        val session = liveSession ?: return
        val at = commandInstant() ?: return
        viewModelScope.launch {
            applyResult(session.modifyOrder(orderId, at, priceUnits = Modification.Set(priceUnits)))
        }
    }

    // ------------------------------------------------ chart line drag (Step 10.4)

    /**
     * The line the chart has grabbed, or null when no drag is under way.
     *
     * Held across the drag because the chart reports a stream of prices and only
     * the grab knows *which* line they belong to. The chart deliberately does not
     * name the line — it must not know what an order is — so the identity is kept
     * here, in the feature, between the grab and the release.
     */
    private var grabbedLine: OverlayLineHit? = null

    /** The latest price a drag has reported, applied on release. */
    private var grabbedPriceUnits: Int? = null

    /**
     * Called when a touch lands on the chart, to decide whether it grabbed a line.
     *
     * Returns true when a draggable line was within [toleranceUnits] of
     * [tapPriceUnits], which is what makes the chart treat the touch as an
     * order-line drag rather than a pan. The overlays searched are the ones currently
     * drawn, so a line the trader can see is a line they can grab.
     */
    fun grabOrderLine(tapPriceUnits: Int, toleranceUnits: Int): Boolean {
        val ready = _state.value as? TradingState.Ready ?: return false
        val hit = OverlayLineHitTester.hit(
            overlays = ready.renderState.overlays,
            tapPriceUnits = tapPriceUnits,
            toleranceUnits = toleranceUnits,
        )
        grabbedLine = hit
        grabbedPriceUnits = hit?.priceUnits
        return hit != null
    }

    /** Records the price under a dragging finger; the move is submitted on release. */
    fun dragGrabbedLine(priceUnits: Int) {
        if (grabbedLine == null) return
        grabbedPriceUnits = priceUnits
    }

    /**
     * Submits the move for the grabbed line (Step 10.4).
     *
     * One command per gesture rather than one per frame: a drag of a hundred frames
     * would otherwise be a hundred commands, each stamped with the same instant and
     * each rejected after the first as a look-back. The engine is told once, at the
     * price the finger finished on.
     */
    fun releaseGrabbedLine() {
        val hit = grabbedLine
        val price = grabbedPriceUnits
        grabbedLine = null
        grabbedPriceUnits = null
        if (hit == null || price == null) return

        when (hit.kind) {
            OverlayLineKind.STOP -> dragPositionStop(hit.overlayId, price)
            OverlayLineKind.TARGET -> dragPositionTarget(hit.overlayId, price)
            OverlayLineKind.ENTRY -> dragOrderEntry(hit.overlayId, price)
        }
    }

    /**
     * Applies a command's result: refresh the panel, and report a refusal.
     *
     * A rejection is shown rather than swallowed. A chart gesture that silently did
     * nothing would read as the app being broken, when in fact the engine refused a
     * command for a reason the trader can act on.
     */
    private suspend fun applyResult(
        result: CommandResult,
        onAccepted: (() -> Unit)? = null,
    ) {
        when (result) {
            is CommandResult.Accepted -> {
                onAccepted?.invoke()
                refreshTradingState()
                // The frame must show the new position's lines without waiting for
                // the next tick, so the overlays are rebuilt now.
                republishOverlays()
                _panel.value = _panel.value.copy(lastMessage = null)
            }

            is CommandResult.Rejected -> {
                _panel.value = _panel.value.copy(
                    lastMessage = "Refused: ${result.reason}",
                )
            }
        }
    }

    /**
     * Rebuilds the current frame's overlays without re-aggregating.
     *
     * A fill changes the overlay set but not the candles, so the buffer is reused
     * and only the overlays are recomputed — the same reasoning as the clock tick.
     */
    private fun republishOverlays() {
        val ready = _state.value as? TradingState.Ready ?: return
        _state.value = ready.copy(renderState = ready.renderState.withTradingOverlays())
    }

    /** A per-session-unique order id, so two orders cannot collide. */
    private fun nextOrderId(): String = "ui-${orderIdCounter++}"

    private var orderIdCounter: Int = 1

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

    // ================= Phase 12: news flags on the chart =================

    /**
     * Opens the release behind a tapped flag (Steps 12.3, 12.4).
     *
     * # Why the release is re-resolved rather than carried on the hit
     *
     * A [NewsFlagHit] names a bar and a headline — deliberately, because the chart
     * must not know what a release is. The release is found by the pair, so a flag
     * whose event has since been re-projected still opens the right one, and a hit
     * that matches nothing (a stale tap after a dataset switch) opens nothing rather
     * than the wrong event.
     */
    fun onNewsFlagTapped(hit: NewsFlagHit) {
        val buffer = (_state.value as? TradingState.Ready)?.renderState?.buffer ?: return
        val release = newsReleases.firstOrNull { candidate ->
            candidate.headline == hit.headline &&
                buffer.indexAtOrAfter(candidate.scheduledUtcMs) == hit.index
        } ?: return
        val seasonStart = newsSeasonStartUtcMs ?: return
        _newsDetail.value = release.toRow(
            simUtcMs = sessions.currentSimUtcMs() ?: seasonStart,
            seasonStartUtcMs = seasonStart,
            blind = newsBlind,
        )
    }

    /** Closes the release detail. */
    fun dismissNewsDetail() {
        _newsDetail.value = null
    }

    companion object {
        private const val ONE_MINUTE_MS: Long = 60_000L

        /** Used only when a dataset somehow yields no visible candle. */
        private val LAST_RESORT_RANGE = PriceAxisRange.around(2_050_000f)

        fun factory(
            repository: MarketRepository,
            style: ChartStyle,
            sessions: TradingSessionSource,
            newsSource: NewsScheduleSource? = null,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    TradingViewModel(repository, style, sessions, newsSource = newsSource) as T
            }
    }
}
