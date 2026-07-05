package com.tazzledazzle.stockagg

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

