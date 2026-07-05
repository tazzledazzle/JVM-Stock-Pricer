# Stock Price Aggregator — Live Data (Kotlin / Ktor)

Productionized version of the Kotlin/Ktor implementation from
`stock-aggregator-java-vs-kotlin.md`: real prices from two independent free
vendors, retry + circuit breaker per provider/symbol, in-memory price
history, and a live Chart.js dashboard served by Ktor itself.

## What changed from the simulated version

| Area          | Before                              | Now                                                                 |
|---------------|--------------------------------------|----------------------------------------------------------------------|
| Data source   | 3 fake `PriceProvider`s, random walk | `YahooFinanceProvider` (chart API) + `StooqProvider` (CSV), both free, no API key |
| Resilience    | Simulated stall on vendor-c          | Real per-provider retry (exponential backoff) + circuit breaker, keyed per `provider:symbol` |
| Data shape    | Latest price only                    | Latest price **and** a bounded in-memory history ring buffer per symbol |
| Config        | Hardcoded in `Main.kt`               | Everything tunable via env vars (see below)                          |
| Frontend      | None (curl/websocat only)            | `/` serves a Chart.js dashboard, live-updating over the existing WebSocket |

The aggregation/bulkhead architecture itself (per-symbol poll loop,
`supervisorScope`, `withTimeoutOrNull`, `SharedFlow` fan-out) is unchanged —
it was already the right shape for "combine N independently-failing sources
into one price," and now it's protecting against real Yahoo/Stooq outages
instead of a scripted one.

## Requirements

- JDK 21+
- Gradle 8.5+ (or generate the wrapper: `gradle wrapper --gradle-version 8.5`)
- Outbound internet access to `query1.finance.yahoo.com` and `stooq.com`
  (both are free, unofficial/undocumented-by-contract endpoints — expect
  occasional rate-limiting or schema drift; that's exactly what the
  circuit breaker and dual-vendor design are for)

## Run

    gradle run

Then open **http://localhost:8080** for the live dashboard, or hit the API
directly:

    curl http://localhost:8080/prices
    curl http://localhost:8080/prices/AAPL
    curl http://localhost:8080/prices/AAPL/history
    websocat ws://localhost:8080/ws/prices

## Configuration (environment variables)

| Variable                  | Default            | Meaning                                                  |
|---------------------------|---------------------|-----------------------------------------------------------|
| `SYMBOLS`                 | `AAPL,MSFT,GOOG`    | Comma-separated tickers to track                          |
| `POLL_INTERVAL_MS`        | `5000`              | How often each symbol's poll loop queries providers        |
| `PROVIDER_TIMEOUT_MS`     | `3000`              | Per-attempt timeout before a provider call is abandoned    |
| `MAX_RETRIES`             | `2`                 | Extra attempts (with exponential backoff) before giving up |
| `RETRY_BASE_DELAY_MS`     | `200`               | Base delay for exponential backoff (200, 400, 800, ...)    |
| `HISTORY_SIZE`            | `200`               | Max points kept per symbol in the in-memory ring buffer    |
| `CB_FAILURE_THRESHOLD`    | `3`                 | Consecutive failures before a provider/symbol circuit opens |
| `CB_RESET_TIMEOUT_MS`     | `30000`             | How long a circuit stays open before allowing a probe request |
| `PORT`                    | `8080`              | HTTP/WebSocket port                                        |

Example:

    SYMBOLS=AAPL,TSLA,NVDA POLL_INTERVAL_MS=10000 gradle run

## What to look at first

- **`providers/YahooFinanceProvider.kt`** / **`providers/StooqProvider.kt`** —
  two genuinely independent vendors implementing the same `PriceProvider`
  interface. This is what makes the bulkhead in `Aggregator.kt` meaningful:
  if Yahoo rate-limits you, Stooq keeps the symbol alive (and vice versa).
- **`CircuitBreaker.kt`** — per-`provider:symbol` state machine
  (CLOSED/OPEN/HALF_OPEN). One vendor failing on one symbol doesn't open the
  circuit for its other symbols or for the other vendor.
- **`Aggregator.kt`** — `fetchFromProvider` composes circuit-breaker
  gating → timeout → retry for each call; `pollLoop` fans concurrent
  provider calls out with `async`/`awaitAll` inside `supervisorScope`, so
  one provider throwing never cancels the other's in-flight call.
- **`src/main/resources/static/index.html`** — plain JS, no build step.
  Fetches `/symbols` and `/prices/{symbol}/history` on load to seed the
  chart, then subscribes to `/ws/prices` for live updates. Kept as a static
  resource (rather than a Kotlin-templated string) so it's editable without
  touching the server code.

## Known rough edges (real talk, not simulated)

- Yahoo's chart endpoint and Stooq's CSV endpoint are both **unofficial** —
  they can change shape or start blocking datacenter IPs without warning.
  If both vendors go down simultaneously you'll see `staleMillis` climb on
  every symbol; that's the intended degrade-to-last-known-price behavior,
  not a bug.
- Stooq's timestamp is treated as US/Eastern for all tickers, which is an
  approximation good enough for a staleness indicator but not for anything
  that needs exchange-accurate timestamps.
- History is in-memory only — restarting the process clears it. Swapping
  the `ConcurrentHashMap<String, ArrayDeque<PricePoint>>` in `Aggregator`
  for a real store (Postgres, Redis, a TSDB) is the natural next step and
  was explicitly a non-goal in the original design doc.

## Notes on running this in a sandboxed/offline environment

This project needs Maven Central access to resolve dependencies on first
build, and outbound HTTPS to Yahoo Finance/Stooq at runtime. If you're
building or testing in a network-restricted environment, do it somewhere
with normal internet access, or pre-populate a local Gradle cache and mock
the two providers for offline testing.
