package com.tazzledazzle.stockagg;

/** A single tick from one upstream provider. */
public record Tick(String symbol, String provider, double price, long timestampMillis) {
}