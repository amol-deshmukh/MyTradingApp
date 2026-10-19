package com.example.myapplication.ui.markets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.TradingRepository
import com.example.myapplication.data.model.ConnectionState
import com.example.myapplication.data.model.StockQuote
import com.example.myapplication.data.remote.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MarketsUiState(
    val stocks: List<StockQuote> = emptyList(),
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val connection: ConnectionState = ConnectionState.OFFLINE,
)

class MarketsViewModel(private val repository: TradingRepository) : ViewModel() {
    private val isRefreshing = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    val state: StateFlow<MarketsUiState> = combine(
        repository.stocks, isRefreshing, error, repository.connection,
    ) { stocks, refreshing, err, conn ->
        MarketsUiState(stocks, refreshing, err, conn)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MarketsUiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            isRefreshing.value = true
            error.value = repository.refreshStocks().exceptionOrNull()?.toUserMessage()
            isRefreshing.value = false
        }
    }
}
