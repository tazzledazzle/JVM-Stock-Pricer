package com.tazzledazzle.stockagg.providers

import com.tazzledazzle.stockagg.model.ProviderReading

/**
 * A single upstream vendor. Implementations should throw on any failure
 * (network error, malformed response, market-closed placeholder data) -
 * the Aggregator's retry/circuit-breaker/bulkhead logic is centralized
 * and doesn't want each provider hand-rolling its own error swallowing.
 */
interface PriceProvider {
    val name: String
    suspend fun fetch(symbol: String): ProviderReading
}
