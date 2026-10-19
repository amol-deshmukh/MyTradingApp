package com.example.myapplication.data.remote

import com.example.myapplication.data.model.ConnectionState
import com.example.myapplication.data.model.NewsItem
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

sealed interface NewsStreamEvent {
    data class Status(val state: ConnectionState) : NewsStreamEvent
    data class Item(val eventId: String?, val item: NewsItem) : NewsStreamEvent
}

/**
 * Market news over Server-Sent Events. News only flows server → client, so SSE fits better than a
 * WebSocket: it's plain HTTP (works through proxies, no upgrade), and the protocol has resume built
 * in — each event carries an `id`, and a reconnecting client sends `Last-Event-ID` so the server
 * replays only what was missed.
 *
 * The server sends comment heartbeats; the client's read timeout must be longer than that interval
 * so a silent dead link is detected and the stream reconnects.
 */
class NewsStreamClient(
    client: OkHttpClient,
    private val url: String,
    private val json: Json,
) {
    private val factory = EventSources.createFactory(client)

    /** Reconnects until cancelled; [lastEventId] is read on every (re)connect to resume the stream. */
    fun events(lastEventId: () -> String?): Flow<NewsStreamEvent> =
        reconnectingStream(NewsStreamEvent::Status) { singleSession(lastEventId()) }

    /** One HTTP response's lifetime. Completes when the server ends the stream or it fails. */
    private fun singleSession(lastEventId: String?): Flow<NewsStreamEvent> = callbackFlow {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .apply { if (lastEventId != null) header("Last-Event-ID", lastEventId) }
            .build()

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                trySend(NewsStreamEvent.Status(ConnectionState.LIVE))
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (type != "news") return
                // Block rather than drop: a dropped item would be skipped forever, since the next
                // Last-Event-ID tells the server we already have it.
                runCatching { json.decodeFromString<NewsItem>(data) }
                    .onSuccess { channel.trySendBlocking(NewsStreamEvent.Item(id, it)) }
            }

            override fun onClosed(eventSource: EventSource) {
                channel.close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                channel.close()
            }
        }
        val source = factory.newEventSource(request, listener)
        awaitClose { source.cancel() }
    }
}
