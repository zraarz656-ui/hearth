package com.tradequest.app.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tradequest.app.core.challenge.ChallengeHistoryEntry
import com.tradequest.app.feature.challenge.ChallengeOverviewSource
import com.tradequest.app.feature.challenge.DatasetBest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * What the history screen is showing (Steps 11.9, 11.11).
 *
 * The list of finished attempts and the per-dataset bests arrive together because
 * they are two views of one history: a failure to read either is a failure to read
 * the database, and two separate error states would let the screen show a list
 * beside an error.
 */
sealed interface HistoryUiState {

    /** Nothing has been read yet. */
    data object Loading : HistoryUiState

    /** The finished attempts and the personal bests. */
    data class Loaded(
        val history: List<ChallengeHistoryEntry>,
        val personalBests: List<DatasetBest>,
    ) : HistoryUiState

    /** The player database could not be read. */
    data class Failed(val message: String) : HistoryUiState
}

/**
 * Backs the challenge history screen (Steps 11.9, 11.11).
 *
 * Reads through [ChallengeOverviewSource], so it never sees a database row or a
 * `SeasonEntity`. Both lists come from the one history flow, so they are always
 * derived from the same snapshot: a best cannot be computed from a history that
 * includes an attempt the list below it does not.
 */
class HistoryViewModel(
    source: ChallengeOverviewSource,
) : ViewModel() {

    val state: StateFlow<HistoryUiState> = combine(
        source.observeHistory(),
        source.observePersonalBests(),
    ) { history, bests ->
        HistoryUiState.Loaded(history, bests) as HistoryUiState
    }
        .catch { error ->
            emit(HistoryUiState.Failed(error.message ?: "Could not read challenge history"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = HistoryUiState.Loading,
        )

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(source: ChallengeOverviewSource): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HistoryViewModel(source) as T
            }
    }
}
