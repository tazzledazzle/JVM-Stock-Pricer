package com.tazzledazzle.aggregator

/**
 * All tunables come from environment variables so this can be reconfigured per-environment
 * (dev/staging/prod) without a rebuild. Every value has a safe default for local runs.
 */
data class AppConfig(
    val symbols: List<String>,
    val pollIntervalMs: Long,
    val providerTimeoutMs: Long,
    val maxRetries: Int,
    val retryBaseDelayMs: Long,
    val historySize: Int,
    val circuitBreakerFailureThreshold: Int,
    val circuitBreakerResetMs: Long,
    val port: Int
) {
    companion object {
        private fun env(name: String, default: String): String =
            System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

        fun fromEnv(): AppConfig = AppConfig(
            symbols = env("SYMBOLS", "AAPL,MSFT,GOOG")
                .split(",")
                .map { it.trim().uppercase() }
                .filter { it.isNotEmpty() },
            // Yahoo/Stooq are unofficial free endpoints - poll politely, not per-second.
            pollIntervalMs = env("POLL_INTERVAL_MS", "5000").toLong(),
            providerTimeoutMs = env("PROVIDER_TIMEOUT_MS", "3000").toLong(),
            maxRetries = env("MAX_RETRIES", "2").toInt(),
            retryBaseDelayMs = env("RETRY_BASE_DELAY_MS", "200").toLong(),
            historySize = env("HISTORY_SIZE", "200").toInt(),
            circuitBreakerFailureThreshold = env("CB_FAILURE_THRESHOLD", "3").toInt(),
            circuitBreakerResetMs = env("CB_RESET_TIMEOUT_MS", "30000").toLong(),
            port = env("PORT", "8080").toInt()
        )
    }
}
