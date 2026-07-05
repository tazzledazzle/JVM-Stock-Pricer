package com.tazzledazzle.stockagg

import com.tazzledazzle.aggregator.CircuitBreaker
import com.tazzledazzle.aggregator.CircuitState
import com.tazzledazzle.aggregator.withRetry
import com.tazzledazzle.stockagg.model.AggregatedPrice
import com.tazzledazzle.stockagg.model.PriceHistory
import com.tazzledazzle.stockagg.model.PricePoint
import com.tazzledazzle.stockagg.model.ProviderReading
import com.tazzledazzle.stockagg.providers.PriceProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

class Aggregator(
    private val providers: List<PriceProvider>,
    private val symbols: List<String>,
    private val pollIntervalMs: Long,
    private val providerTimeoutMs: Long,
    private val maxRetries: Int,
    private val retryBaseDelayMs: Long,
    private val historySize: Int,
    circuitBreakerFailureThreshold: Int,
    circuitBreakerResetMs: Long
) {
    private val cache = ConcurrentHashMap<String, AggregatedPrice>()
    private val history = ConcurrentHashMap<String, ArrayDeque<PricePoint>>()
    private val circuitBreaker = CircuitBreaker(circuitBreakerFailureThreshold, circuitBreakerResetMs)

    private val _updates = MutableSharedFlow<AggregatedPrice>(extraBufferCapacity = 64)
    val updates: SharedFlow<AggregatedPrice> = _updates.asSharedFlow()

    /** Launches one independent poll loop per symbol on [scope]. Failures in one loop
     *  (or one provider within a loop) never cancel the others - supervisorScope below
     *  is the bulkhead boundary. */
    fun start(scope: CoroutineScope) {
        symbols.forEach { symbol ->
            scope.launch { pollLoop(symbol) }
        }
    }

    private suspend fun CoroutineScope.pollLoop(symbol: String) {
        while (isActive) {
            val readings = supervisorScope {
                providers.map { provider ->
                    async { fetchFromProvider(provider, symbol) }
                }.awaitAll().filterNotNull()
            }

            if (readings.isNotEmpty()) {
                val avgPrice = readings.sumOf { it.price } / readings.size
                val latestTs = readings.maxOf { it.timestamp }
                val aggregated = AggregatedPrice(
                    symbol = symbol,
                    price = avgPrice,
                    timestamp = latestTs,
                    sources = readings.map { it.source }
                )
                cache[symbol] = aggregated
                appendHistory(symbol, PricePoint(latestTs, avgPrice))
                _updates.emit(aggregated)
            } else {
                // Every provider failed or was circuit-open this tick - degrade to the
                // last known price with a staleness flag, never a 500 to the client.
                cache[symbol]?.let { last ->
                    val stale = last.copy(staleMillis = System.currentTimeMillis() - last.timestamp)
                    cache[symbol] = stale
                    _updates.emit(stale)
                }
            }

            delay(pollIntervalMs)
        }
    }

    private suspend fun fetchFromProvider(provider: PriceProvider, symbol: String): ProviderReading? {
        val key = "${provider.name}:$symbol"
        if (!circuitBreaker.allowRequest(key)) return null

        return try {
            val reading = withTimeoutOrNull(providerTimeoutMs) {
                withRetry(maxRetries, retryBaseDelayMs) { provider.fetch(symbol) }
            }
            if (reading != null) {
                circuitBreaker.recordSuccess(key)
                reading
            } else {
                circuitBreaker.recordFailure(key)
                null
            }
        } catch (e: Exception) {
            circuitBreaker.recordFailure(key)
            null
        }
    }

    private fun appendHistory(symbol: String, point: PricePoint) {
        val deque = history.computeIfAbsent(symbol) { ArrayDeque() }
        synchronized(deque) {
            deque.addLast(point)
            while (deque.size > historySize) deque.removeFirst()
        }
    }

    fun latest(symbol: String): AggregatedPrice? = cache[symbol]

    fun allLatest(): List<AggregatedPrice> = cache.values.toList()

    fun historyOf(symbol: String): PriceHistory {
        val points = history[symbol]?.let { synchronized(it) { it.toList() } } ?: emptyList()
        return PriceHistory(symbol, points)
    }

    fun circuitStateOf(providerName: String, symbol: String): CircuitState =
        circuitBreaker.stateOf("$providerName:$symbol")
}
