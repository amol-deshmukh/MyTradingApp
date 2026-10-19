package com.example.myapplication.data.model

import kotlinx.serialization.Serializable

@Serializable
data class StockQuote(
    val symbol: String,
    val name: String = "",
    val price: Double,
    val change: Double,
    val changePercent: Double,
)

@Serializable
data class StockListResponse(
    val stocks: List<StockQuote>,
    val asOf: Long,
)

@Serializable
data class StockDetail(
    val symbol: String,
    val name: String,
    val price: Double,
    val change: Double,
    val changePercent: Double,
    val sector: String,
    val description: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val prevClose: Double,
    val volume: Long,
    val marketCap: Double,
    val peRatio: Double,
    val week52High: Double,
    val week52Low: Double,
    val history: List<Double>,
)

@Serializable
data class Holding(
    val symbol: String,
    val name: String,
    val quantity: Int,
    val avgPrice: Double,
    val lastPrice: Double,
)

@Serializable
data class Portfolio(
    val cash: Double,
    val holdings: List<Holding>,
)

@Serializable
enum class OrderSide { BUY, SELL }

@Serializable
data class OrderRequest(
    val symbol: String,
    val side: OrderSide,
    val quantity: Int,
)

@Serializable
data class OrderResponse(
    val orderId: String,
    val symbol: String,
    val side: OrderSide,
    val quantity: Int,
    val filledPrice: Double,
    val total: Double,
    val status: String,
    val filledAt: String,
    val portfolio: Portfolio,
)

@Serializable
data class ApiError(
    val error: String,
    val message: String,
)

/** Messages pushed by the server over the price WebSocket. */
@Serializable
data class PriceStreamMessage(
    val type: String, // "snapshot" | "ticks"
    val ts: Long,
    val quotes: List<StockQuote>,
)

enum class ConnectionState { CONNECTING, LIVE, RECONNECTING, OFFLINE }
