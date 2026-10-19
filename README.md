# Sample Trading App

Jetpack Compose trading app backed by a local mock server that simulates a slow, flaky network.
It demonstrates the three common transport styles side by side:

| Data | Transport | Why |
|---|---|---|
| Stock list, details, portfolio, orders | REST (Retrofit + kotlinx.serialization) | Request/response |
| Live prices | WebSocket (`/ws/prices`) | High-frequency push, persistent connection |
| Market news feed | Server-Sent Events (`/sse/news`) | One-way push over plain HTTP, built-in resume |

## Quick start

```bash
node mock-server/server.js          # Node 18+, no dependencies; listens on :8080
./gradlew :app:installDebug         # emulator reaches the host at 10.0.2.2
```

On a physical device, run `adb reverse tcp:8080 tcp:8080` and use the `benchmark` build type, or
point the URLs in `app/build.gradle.kts` at your machine's LAN IP.

## Screens

- **Markets**: all stocks with live prices that flash on each tick. Pull to refresh.
- **Portfolio**: cash, holdings and P&L, revalued live from the price stream.
- **Stock detail**: stats, price chart and buy/sell orders.
- **News**: live SSE feed of market news, earnings, analyst ratings and price alerts. Filter by
  category; tap a ticker to open its detail screen.

Each tab shows a connection pill (Live / Connecting / Reconnecting / Offline).

## Architecture

```
ui/<screen>/      Screen (stateless composable + previews) and ViewModel exposing StateFlow<UiState>
data/             TradingRepository: single source of truth, in-memory cache exposed as flows
data/remote/      TradingApi (REST), PriceStreamClient (WS), NewsStreamClient (SSE),
                  Reconnect.kt (shared backoff loop), Retry.kt (REST retry + error messages)
```

Manual DI: `TradingApplication` builds the OkHttp clients and the repository.

### Handling latency and failures

- **Stale-while-revalidate**: screens render cached data instantly and refresh in the background.
- **Request de-duplication**: identical in-flight calls share one network request.
- **Retries**: idempotent calls retry with exponential backoff + jitter and honor `Retry-After`.
  4xx errors are not retried.
- **Safe orders**: every order carries an `Idempotency-Key`, so a retry after a lost response never
  executes twice.
- **Streams reconnect forever** with exponential backoff (capped at 30s) and jitter.

### Server-Sent Events (News)

- Each event has an `id:`, `event: news` and a JSON `data:` payload.
- **Resume**: on reconnect the client sends `Last-Event-ID`; the server replays only missed events
  (from a 50-item buffer). New clients get the latest 20.
- **Dead-link detection**: the server sends a `: keep-alive` comment every 15s; the SSE client's
  30s read timeout triggers a reconnect if the link goes silent.
- **Lifecycle**: the stream is open only while the News screen is visible (5s grace for rotation).
  The feed is cached, so returning to the tab is instant and resumes from the last event.
- No event is ever dropped on the client: a skipped item would be lost, because the next
  `Last-Event-ID` tells the server it was received.

## Mock server

`mock-server/server.js` serves REST, WebSocket and SSE from one port. Tune it with env vars:

| Variable | Default | Effect |
|---|---|---|
| `LATENCY_MS` | 1500 | Base delay on every REST response and WS push |
| `JITTER_MS` | 1000 | Random extra delay `0..JITTER_MS` |
| `FAILURE_RATE` | 0.1 | Chance of 503s / dropped responses; when > 0, SSE streams are also cut every 30–90s |
| `EXTRA_STOCKS` | 0 | Synthetic stocks for performance tests |
| `MOCK_PORT` | 8080 | Listen port |

```bash
LATENCY_MS=3000 FAILURE_RATE=0.3 node mock-server/server.js   # stress retries and SSE resume
curl -N http://localhost:8080/sse/news                         # watch the raw event stream
curl -N -H 'Last-Event-ID: 5' http://localhost:8080/sse/news   # resume after event 5
```

## Build types

| Type | Backend | Notes |
|---|---|---|
| `debug` | `10.0.2.2:8080` | Emulator → host, HTTP logging on |
| `release` | `10.0.2.2:8080` | R8 minified; swap in real HTTPS URLs before shipping |
| `benchmark` | `localhost:8080` | Release-like, debug-signed; used by macrobenchmarks via `adb reverse` |

Cleartext HTTP is allowed only for `10.0.2.2`, `localhost` and `127.0.0.1`
(`res/xml/network_security_config.xml`). Use HTTPS/WSS in production.

## Performance tests

The `:benchmark` module measures startup, scroll jank, live-update cost and main-thread blocking
on the Markets list. See [perf/README.md](perf/README.md).

```bash
perf/run-benchmarks.sh v1.1
perf/compare.py perf/results/v1.0 perf/results/v1.1
```
