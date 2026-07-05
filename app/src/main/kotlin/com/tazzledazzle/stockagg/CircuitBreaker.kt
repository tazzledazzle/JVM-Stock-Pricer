package com.tazzledazzle.aggregator

import java.util.concurrent.ConcurrentHashMap

enum class CircuitState { CLOSED, OPEN, HALF_OPEN }

/**
 * A small, dependency-free circuit breaker keyed by an arbitrary string
 * (we key it "$providerName:$symbol" so one vendor's outage on one symbol
 * doesn't need to affect its other symbols, and never affects other vendors -
 * this is the bulkhead property from the original design doc, now backed by
 * real failure modes instead of a simulated flaky provider).
 *
 * CLOSED       - requests flow normally.
 * OPEN         - short-circuits immediately (no network call) until resetTimeoutMs elapses.
 * HALF_OPEN    - allows exactly one probe request through; success closes the circuit,
 *                failure re-opens it and restarts the timer.
 */
class CircuitBreaker(
    private val failureThreshold: Int,
    private val resetTimeoutMs: Long
) {
    private data class State(
        var circuitState: CircuitState = CircuitState.CLOSED,
        var consecutiveFailures: Int = 0,
        var openedAt: Long = 0
    )

    private val states = ConcurrentHashMap<String, State>()

    fun allowRequest(key: String): Boolean {
        val state = states.computeIfAbsent(key) { State() }
        synchronized(state) {
            return when (state.circuitState) {
                CircuitState.CLOSED -> true
                CircuitState.HALF_OPEN -> true
                CircuitState.OPEN -> {
                    if (System.currentTimeMillis() - state.openedAt >= resetTimeoutMs) {
                        state.circuitState = CircuitState.HALF_OPEN
                        true
                    } else {
                        false
                    }
                }
            }
        }
    }

    fun recordSuccess(key: String) {
        val state = states.computeIfAbsent(key) { State() }
        synchronized(state) {
            state.consecutiveFailures = 0
            state.circuitState = CircuitState.CLOSED
        }
    }

    fun recordFailure(key: String) {
        val state = states.computeIfAbsent(key) { State() }
        synchronized(state) {
            state.consecutiveFailures++
            if (state.circuitState == CircuitState.HALF_OPEN || state.consecutiveFailures >= failureThreshold) {
                state.circuitState = CircuitState.OPEN
                state.openedAt = System.currentTimeMillis()
            }
        }
    }

    fun stateOf(key: String): CircuitState = states[key]?.circuitState ?: CircuitState.CLOSED
}
