package com.tazzledazzle.stockagg

import com.tazzledazzle.stockagg.model.AggregatedPrice
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

private val SYMBOLS = listOf("AAPL", "MSFT", "GOOG")

fun main() {
    val providers = listOf(
        PriceProvider(name = "vendor-a", basePrice = 190.0),
        PriceProvider(name = "vendor-b", basePrice = 190.5),
        PriceProvider(name = "vendor-c", basePrice = 189.5, flakyEveryNTicks = 5)
    )
    val aggregator = Aggregator(providers = providers, perTickTimeoutMillis = 1_000)

    // Dedicated scope for background ingestion. Do NOT reuse Ktor's
    // Application scope for this -- its dispatcher is wrapped by
    // ClassLoaderAwareContinuationInterceptor, which creates a new
    // interceptor instance on each resumption and breaks Flow's
    // SafeCollector context-identity check (the "Flow invariant is
    // violated" error). Dispatchers.Default has no such wrapping.
    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) {
            json(Json { prettyPrint = true })
        }
        install(WebSockets) {
            pingPeriod = 15.seconds.toJavaDuration()
        }

        // Launch ingestion on backgroundScope, not `this`.
        SYMBOLS.forEach { symbol -> aggregator.start(backgroundScope, symbol) }

        // Cancel the background work when the server shuts down.
        environment.monitor.subscribe(ApplicationStopped) {
            backgroundScope.cancel()
        }

        routing {
            get("/prices") {
                call.respond(aggregator.allLatest())
            }

            get("/prices/{symbol}") {
                val symbol = call.parameters["symbol"]?.uppercase()
                val price = symbol?.let { aggregator.latest(it) }
                if (price == null) {
                    call.respond(io.ktor.http.HttpStatusCode.NotFound, mapOf("error" to "no price yet for $symbol"))
                } else {
                    call.respond(price)
                }
            }

            webSocket("/ws/prices") {
                val json = Json
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