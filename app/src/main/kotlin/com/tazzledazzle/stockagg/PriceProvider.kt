package com.tazzledazzle.stockagg

import com.tazzledazzle.stockagg.model.Tick
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

/**
 * Simulates an upstream price provider (in real life: a WebSocket feed,
 * FIX connection, or REST poller against Alpha Vantage / Polygon / etc).
 *
 * Modeled as a suspending [Flow] so it composes naturally with structured
 * concurrency: cancelling the collector cancels the underlying "connection"
 * (the delay loop) automatically -- no manual thread interruption needed.
 *
 * A provider can be "flaky" to demonstrate the bulkhead behavior: a flaky
 * provider occasionally stalls far longer than the timeout, and the
 * aggregator must not let that block other providers or other symbols.
 */
class PriceProvider(
    val name: String,
    private val basePrice: Double,
    private val flakyEveryNTicks: Int? = null
) {
    fun ticks(symbol: String): Flow<Tick> = flow {
        var price = basePrice
        var tickCount = 0
        while (true) {
            tickCount++

            // Simulate an occasional slow/stalled provider.
            val isFlakyTick = flakyEveryNTicks != null && tickCount % flakyEveryNTicks == 0
            val delayMillis = if (isFlakyTick) 5_000L else Random.nextLong(200, 800)
            delay(delayMillis)

            // Random walk the price.
            price += Random.nextDouble(-0.5, 0.5)
            price = price.coerceAtLeast(0.01)

            emit(
                Tick(
                    symbol = symbol,
                    provider = name,
                    price = price,
                    timestampMillis = System.currentTimeMillis()
                )
            )
        }
    }
}