package com.tradequest.app.feature.market

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tradequest.app.core.challenge.ChallengeLaunchResult
import com.tradequest.app.core.challenge.ChallengeLauncher
import com.tradequest.app.core.market.DatasetSummary
import com.tradequest.app.core.market.MarketRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the Market Library is showing. */
sealed interface MarketLibraryState {
    /** The first emission has not arrived yet. */
    data object Loading : MarketLibraryState

    /** No datasets have been imported yet. */
    data object Empty : MarketLibraryState

    data class Loaded(
        val datasets: List<DatasetSummary>,
        val selectedDatasetId: String?,
    ) : MarketLibraryState {
        val selected: DatasetSummary? get() = datasets.firstOrNull { it.datasetId == selectedDatasetId }
    }

    /** The database could not be read. */
    data class Failed(val message: String) : MarketLibraryState
}

/**
 * A challenge-start outcome the screen has to show (Steps 11.1-11.3).
 *
 * Held as a separate flow rather than folded into [MarketLibraryState] because the
 * library's contents do not change when a challenge starts, and a message must
 * survive the next dataset emission — which arrives from the database, not from the
 * start action. Folding it in would let a database tick silently dismiss the
 * trader's confirmation prompt.
 */
sealed interface ChallengeStartMessage {
    /**
     * A challenge is running and starting another would abandon it (Step 11.3).
     * The screen shows the confirmation prompt for [datasetId].
     */
    data class ConfirmAbandon(val datasetId: String, val datasetDisplayName: String) :
        ChallengeStartMessage

    /** The challenge started. [datasetDisplayName] is what it runs on. */
    data class Started(val datasetDisplayName: String) : ChallengeStartMessage

    /** The dataset is not in the library. */
    data class DatasetNotFound(val datasetId: String) : ChallengeStartMessage

    /** The dataset exists but has no candles to replay. */
    data class DatasetNotReplayable(val datasetId: String) : ChallengeStartMessage

    /** The start attempt itself failed. */
    data class Failed(val message: String) : ChallengeStartMessage
}

/**
 * Backs the Market Library screen (Step 2.6, Steps 11.1-11.3).
 *
 * Reads through [MarketRepository] only, never from the database directly, so
 * the dataset-isolation rules in the repository cannot be bypassed by the UI.
 *
 * Starting a challenge goes through [ChallengeLauncher], not through a repository:
 * a season row and the simulation clock must be bound together, and only the
 * composition root can do both (see the port's documentation).
 */
class MarketLibraryViewModel(
    private val repository: MarketRepository,
    private val launcher: ChallengeLauncher,
) : ViewModel() {

    private val selectedId = MutableStateFlow<String?>(null)

    private val _challengeStart = MutableStateFlow<ChallengeStartMessage?>(null)
    /** The pending challenge-start prompt or message, or null when there is none. */
    val challengeStart: StateFlow<ChallengeStartMessage?> = _challengeStart.asStateFlow()

    val state: StateFlow<MarketLibraryState> =
        combine(repository.observeDatasets(), selectedId) { datasets, current ->
            if (datasets.isEmpty()) {
                MarketLibraryState.Empty
            } else {
                // Keep the user's selection if it still exists, otherwise fall
                // back to the first dataset so the detail pane never shows a
                // dataset that has been deleted.
                val resolved = datasets.firstOrNull { it.datasetId == current }?.datasetId
                    ?: datasets.first().datasetId
                MarketLibraryState.Loaded(datasets, resolved)
            }
        }
            .catch { error ->
                emit(MarketLibraryState.Failed(error.message ?: "Could not read market data"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = MarketLibraryState.Loading,
            )

    fun selectDataset(datasetId: String) {
        selectedId.value = datasetId
    }

    /**
     * Starts a challenge on [datasetId] (Step 11.1).
     *
     * With no confirmation: if a challenge is already running, the launcher returns
     * `ConfirmationRequired` and this surfaces the prompt rather than starting
     * anything, so the destructive path is never taken by this call.
     */
    fun startChallenge(datasetId: String) {
        launchStart(datasetId, confirmAbandon = false)
    }

    /** Answers the abandonment prompt (Step 11.3). */
    fun confirmAbandon(datasetId: String) {
        launchStart(datasetId, confirmAbandon = true)
    }

    /** Dismisses the pending message or prompt. */
    fun dismissChallengeStart() {
        _challengeStart.value = null
    }

    private fun launchStart(datasetId: String, confirmAbandon: Boolean) {
        viewModelScope.launch {
            val displayName = (state.value as? MarketLibraryState.Loaded)
                ?.datasets?.firstOrNull { it.datasetId == datasetId }
                ?.displayName
                ?: datasetId

            val message = try {
                when (val result = launcher.startChallenge(datasetId, confirmAbandon)) {
                    is ChallengeLaunchResult.Started ->
                        ChallengeStartMessage.Started(result.datasetDisplayName)

                    is ChallengeLaunchResult.ConfirmationRequired ->
                        ChallengeStartMessage.ConfirmAbandon(datasetId, displayName)

                    is ChallengeLaunchResult.DatasetNotFound ->
                        ChallengeStartMessage.DatasetNotFound(result.datasetId)

                    is ChallengeLaunchResult.DatasetNotReplayable ->
                        ChallengeStartMessage.DatasetNotReplayable(result.datasetId)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ChallengeStartMessage.Failed(error.message ?: "Could not start the challenge")
            }
            _challengeStart.value = message
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(
            repository: MarketRepository,
            launcher: ChallengeLauncher,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MarketLibraryViewModel(repository, launcher) as T
            }
    }
}
