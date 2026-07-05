package com.tazzledazzle.aggregator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Retries [block] up to [maxRetries] additional times with exponential backoff
 * (baseDelayMs, baseDelayMs*2, baseDelayMs*4, ...). Never swallows cancellation -
 * that must propagate immediately for structured concurrency / timeouts to work.
 */
suspend fun <T> withRetry(
    maxRetries: Int,
    baseDelayMs: Long,
    onRetry: (attempt: Int, error: Throwable) -> Unit = { _, _ -> },
    block: suspend () -> T
): T {
    var lastError: Throwable? = null
    repeat(maxRetries + 1) { attempt ->
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            lastError = e
            onRetry(attempt, e)
            if (attempt < maxRetries) {
                delay(baseDelayMs * (1L shl attempt))
            }
        }
    }
    throw lastError ?: IllegalStateException("withRetry exhausted with no captured error")
}
