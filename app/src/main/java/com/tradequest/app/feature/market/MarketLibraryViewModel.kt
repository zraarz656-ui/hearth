package com.tradequest.app.feature.market

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tradequest.app.core.market.DatasetSummary
import com.tradequest.app.core.market.MarketRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

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
 * Backs the Market Library screen (Step 2.6).
 *
 * Reads through [MarketRepository] only, never from the database directly, so
 * the dataset-isolation rules in the repository cannot be bypassed by the UI.
 */
class MarketLibraryViewModel(
    private val repository: MarketRepository,
) : ViewModel() {

    private val selectedId = MutableStateFlow<String?>(null)

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

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(repository: MarketRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MarketLibraryViewModel(repository) as T
            }
    }
}
