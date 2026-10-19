package com.example.myapplication

import android.app.Application
import com.example.myapplication.data.TradingRepository
import com.example.myapplication.data.remote.PriceStreamClient
import com.example.myapplication.data.remote.TradingApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

class TradingApplication : Application() {
    lateinit var repository: TradingRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val json = Json { ignoreUnknownKeys = true }

        // Timeouts are sized for a high-latency link (the mock server adds 1.5–2.5s per call),
        // with connection reuse so we don't pay TCP setup on every request.
        val baseClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .build()

        val restClient = baseClient.newBuilder()
            .callTimeout(20, TimeUnit.SECONDS)
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
                }
            }
            .build()

        // WebSocket: no read timeout (the stream is long-lived); ping frames detect dead links.
        val wsClient = baseClient.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()

        val api = Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(restClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(TradingApi::class.java)

        repository = TradingRepository(
            api = api,
            priceStream = PriceStreamClient(wsClient, BuildConfig.WS_PRICES_URL, json),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }
}
