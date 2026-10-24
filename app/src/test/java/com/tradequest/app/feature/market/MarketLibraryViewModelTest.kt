package com.tradequest.app.feature.market

import androidx.test.core.app.ApplicationProvider
import com.tradequest.app.core.challenge.ChallengeLaunchResult
import com.tradequest.app.core.challenge.ChallengeLauncher
import com.tradequest.app.core.database.market.MarketDatabase
import com.tradequest.app.core.database.market.MarketDatasetEntity
import com.tradequest.app.core.database.market.RoomMarketRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Market Library ViewModel tests.
 *
 * The ViewModel is driven through the real [RoomMarketRepository] on real
 * SQLite, not a fake. A fake would prove only that the ViewModel agrees with
 * itself; this proves the screen renders what the database actually holds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MarketLibraryViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: MarketDatabase
    private lateinit var repository: RoomMarketRepository
    private lateinit var launcher: RecordingLauncher
    private lateinit var viewModel: MarketLibraryViewModel

    private val t0 = 1_705_276_800_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = MarketDatabase.create(ApplicationProvider.getApplicationContext())
        repository = RoomMarketRepository(db)
        launcher = RecordingLauncher()
        viewModel = MarketLibraryViewModel(repository, launcher)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun insert(id: String, name: String, start: Long = t0) {
        db.marketDao().insertDataset(
            MarketDatasetEntity(
                datasetId = id, displayName = name, instrument = "XAUUSD",
                startTimestampUtcMs = start, endTimestampUtcMs = start + 86_400_000L,
                candleCount = 1440, contentHash = "h_$id", validationStatus = "VALID",
                sourceDescription = "t", importedAtUtcMs = t0,
            ),
        )
    }

    private suspend fun awaitState(predicate: (MarketLibraryState) -> Boolean): MarketLibraryState =
        viewModel.state.first(predicate)

    /**
     * Records what the screen asked the launcher to do and answers with a scripted
     * result.
     *
     * A fake rather than the real launcher because the real one writes a season and
     * binds a `SimulationClock`; those are the composition root's behaviour and are
     * exercised where the container is. What this test must pin is the *screen's*
     * contract: that a start with no confirmation never abandons, and that a
     * confirmation answer is forwarded.
     */
    private class RecordingLauncher(
        var result: ChallengeLaunchResult = ChallengeLaunchResult.Started("s1", "A"),
        var failure: Exception? = null,
    ) : ChallengeLauncher {
        data class Call(
            val datasetId: String,
            val confirmAbandon: Boolean,
            val blindMode: Boolean,
        )

        val calls = mutableListOf<Call>()

        override suspend fun startChallenge(
            datasetId: String,
            confirmAbandon: Boolean,
            blindMode: Boolean,
        ): ChallengeLaunchResult {
            calls += Call(datasetId, confirmAbandon, blindMode)
            failure?.let { throw it }
            return result
        }
    }

    @Test
    fun `empty database shows the empty state`() = runTest(dispatcher) {
        assertTrue(awaitState { it is MarketLibraryState.Empty } is MarketLibraryState.Empty)
    }

    @Test
    fun `datasets are listed once inserted`() = runTest(dispatcher) {
        insert("ds_a", "Gold Q1")
        advanceUntilIdle()

        val state = awaitState { it is MarketLibraryState.Loaded } as MarketLibraryState.Loaded
        assertEquals(1, state.datasets.size)
        assertEquals("Gold Q1", state.datasets.single().displayName)
    }

    @Test
    fun `the first dataset is selected automatically`() = runTest(dispatcher) {
        insert("ds_a", "Gold Q1")
        advanceUntilIdle()

        val state = awaitState { it is MarketLibraryState.Loaded } as MarketLibraryState.Loaded
        assertEquals("ds_a", state.selectedDatasetId)
        assertEquals("Gold Q1", state.selected!!.displayName)
    }

    @Test
    fun `selecting a dataset updates the detail pane`() = runTest(dispatcher) {
        insert("ds_a", "A", start = t0)
        insert("ds_b", "B", start = t0 + 1_000_000L)
        advanceUntilIdle()
        awaitState { it is MarketLibraryState.Loaded }

        viewModel.selectDataset("ds_b")
        advanceUntilIdle()

        val state = awaitState {
            it is MarketLibraryState.Loaded && it.selectedDatasetId == "ds_b"
        } as MarketLibraryState.Loaded
        assertEquals("B", state.selected!!.displayName)
    }

    @Test
    fun `selection falls back to the first dataset when the selected one disappears`() =
        runTest(dispatcher) {
            insert("ds_a", "A", start = t0)
            insert("ds_b", "B", start = t0 + 1_000_000L)
            advanceUntilIdle()
            awaitState { it is MarketLibraryState.Loaded }

            viewModel.selectDataset("ds_b")
            advanceUntilIdle()
            awaitState { it is MarketLibraryState.Loaded && it.selectedDatasetId == "ds_b" }

            repository.deleteDataset("ds_b")
            advanceUntilIdle()

            val state = awaitState {
                it is MarketLibraryState.Loaded && it.selectedDatasetId == "ds_a"
            } as MarketLibraryState.Loaded
            assertEquals("A", state.selected!!.displayName)
            assertEquals(1, state.datasets.size)
        }

    @Test
    fun `deleting the last dataset returns to the empty state`() = runTest(dispatcher) {
        insert("ds_a", "A")
        advanceUntilIdle()
        awaitState { it is MarketLibraryState.Loaded }

        repository.deleteDataset("ds_a")
        advanceUntilIdle()

        assertTrue(awaitState { it is MarketLibraryState.Empty } is MarketLibraryState.Empty)
    }

    // ---------- Starting a challenge (Steps 11.1-11.3) ----------

    @Test
    fun `starting a challenge on the selected dataset asks the launcher without confirming`() =
        runTest(dispatcher) {
            insert("ds_a", "A")
            advanceUntilIdle()
            awaitState { it is MarketLibraryState.Loaded }

            viewModel.startChallenge("ds_a")
            advanceUntilIdle()

            assertEquals(
                listOf(RecordingLauncher.Call("ds_a", confirmAbandon = false, blindMode = false)),
                launcher.calls,
            )
        }

    @Test
    fun `a running challenge surfaces the abandonment prompt and starts nothing`() =
        runTest(dispatcher) {
            insert("ds_a", "A")
            insert("ds_b", "B")
            advanceUntilIdle()
            awaitState { it is MarketLibraryState.Loaded }
            launcher.result = ChallengeLaunchResult.ConfirmationRequired("s1")

            viewModel.startChallenge("ds_b")
            advanceUntilIdle()

            val message = viewModel.challengeStart.value
            assertTrue(message is ChallengeStartMessage.ConfirmAbandon)
            assertEquals("ds_b", (message as ChallengeStartMessage.ConfirmAbandon).datasetId)
            assertEquals("B", message.datasetDisplayName)
            // The destructive call was never made with confirmation.
            assertTrue(launcher.calls.none { it.confirmAbandon })
        }

    @Test
    fun `confirming the prompt abandons and starts`() = runTest(dispatcher) {
        insert("ds_a", "A")
        insert("ds_b", "B")
        advanceUntilIdle()
        awaitState { it is MarketLibraryState.Loaded }

        viewModel.confirmAbandon("ds_b")
        advanceUntilIdle()

        assertEquals(
            listOf(RecordingLauncher.Call("ds_b", confirmAbandon = true, blindMode = false)),
            launcher.calls,
        )
        assertTrue(viewModel.challengeStart.value is ChallengeStartMessage.Started)
    }

    @Test
    fun `a missing dataset is reported rather than started`() = runTest(dispatcher) {
        insert("ds_a", "A")
        advanceUntilIdle()
        awaitState { it is MarketLibraryState.Loaded }
        launcher.result = ChallengeLaunchResult.DatasetNotFound("ds_gone")

        viewModel.startChallenge("ds_gone")
        advanceUntilIdle()

        assertTrue(viewModel.challengeStart.value is ChallengeStartMessage.DatasetNotFound)
    }

    @Test
    fun `an empty dataset is reported rather than started`() = runTest(dispatcher) {
        insert("ds_a", "A")
        advanceUntilIdle()
        awaitState { it is MarketLibraryState.Loaded }
        launcher.result = ChallengeLaunchResult.DatasetNotReplayable("ds_a")

        viewModel.startChallenge("ds_a")
        advanceUntilIdle()

        assertTrue(viewModel.challengeStart.value is ChallengeStartMessage.DatasetNotReplayable)
    }

    @Test
    fun `a launcher failure is reported rather than thrown`() = runTest(dispatcher) {
        insert("ds_a", "A")
        advanceUntilIdle()
        awaitState { it is MarketLibraryState.Loaded }
        launcher.failure = IllegalStateException("disk full")

        viewModel.startChallenge("ds_a")
        advanceUntilIdle()

        val message = viewModel.challengeStart.value
        assertTrue(message is ChallengeStartMessage.Failed)
        assertEquals("disk full", (message as ChallengeStartMessage.Failed).message)
    }

    @Test
    fun `dismissing the prompt clears it`() = runTest(dispatcher) {
        insert("ds_a", "A")
        advanceUntilIdle()
        awaitState { it is MarketLibraryState.Loaded }
        launcher.result = ChallengeLaunchResult.ConfirmationRequired("s1")

        viewModel.startChallenge("ds_a")
        advanceUntilIdle()
        assertTrue(viewModel.challengeStart.value != null)

        viewModel.dismissChallengeStart()
        assertNull(viewModel.challengeStart.value)
    }

    @Test
    fun `the summary shown carries the period and counts`() = runTest(dispatcher) {
        insert("ds_a", "Gold Q1")
        advanceUntilIdle()

        val summary = (awaitState { it is MarketLibraryState.Loaded } as MarketLibraryState.Loaded)
            .selected!!
        assertEquals("XAUUSD", summary.instrument)
        assertEquals(1440, summary.candleCount)
        assertEquals(1L, summary.spanDays)
        assertEquals(t0, summary.startTimestampUtcMs)
    }
}
