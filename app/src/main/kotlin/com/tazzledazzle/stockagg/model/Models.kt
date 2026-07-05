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
@Serializable
data class AggregatedPrice(
    val symbol: String,
    val price: Double,
    val sampleCount: Int,
    val providers: List<String>,
    val asOfMillis: Long,
    val staleMillis: Long
)