package com.example.myapplication.ui.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.TradingRepository
import com.example.myapplication.data.model.ConnectionState
import com.example.myapplication.data.model.Holding
import com.example.myapplication.data.remote.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PortfolioUiState(
    val loaded: Boolean = false,
    val cash: Double = 0.0,
    val holdings: List<Holding> = emptyList(),
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val connection: ConnectionState = ConnectionState.OFFLINE,
) {
    val invested: Double get() = holdings.sumOf { it.avgPrice * it.quantity }
    val marketValue: Double get() = holdings.sumOf { it.lastPrice * it.quantity }
    val totalReturn: Double get() = marketValue - invested
    val totalReturnPercent: Double get() = if (invested == 0.0) 0.0 else totalReturn / invested * 100
    val accountValue: Double get() = cash + marketValue
}

class PortfolioViewModel(private val repository: TradingRepository) : ViewModel() {
    private val isRefreshing = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    val state: StateFlow<PortfolioUiState> = combine(
        repository.portfolio, repository.livePrices, isRefreshing, error, repository.connection,
    ) { portfolio, live, refreshing, err, conn ->
        PortfolioUiState(
            loaded = portfolio != null,
            cash = portfolio?.cash ?: 0.0,
            // Positions are fetched once; valuations update from the price stream.
            holdings = portfolio?.holdings.orEmpty().map { h -> live[h.symbol]?.let { h.copy(lastPrice = it.price) } ?: h },
            isRefreshing = refreshing,
            error = err,
            connection = conn,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PortfolioUiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            isRefreshing.value = true
            error.value = repository.refreshPortfolio().exceptionOrNull()?.toUserMessage()
            isRefreshing.value = false
        }
    }
}
