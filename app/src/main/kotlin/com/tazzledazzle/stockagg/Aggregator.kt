package com.tazzledazzle.stockagg

import com.tazzledazzle.stockagg.model.AggregatedPrice
import com.tazzledazzle.stockagg.model.Tick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.toList
import kotlin.time.Duration.Companion.milliseconds

/**
 * Aggregates concurrent provider streams per symbol into a single cached
 * [AggregatedPrice], and broadcasts every update over a [SharedFlow] for
 * WebSocket fan-out.
 *
 * Concurrency shape:
 *  - one parent coroutine per symbol (launched in [start])
 *  - inside a `supervisorScope`, one child coroutine per provider
 *  - each provider's flow is turned into a channel via `produceIn`, so
 *    waiting for its next tick can be raced against `withTimeoutOrNull` --
 *    a stalled provider just gets skipped for a beat, nothing else blocks
 *  - `supervisorScope` means one provider throwing an exception does not
 *    cancel its siblings (the bulkhead requirement from the NFRs)
 */
class Aggregator(
    private val providers: List<PriceProvider>,
    private val perTickTimeoutMillis: Long = 1_000
) {
    private val cache = ConcurrentHashMap<String, AggregatedPrice>()

    private val _updates = MutableSharedFlow<AggregatedPrice>(replay = 0, extraBufferCapacity = 64)
    val updates: SharedFlow<AggregatedPrice> = _updates.asSharedFlow()

    fun latest(symbol: String): AggregatedPrice? = cache[symbol]
    fun allLatest(): List<AggregatedPrice> = cache.values.toList()

    /** Launches the ingestion pipeline for a symbol. Runs until [scope] is cancelled. */
    fun start(scope: CoroutineScope, symbol: String) {
        scope.launch {
            // Latest tick seen per provider for this symbol; recomputed into
            // an average whenever any provider reports a fresh tick.
            val latestByProvider = ConcurrentHashMap<String, Tick>()

            supervisorScope {
                providers.forEach { provider ->
                    launch {
                        // produceIn starts the provider's flow eagerly in its own
                        // child coroutine, decoupled from how fast we consume it.
                        // That lets us race "wait for the next tick" against a
                        // timeout: if the provider stalls (our simulated flaky
                        // tick), we simply skip a beat for THIS provider only --
                        // the channel keeps buffering, and other providers'
                        // coroutines are completely unaffected.
                        val channel = provider.ticks(symbol).produceIn(this)
                        while (true) {
                            val tick = withTimeoutOrNull(perTickTimeoutMillis.milliseconds) {
                                channel.receive()
                            } ?: continue // this provider stalled past the SLA; try again next beat

                            latestByProvider[provider.name] = tick

                            val samples = latestByProvider.values.toList()
                            val avg = samples.map { it.price }.average()
                            val aggregated = AggregatedPrice(
                                symbol = symbol,
                                price = avg,
                                sampleCount = samples.size,
                                providers = samples.map { it.provider },
                                asOfMillis = System.currentTimeMillis(),
                                staleMillis = 0
                            )
                            cache[symbol] = aggregated
                            _updates.emit(aggregated)
                        }
                    }
                }
            }
        }
    }
}