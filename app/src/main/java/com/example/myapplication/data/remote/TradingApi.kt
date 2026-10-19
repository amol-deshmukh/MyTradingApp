package com.example.myapplication.data.remote

import com.example.myapplication.data.model.OrderRequest
import com.example.myapplication.data.model.OrderResponse
import com.example.myapplication.data.model.Portfolio
import com.example.myapplication.data.model.StockDetail
import com.example.myapplication.data.model.StockListResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/** REST endpoints: request/response data that doesn't need to be pushed. */
interface TradingApi {
    @GET("api/v1/stocks")
    suspend fun getStocks(): StockListResponse

    @GET("api/v1/stocks/{symbol}")
    suspend fun getStock(@Path("symbol") symbol: String): StockDetail

    @GET("api/v1/portfolio")
    suspend fun getPortfolio(): Portfolio

    /**
     * Orders are not naturally idempotent, so the client sends an [idempotencyKey]. Retrying with
     * the same key after a timeout returns the original result instead of placing a second order.
     */
    @POST("api/v1/orders")
    suspend fun placeOrder(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: OrderRequest,
    ): OrderResponse
}
