package com.example.myapplication.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.TradingRepository
import com.example.myapplication.data.model.ConnectionState
import com.example.myapplication.data.model.Holding
import com.example.myapplication.data.model.OrderRequest
import com.example.myapplication.data.model.OrderSide
import com.example.myapplication.data.model.StockDetail
import com.example.myapplication.data.model.StockQuote
import com.example.myapplication.data.remote.toUserMessage
import com.example.myapplication.ui.components.asMoney
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class OrderForm(
    val side: OrderSide,
    val quantity: Int = 1,
    /** Generated once per order; reused across retries so the server can de-duplicate. */
    val idempotencyKey: String = UUID.randomUUID().toString(),
    val isSubmitting: Boolean = false,
    val error: String? = null,
)

data class StockDetailUiState(
    val symbol: String,
    val quote: StockQuote? = null,
    val detail: StockDetail? = null,
    val chart: List<Double> = emptyList(),
    val holding: Holding? = null,
    val cash: Double? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val connection: ConnectionState = ConnectionState.OFFLINE,
    val orderForm: OrderForm? = null,
    val message: String? = null,
)

class StockDetailViewModel(
    private val symbol: String,
    private val repository: TradingRepository,
) : ViewModel() {
    private val chart = MutableStateFlow<List<Double>>(emptyList())
    private val local = MutableStateFlow(LocalState())

    private data class LocalState(
        val isLoading: Boolean = false,
        val error: String? = null,
        val orderForm: OrderForm? = null,
        val message: String? = null,
    )

    private val positionFlow = repository.portfolio.map { p ->
        p?.cash to p?.holdings?.firstOrNull { it.symbol == symbol }
    }

    val state: StateFlow<StockDetailUiState> = combine(
        repository.quote(symbol),
        repository.detail(symbol),
        chart,
        positionFlow,
        combine(local, repository.connection) { l, c -> l to c },
    ) { quote, detail, points, (cash, holding), (l, conn) ->
        StockDetailUiState(
            symbol = symbol,
            quote = quote,
            detail = detail,
            chart = points,
            holding = holding,
            cash = cash,
            isLoading = l.isLoading,
            error = l.error,
            connection = conn,
            orderForm = l.orderForm,
            message = l.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StockDetailUiState(symbol))

    init {
        // Chart = server history, extended client-side with live ticks (no re-fetching needed).
        viewModelScope.launch {
            repository.detail(symbol).filterNotNull().collect { chart.value = it.history }
        }
        viewModelScope.launch {
            repository.quote(symbol).filterNotNull().map { it.price }.distinctUntilChanged().collect { price ->
                chart.update { if (it.isEmpty()) it else (it + price).takeLast(240) }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            local.update { it.copy(isLoading = true, error = null) }
            if (repository.portfolio.value == null) launch { repository.refreshPortfolio() }
            val err = repository.refreshDetail(symbol).exceptionOrNull()
            local.update { it.copy(isLoading = false, error = err?.toUserMessage()) }
        }
    }

    fun openOrder(side: OrderSide) = local.update { it.copy(orderForm = OrderForm(side)) }

    fun setQuantity(quantity: Int) = local.update { s ->
        val form = s.orderForm ?: return@update s
        // Changing the order makes it a different order -> new idempotency key.
        s.copy(orderForm = form.copy(quantity = quantity.coerceAtLeast(1), idempotencyKey = UUID.randomUUID().toString(), error = null))
    }

    fun dismissOrder() = local.update { s ->
        if (s.orderForm?.isSubmitting == true) s else s.copy(orderForm = null)
    }

    fun submitOrder() {
        val form = local.value.orderForm ?: return
        if (form.isSubmitting) return // guard against double taps while the slow request is in flight
        local.update { it.copy(orderForm = form.copy(isSubmitting = true, error = null)) }
        viewModelScope.launch {
            repository.placeOrder(OrderRequest(symbol, form.side, form.quantity), form.idempotencyKey)
                .onSuccess { r ->
                    val verb = if (r.side == OrderSide.BUY) "Bought" else "Sold"
                    local.update {
                        it.copy(orderForm = null, message = "$verb ${r.quantity} ${r.symbol} @ ${r.filledPrice.asMoney()}")
                    }
                }
                .onFailure { e ->
                    // Keep the same key: if the order actually executed, the retry returns that result.
                    local.update { it.copy(orderForm = form.copy(isSubmitting = false, error = e.toUserMessage())) }
                }
        }
    }

    fun messageShown() = local.update { it.copy(message = null) }
}
