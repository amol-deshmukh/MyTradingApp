package com.example.myapplication.data

import androidx.tracing.trace
import com.example.myapplication.data.model.ConnectionState
import com.example.myapplication.data.model.OrderRequest
import com.example.myapplication.data.model.OrderResponse
import com.example.myapplication.data.model.Portfolio
import com.example.myapplication.data.model.StockDetail
import com.example.myapplication.data.model.StockQuote
import com.example.myapplication.data.remote.PriceStreamClient
import com.example.myapplication.data.remote.StreamEvent
import com.example.myapplication.data.remote.TradingApi
import com.example.myapplication.data.remote.retryWithBackoff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single source of truth for market and account data.
 *
 * Latency strategy:
 *  - Stale-while-revalidate: everything fetched is cached in memory and exposed as flows, so
 *    screens render cached data instantly and refresh in the background.
 *  - Live prices come from a WebSocket and are overlaid onto the REST data, so the slow REST
 *    calls only need to run once per screen, not on every price change.
 *  - Identical in-flight requests are de-duplicated (one network call, many awaiters).
 *  - Idempotent calls are retried with backoff; orders use an Idempotency-Key so retries are safe.
 */
class TradingRepository(
    private val api: TradingApi,
    private val priceStream: PriceStreamClient,
    private val scope: CoroutineScope,
) {
    private val baseQuotes = MutableStateFlow<List<StockQuote>>(emptyList())
    private val liveQuotes = MutableStateFlow<Map<String, StockQuote>>(emptyMap())
    private val details = MutableStateFlow<Map<String, StockDetail>>(emptyMap())
    private val _portfolio = MutableStateFlow<Portfolio?>(null)
    private val _connection = MutableStateFlow(ConnectionState.OFFLINE)
    private val _lastPriceUpdate = MutableStateFlow<Long?>(null)

    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()
    val lastPriceUpdate: StateFlow<Long?> = _lastPriceUpdate.asStateFlow()
    val portfolio: StateFlow<Portfolio?> = _portfolio.asStateFlow()

    /** REST list (ordering + names) with live WebSocket prices overlaid. */
    val stocks: Flow<List<StockQuote>> = combine(baseQuotes, liveQuotes) { base, live ->
        if (base.isEmpty()) live.values.toList()
        else base.map { q -> live[q.symbol]?.let { q.copy(price = it.price, change = it.change, changePercent = it.changePercent) } ?: q }
    }

    val livePrices: StateFlow<Map<String, StockQuote>> = liveQuotes.asStateFlow()

    fun quote(symbol: String): Flow<StockQuote?> =
        stocks.map { list -> list.firstOrNull { it.symbol == symbol } }.distinctUntilChanged()

    fun detail(symbol: String): Flow<StockDetail?> =
        details.map { it[symbol] }.distinctUntilChanged()

    suspend fun refreshStocks(): Result<Unit> = singleFlight("stocks") {
        val response = retryWithBackoff { api.getStocks() }
        baseQuotes.value = response.stocks
        // Seed live prices only where the stream hasn't delivered something fresher.
        if (_lastPriceUpdate.value?.let { it > response.asOf } != true) {
            liveQuotes.update { live -> live + response.stocks.associateBy { it.symbol } }
        }
    }

    suspend fun refreshDetail(symbol: String): Result<Unit> = singleFlight("detail/$symbol") {
        val detail = retryWithBackoff { api.getStock(symbol) }
        details.update { it + (symbol to detail) }
    }

    suspend fun refreshPortfolio(): Result<Unit> = singleFlight("portfolio") {
        _portfolio.value = retryWithBackoff { api.getPortfolio() }
    }

    /**
     * Places a market order. The caller owns [idempotencyKey]; reuse it when the user retries the
     * same order so a request that reached the server (but whose response was lost) isn't
     * executed twice. Runs in the app scope so navigating away doesn't abandon an order mid-flight.
     */
    suspend fun placeOrder(request: OrderRequest, idempotencyKey: String): Result<OrderResponse> =
        scope.async {
            runCatchingNonCancel {
                retryWithBackoff(maxAttempts = 3) { api.placeOrder(idempotencyKey, request) }
                    .also { _portfolio.value = it.portfolio }
            }
        }.await()

    /** Keeps the price stream open while the caller is active; call from a lifecycle-aware scope. */
    suspend fun streamPrices() {
        priceStream.events().collect { event ->
            when (event) {
                is StreamEvent.Status -> _connection.value = event.state
                is StreamEvent.Prices -> trace("Repository.applyTicks") {
                    liveQuotes.update { live -> live + event.message.quotes.associateBy { it.symbol } }
                    _lastPriceUpdate.value = event.message.ts
                }
            }
        }
    }

    // --- request de-duplication -------------------------------------------------------------

    private val inFlightLock = Mutex()
    private val inFlight = mutableMapOf<String, Deferred<Result<Unit>>>()

    private suspend fun singleFlight(key: String, block: suspend () -> Unit): Result<Unit> {
        // Runs in the app scope, so leaving a screen mid-request still lands the result in the cache.
        val deferred = inFlightLock.withLock {
            inFlight.getOrPut(key) {
                scope.async {
                    try {
                        runCatchingNonCancel { block() }
                    } finally {
                        inFlightLock.withLock { inFlight.remove(key) }
                    }
                }
            }
        }
        return deferred.await()
    }
}

private inline fun <T> runCatchingNonCancel(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: kotlin.coroutines.cancellation.CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
