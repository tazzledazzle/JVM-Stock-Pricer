# Scalable Stock Price Aggregator — Java vs Kotlin Comparative Analysis

**Author:** Terence Schumacher · **Status:** Draft

## 1. Problem Statement

Design a microservice that ingests real-time price ticks from multiple upstream
providers (exchanges/vendors), aggregates them into a single authoritative
price per symbol, and serves that price via REST (pull) and WebSocket (push)
to downstream consumers — with low tail latency and no single provider outage
taking down the feed.

This doc compares **Java** and **Kotlin** as implementation languages for this
specific system, then gives you a complete, runnable service in each so the
trade-offs aren't abstract.

## 2. Goals & Non-Goals

**Goals**
- Concurrent ingestion from N providers per symbol without thread-per-connection blowup
- Sub-100ms cache-to-client fan-out for price updates
- Graceful degradation when a provider is slow/down (bulkhead + timeout)
- Idiomatic, comprehensive comparison of language/runtime trade-offs for this workload

**Non-Goals**
- Persistence / historical time-series storage (out of scope — would be a separate service backed by a TSDB)
- Auth/authz (assume it's behind an internal gateway)
- Exactly-once delivery guarantees on the WebSocket fan-out (at-least-once, latest-value-wins is fine for a price feed)

## 3. Non-Functional Requirements

| Attribute         | Target                                                                          |
|-------------------|---------------------------------------------------------------------------------|
| Scale             | 500 symbols × 3 providers = 1,500 concurrent pollers; 10K WebSocket subscribers |
| Latency           | p99 < 100ms from provider tick to client push                                   |
| Availability      | Single provider failure must not affect other providers or symbols (bulkhead)   |
| Consistency       | Eventual — last-write-wins per symbol, timestamp-ordered                        |
| Concurrency model | Must not require 1,500+ native OS threads                                       |

## 4. Architecture

```
[Provider A]\
[Provider B]-→ (poll/subscribe, concurrent) → [Aggregator Core] → [Cache: ConcurrentHashMap<Symbol, Price>]
[Provider C]/                                        |                          |
                                                       ↓                          ↓
                                              [Broadcast Bus]           [REST GET /prices/{symbol}]
                                                       ↓
                                          [WebSocket /ws/prices — fan-out to subscribers]
```

Each provider is polled/subscribed to **independently and concurrently**, per
symbol. A single slow or dead provider must not block others — this is the
crux of why the concurrency primitive matters here, and why this comparison
isn't just syntax preference.

## 5. Java vs Kotlin — Trade-off Analysis

### 5.1 Concurrency Model (the decision that matters most for this system)

| Dimension                                             | Java 21 (Virtual Threads)                                                                                                  | Kotlin (Coroutines)                                                                                                               |
|-------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------|
| Primitive                                             | `Thread` (virtual, JEP 444), scheduled on carrier threads by the JVM                                                       | `suspend fun` + `CoroutineScope`, scheduled by a user-space dispatcher                                                            |
| Mental model                                          | "Just write blocking code" — the JVM parks/unparks for you                                                                 | Structured concurrency — explicit scopes, cancellation propagates through a job tree                                              |
| 1,500 pollers                                         | Trivial: `Executors.newVirtualThreadPerTaskExecutor()`, one virtual thread per poller, blocking HTTP client calls are fine | Trivial: `launch` 1,500 coroutines in a `supervisorScope`, each does a suspending call                                            |
| Cancellation/timeout on a stuck provider              | `Future.get(timeout)` or `CompletableFuture.orTimeout()` — workable but bolted-on                                          | `withTimeout {}` is a first-class coroutine builder; cancellation is cooperative and propagates automatically to children         |
| Bulkhead (one provider failing shouldn't kill others) | Each virtual thread already isolates failures if you catch exceptions per-task                                             | `supervisorScope` + per-child `try/catch` gives you this natively, and a failed child doesn't cancel siblings                     |
| Backpressure / streaming aggregation                  | Needs a reactive library (Reactor/RxJava) or manual queues for true streaming composition                                  | `Flow` gives you backpressure-aware streaming composition (`combine`, `debounce`, `conflate`) out of the box                      |
| Debuggability                                         | Stack traces look like normal blocking code — very debuggable                                                              | Coroutine stack traces are reconstructed (with `kotlinx-coroutines-debug`); slightly less familiar but tooling has matured a lot  |
| Maturity for this exact use case                      | Virtual threads are new (Java 21, Sept 2023) — great for blocking I/O, less opinionated about structured composition       | Coroutines are 6+ years mature; `Flow` was purpose-built for exactly this "many concurrent sources → one aggregated stream" shape |

**Read on this**: for pure "run 1,500 blocking calls concurrently," virtual threads win on simplicity — it's just blocking code. For "aggregate 3 concurrent streams per symbol with per-provider timeout, cancellation, and backpressure," `Flow` + structured concurrency is a better semantic fit because that composition is what it was designed for. Both are used below so you can feel the difference directly.

### 5.2 Type System & Null Safety

| Dimension      | Java                                                                                     | Kotlin                                                                                                 |
|----------------|------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------|
| Null safety    | Optional-based, opt-in (`Optional<Price>`), NPEs still possible everywhere else          | Null-safety in the type system (`Price?` vs `Price`), compiler-enforced at every call site             |
| Data carriers  | Records (Java 16+) — immutable, concise, but no built-in `copy()` or structural defaults | `data class` — `copy()`, destructuring, `equals`/`hashCode`/`toString` generated, named + default args |
| Relevance here | A `null` price silently propagating to a client is a real bug class in a price feed      | `Price?` forces you to handle "no price yet for this symbol" at the type level, not at runtime         |

For a price feed specifically — where "is there a value or not" is a constant question (symbol just started, provider hasn't reported yet) — Kotlin's nullable types make the "no data yet" case impossible to accidentally skip.

### 5.3 Framework Ecosystem

| Dimension              | Spring Boot (Java-first)                                                           | Ktor (Kotlin-native)                                                            |
|------------------------|------------------------------------------------------------------------------------|---------------------------------------------------------------------------------|
| Startup/footprint      | Heavier (reflection-based DI, classpath scanning); improving with AOT/native-image | Lightweight, minimal reflection, faster cold start                              |
| Virtual thread support | First-class as of Spring Boot 3.2 (`spring.threads.virtual.enabled=true`)          | N/A — Ktor's model is coroutine-native, not virtual-thread-native               |
| WebSocket support      | `spring-websocket` — mature, more boilerplate                                      | `ktor-server-websockets` — coroutine-native, `SharedFlow` maps directly onto it |
| Ecosystem breadth      | Enormous — this is Spring's biggest advantage, still                               | Smaller but sufficient; growing fast                                            |
| DI style               | Annotation-heavy (`@Autowired`, `@Service`)                                        | Usually explicit constructor injection or Koin — more visible, less "magic"     |

Both frameworks run on Kotlin *and* Java equally well (Spring has excellent Kotlin support; Ktor is Kotlin-only). The framework choice is somewhat separable from the language choice — the harder coupling is Ktor↔Kotlin.

### 5.4 Serialization

| Dimension      | Jackson (Java default)                                                 | kotlinx.serialization                                                    |
|----------------|------------------------------------------------------------------------|--------------------------------------------------------------------------|
| Mechanism      | Runtime reflection                                                     | Compile-time codegen (Kotlin compiler plugin)                            |
| Data class fit | Needs a no-arg constructor or extra annotations for records/immutables | Works natively with `data class`, including nullable fields and defaults |
| Performance    | Reflection has overhead (usually amortized/cached)                     | No reflection at runtime — faster serialization, smaller attack surface  |

### 5.5 Build Tooling & Team Fit

Given your background running CMake→Bazel and Gradle/Develocity work, this
is the dimension you'll evaluate fastest yourself, so I'll keep it brief:

| Dimension               | Java                                                                                                     | Kotlin                                                                                                       |
|-------------------------|----------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------|
| Build tool              | Gradle or Maven, both first-class                                                                        | Gradle (Kotlin DSL is now the Gradle default) — better IDE support for `build.gradle.kts` than Groovy        |
| Incremental compilation | `javac` — fast, simple                                                                                   | `kotlinc` — historically slower than `javac`, materially improved with K2 compiler (stable since Kotlin 2.0) |
| Interop                 | Java can't call Kotlin-only features (extension functions, coroutines without adapters) without friction | Kotlin calls Java transparently — near-zero friction consuming existing Java libs                            |
| Hiring/onboarding       | Larger talent pool, near-universal familiarity                                                           | Smaller pool but essentially every Java dev ramps in days; the JVM/tooling knowledge transfers 1:1           |

### 5.6 Testing

| Dimension         | Java             | Kotlin                                                                                                                                                |
|-------------------|------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------|
| Framework         | JUnit 5, Mockito | JUnit 5 (same runner), MockK (built for coroutines/`suspend fun` mocking)                                                                             |
| Coroutine testing | N/A              | `kotlinx-coroutines-test` gives you `runTest` + virtual time control — genuinely useful for testing pollers with timeouts/retries without real sleeps |

## 6. Recommendation (with explicit assumptions)

**Given** this system's core challenge is composing many concurrent, independently-failing, timeout-bound streams into one aggregated cache — and given a small team already fluent in Kotlin (per your WOD Ledger / Ktor work) — **Kotlin + Ktor + coroutines/Flow** is the stronger fit: `Flow`'s `combine`/timeout/cancellation operators map directly onto the aggregation problem, and null-safety removes a whole bug class from "is there a current price yet." 

**The case for Java + virtual threads instead**: if your team is Java-only, or you need Spring's ecosystem breadth (e.g., heavy Spring Cloud/Spring Security integration already in place), virtual threads get you 90% of the concurrency win with zero new language to learn, and Spring Boot 3.2's virtual thread support is production-ready.

Both are demonstrated fully below — build both, benchmark them against your actual latency targets, and let that data settle it rather than my preference.

## 7. Trade-offs Considered

| Option                                 | Pros                                                                 | Cons                                                                                                     | Decision                                               |
|----------------------------------------|----------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------|--------------------------------------------------------|
| Kotlin + Ktor + coroutines             | Best semantic fit for stream aggregation; null-safety; concise       | Smaller ecosystem; team must know Kotlin                                                                 | **Recommended** for greenfield with Kotlin-fluent team |
| Java + Spring Boot + virtual threads   | Familiar; huge ecosystem; virtual threads simplify concurrency a lot | No native structured-concurrency composition (`StructuredTaskScope` is still a preview API as of JDK 21) | Recommended if team/org is Java-committed              |
| Java + Spring Boot + Reactor (WebFlux) | Mature reactive story                                                | Highest cognitive overhead of all four options; not worth it now that virtual threads exist              | Rejected                                               |

## 8. Operational Considerations

- **Deployment**: both services below are stateless — scale horizontally behind a load balancer; WebSocket fan-out means you need sticky sessions or a shared pub/sub (Redis pub/sub, Kafka) once you run >1 instance.
- **Observability**: expose `/metrics` (Micrometer in both stacks) — track per-provider latency, staleness (time since last tick per symbol), and WebSocket subscriber count.
- **Failure mode**: a dead provider should degrade to "serve last known price + staleness flag," never a 500.

## 9. What's in this deliverable

Two complete, runnable single-service implementations, side by side:

```
kotlin-aggregator/   — Ktor + coroutines + Flow, kotlinx.serialization
java-aggregator/     — Spring Boot 3.2 + virtual threads, Jackson
```

Both simulate 3 providers with random-walk prices for `AAPL`, `MSFT`, `GOOG`,
aggregate via simple average, cache the latest price per symbol, and expose:

- `GET /prices/{symbol}` — latest aggregated price
- `GET /prices` — all symbols
- `WS /ws/prices` — pushes every update as JSON

Run either with the instructions in its own `README.md`.
