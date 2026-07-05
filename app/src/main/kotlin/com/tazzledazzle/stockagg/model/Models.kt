package com.tazzledazzle.stockagg.model

import kotlinx.serialization.Serializable

/**
 * A single tick from one upstream provider.
 */

@Serializable
data class Tick(
    val symbol: String,
    val provider: String,
    val price: Double,
    val timestampMillis: Long
)

/**
 * The aggregated, client-facing view of a symbol's price.
 * Nullable-by-construction is avoided here on purpose: if we don't have
 * enough data yet, we simply don't emit a Price for that symbol. Callers
 * asking for a symbol that has no Price yet get a 404, not a null price
 * silently serialized as `0.0` or similar.
 */
/** What we serve on REST/WebSocket - the aggregated view across all live providers. */
@Serializable
data class AggregatedPrice(
    val symbol: String,
    val price: Double,
    val timestamp: Long,
    val sources: List<String>,
    // Non-null means every provider failed this tick and we're serving the last good price.
    val staleMillis: Long? = null
)

@Serializable
data class PricePoint(val timestamp: Long, val price: Double)

@Serializable
data class PriceHistory(val symbol: String, val points: List<PricePoint>)

/** Internal - a single successful read from one vendor, before aggregation. */
data class ProviderReading(val source: String, val price: Double, val timestamp: Long)

class ProviderException(message: String) : Exception(message)
