package com.example.myapplication.data.remote

import com.example.myapplication.data.model.ApiError
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/**
 * Retries [block] on transient failures (I/O errors, timeouts, 408/429/5xx) using exponential
 * backoff with jitter. Honors `Retry-After` when the server sends it. Client errors (4xx) are not
 * retried because repeating them cannot succeed.
 *
 * Only use for idempotent calls — GETs, or POSTs protected by an Idempotency-Key.
 */
suspend fun <T> retryWithBackoff(
    maxAttempts: Int = 4,
    initialDelayMs: Long = 500,
    maxDelayMs: Long = 8_000,
    block: suspend () -> T,
): T {
    var backoff = initialDelayMs
    repeat(maxAttempts - 1) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!e.isTransient()) throw e
            val retryAfterMs = (e as? HttpException)?.response()?.headers()?.get("Retry-After")
                ?.toLongOrNull()?.times(1000)
            delay(retryAfterMs ?: (backoff + Random.nextLong(backoff / 2 + 1)))
            backoff = (backoff * 2).coerceAtMost(maxDelayMs)
        }
    }
    return block()
}

private fun Throwable.isTransient(): Boolean = when (this) {
    is IOException -> true
    is HttpException -> code() == 408 || code() == 429 || code() >= 500
    else -> false
}

private val errorJson = Json { ignoreUnknownKeys = true }

/** Converts any network/API failure into a message suitable for the UI. */
fun Throwable.toUserMessage(): String = when (this) {
    is SocketTimeoutException -> "The server is taking too long to respond."
    is IOException -> "Network problem. Check your connection."
    is HttpException -> response()?.errorBody()?.string()
        ?.let { runCatching { errorJson.decodeFromString<ApiError>(it).message }.getOrNull() }
        ?: "Server error (${code()})"
    else -> message ?: "Something went wrong"
}
