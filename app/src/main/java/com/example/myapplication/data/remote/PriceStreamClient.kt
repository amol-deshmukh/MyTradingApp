package com.example.myapplication.data.remote

import androidx.tracing.trace
import com.example.myapplication.data.model.ConnectionState
import com.example.myapplication.data.model.PriceStreamMessage
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed interface StreamEvent {
    data class Status(val state: ConnectionState) : StreamEvent
    data class Prices(val message: PriceStreamMessage) : StreamEvent
}

/**
 * Live prices over WebSocket. A persistent connection avoids paying request latency (and HTTP
 * overhead) for every update, and the server pushes as soon as prices change instead of the
 * client polling.
 *
 * The returned flow reconnects forever with exponential backoff + jitter until the collector is
 * cancelled. On every (re)connect the server sends a full snapshot, so no gap recovery is needed.
 */
class PriceStreamClient(
    private val client: OkHttpClient,
    private val url: String,
    private val json: Json,
) {
    fun events(): Flow<StreamEvent> = reconnectingStream(StreamEvent::Status, ::singleSession)

    /** One socket's lifetime. Completes when the socket closes or fails. */
    private fun singleSession(): Flow<StreamEvent> = callbackFlow {
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                trySend(StreamEvent.Status(ConnectionState.LIVE))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { trace("PriceStream.decode") { json.decodeFromString<PriceStreamMessage>(text) } }
                    .onSuccess { trySend(StreamEvent.Prices(it)) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                channel.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                channel.close()
            }
        }
        val socket = client.newWebSocket(Request.Builder().url(url).build(), listener)
        awaitClose { socket.close(1000, "client closed") }
    }
}
