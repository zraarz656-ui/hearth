package com.tradequest.app.feature.challenge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tradequest.app.core.challenge.ChallengeDashboardState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * What the Challenge dashboard is showing (Step 11.8).
 *
 * [Loaded.dashboard] is null when no season is running, which is a normal state —
 * the app can be opened before a challenge exists — so it is a nullable field
 * rather than an error or an empty-state variant of its own.
 */
sealed interface ChallengeDashboardUiState {

    /** Nothing has been read yet. */
    data object Loading : ChallengeDashboardUiState

    /** The active season's figures, or null when no season is running. */
    data class Loaded(val dashboard: ChallengeDashboardState?) : ChallengeDashboardUiState

    /** The player database could not be read. */
    data class Failed(val message: String) : ChallengeDashboardUiState
}

/**
 * Backs the Challenge dashboard (Step 11.8).
 *
 * Reads only through [ChallengeOverviewSource], so the screen cannot reach a
 * database, a season entity or the challenge arithmetic — the same boundary
 * `MarketLibraryViewModel` keeps one feature over. The history of finished
 * attempts is a separate screen with its own ViewModel, so this one subscribes to
 * the dashboard flow alone and a change to the history does not recompose it.
 */
class ChallengeDashboardViewModel(
    source: ChallengeOverviewSource,
) : ViewModel() {

    val state: StateFlow<ChallengeDashboardUiState> = source.observeDashboard()
        .map { dashboard -> ChallengeDashboardUiState.Loaded(dashboard) as ChallengeDashboardUiState }
        .catch { error ->
            emit(ChallengeDashboardUiState.Failed(error.message ?: "Could not read challenge data"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = ChallengeDashboardUiState.Loading,
        )

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(source: ChallengeOverviewSource): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ChallengeDashboardViewModel(source) as T
            }
    }
}
