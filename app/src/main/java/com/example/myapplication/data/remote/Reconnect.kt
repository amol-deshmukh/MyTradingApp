package com.example.myapplication.data.remote

import com.example.myapplication.data.model.ConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlin.random.Random

/**
 * Runs [session] (one connection's lifetime) forever, reconnecting with exponential backoff +
 * jitter until the collector is cancelled. [status] wraps connection-state changes in the stream's
 * event type; a session signals a successful connect by emitting `status(LIVE)`, which resets the
 * backoff.
 */
internal fun <T> reconnectingStream(status: (ConnectionState) -> T, session: () -> Flow<T>): Flow<T> = channelFlow {
    val live = status(ConnectionState.LIVE)
    var attempt = 0
    while (true) {
        send(status(if (attempt == 0) ConnectionState.CONNECTING else ConnectionState.RECONNECTING))
        session().collect { event ->
            if (event == live) attempt = 0
            send(event)
        }
        attempt++
        send(status(if (attempt >= 3) ConnectionState.OFFLINE else ConnectionState.RECONNECTING))
        val backoff = (1_000L shl (attempt - 1).coerceAtMost(5)).coerceAtMost(30_000L)
        delay(backoff + Random.nextLong(backoff / 2))
    }
}
