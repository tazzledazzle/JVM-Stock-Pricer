## **💻 3.Concurrency & Multithreading**

**🧪 Project: Stock Price Aggregator (Java concurrency + Kotlin coroutines)**

- **Stack**: Java 17 + Kotlin, Spring Boot, WebSockets
- **Details**: Periodically fetch stock prices from multiple APIs and serve over a real-time WebSocket stream.
- **Showcases**:
    - CompletableFuture + ExecutorService in Java
    - Kotlin’s suspend, flow, and structured concurrency
    - Performance comparison between both

----


# Stock Price Aggregator — Kotlin / Ktor

Coroutine-native implementation. See the top-level
`stock-aggregator-java-vs-kotlin.md` for the full trade-off analysis.

## Requirements
- JDK 21+
- Gradle 8.5+ (or use `./gradlew` if you add the wrapper: `gradle wrapper --gradle-version 8.5`)

## Run

    gradle run

Server starts on `http://localhost:8080`.

## Try it

    curl http://localhost:8080/prices
    curl http://localhost:8080/prices/AAPL
    websocat ws://localhost:8080/ws/prices

## What to look at first

- `Provider.kt` — the simulated upstream feed, modeled as a `Flow`. Note
  `flakyEveryNTicks` on `vendor-c`, used to exercise the timeout path.
- `Aggregator.kt` — the concurrency core. `supervisorScope` + `produceIn` +
  `withTimeoutOrNull` is the whole bulkhead/timeout story in ~15 lines.
- `Main.kt` — wires the aggregator into Ktor's routing and WebSocket support.
  `aggregator.updates` (a `SharedFlow`) is `collect`-ed directly inside the
  WebSocket handler — no manual broadcast/observer bookkeeping needed.

## Notes on running this in a sandboxed/offline environment

This project needs Maven Central access to resolve Ktor/coroutines
dependencies on first build. If you're in a network-restricted environment,
run it somewhere with normal internet access, or pre-populate a local Gradle
cache.