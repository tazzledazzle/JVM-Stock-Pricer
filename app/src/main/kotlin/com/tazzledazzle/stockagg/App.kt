package com.tazzledazzle.stockagg


import com.tazzledazzle.aggregator.AppConfig
import com.tazzledazzle.stockagg.providers.PriceProvider
import com.tazzledazzle.stockagg.providers.StooqProvider
import com.tazzledazzle.stockagg.providers.YahooFinanceProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

fun main() {
    val config = AppConfig.fromEnv()

    val httpClient = HttpClient(CIO) {
        install(ClientContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        expectSuccess = false
    }

    val providers: List<PriceProvider> = listOf(
        YahooFinanceProvider(httpClient),
        StooqProvider(httpClient)
    )

    val aggregator = Aggregator(
        providers = providers,
        symbols = config.symbols,
        pollIntervalMs = config.pollIntervalMs,
        providerTimeoutMs = config.providerTimeoutMs,
        maxRetries = config.maxRetries,
        retryBaseDelayMs = config.retryBaseDelayMs,
        historySize = config.historySize,
        circuitBreakerFailureThreshold = config.circuitBreakerFailureThreshold,
        circuitBreakerResetMs = config.circuitBreakerResetMs
    )

    // Deliberately independent from Ktor's engine-scoped dispatcher. Sharing that scope
    // for a long-lived background Flow was the root cause of the
    // "Flow invariant is violated" crash from the earlier session - Ktor's
    // ClassLoaderAwareContinuationInterceptor swaps interceptor instances across
    // resumptions on its own scope, which breaks SafeCollector's identity check.
    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    aggregator.start(backgroundScope)

    embeddedServer(Netty, port = config.port) {
        install(ContentNegotiation) {
            json(Json { prettyPrint = true })
        }
        install(WebSockets) {
            pingPeriod = 15.seconds
        }

        routing {
            // Serves src/main/resources/static/index.html at "/" plus its assets.
            // (index = "index.html" is the default for this function, kept explicit here for clarity.)
            staticResources("/", "static", index = "index.html")

            get("/symbols") {
                call.respond(config.symbols)
            }

            get("/prices") {
                call.respond(aggregator.allLatest())
            }

            get("/prices/{symbol}") {
                val symbol = call.parameters["symbol"]?.uppercase()
                val price = symbol?.let { aggregator.latest(it) }
                if (price == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "no price yet for $symbol"))
                } else {
                    call.respond(price)
                }
            }

            get("/prices/{symbol}/history") {
                val symbol = call.parameters["symbol"]?.uppercase()
                if (symbol == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "symbol required"))
                } else {
                    call.respond(aggregator.historyOf(symbol))
                }
            }

            webSocket("/ws/prices") {
                val json = Json
                // Push whatever's cached immediately so late-connecting clients aren't
                // stuck staring at a blank chart until the next poll tick.
                aggregator.allLatest().forEach { price ->
                    send(Frame.Text(json.encodeToString(AggregatedPrice.serializer(), price)))
                }
                aggregator.updates.collect { price ->
                    send(Frame.Text(json.encodeToString(AggregatedPrice.serializer(), price)))
                }
            }
        }
    }.start(wait = true)
}
