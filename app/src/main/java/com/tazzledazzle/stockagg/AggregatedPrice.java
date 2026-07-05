package com.tazzledazzle.stockagg;

import java.util.List;

/**
 * The aggregated, client-facing view of a symbol's price.
 *
 * There's no null-safety at the type level here the way there is with
 * Kotlin's `Price?` -- a caller must know, by convention, that
 * {@link Aggregator#latest(String)} can return {@code null} for a symbol
 * with no data yet. Records don't change that; it's still a
 * javadoc-and-discipline contract rather than a compiler-enforced one.
 */
public record AggregatedPrice(
        String symbol,
        double price,
        int sampleCount,
        List<String> providers,
        long asOfMillis,
        long staleMillis
) {
}