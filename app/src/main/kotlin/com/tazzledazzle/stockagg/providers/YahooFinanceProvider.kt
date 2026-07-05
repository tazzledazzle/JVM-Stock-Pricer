package com.tazzledazzle.stockagg.providers

import com.tazzledazzle.stockagg.model.ProviderException
import com.tazzledazzle.stockagg.model.ProviderReading
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import kotlinx.serialization.Serializable

/**
 * Yahoo Finance's public "chart" endpoint. This is unofficial and unrate-limited-by-contract
 * (Yahoo can throttle or change it without notice), which is exactly why it's paired with
 * StooqProvider below rather than trusted alone - this is the same bulkhead argument from
 * the original design doc, just against a real-world failure mode instead of a simulated one.
 *
 * Endpoint: https://query1.finance.yahoo.com/v8/finance/chart/{symbol}
 */
class YahooFinanceProvider(private val client: HttpClient) : PriceProvider {
    override val name = "yahoo-finance"

    override suspend fun fetch(symbol: String): ProviderReading {
        val response: YahooChartResponse = client.get(
            "https://query1.finance.yahoo.com/v8/finance/chart/$symbol"
        ) {
            parameter("interval", "1m")
            parameter("range", "1d")
            // Yahoo's edge will reject requests with no/odd User-Agent.
            header(HttpHeaders.UserAgent, "Mozilla/5.0 (compatible; StockAggregator/1.0)")
        }.body()

        val result = response.chart.result?.firstOrNull()
            ?: throw ProviderException(
                "$name: no result for $symbol (${response.chart.error?.description ?: "unknown error"})"
            )

        val price = result.meta.regularMarketPrice
            ?: throw ProviderException("$name: missing regularMarketPrice for $symbol")

        val timestampMs = (result.meta.regularMarketTime ?: (System.currentTimeMillis() / 1000)) * 1000
        return ProviderReading(source = name, price = price, timestamp = timestampMs)
    }

    @Serializable
    private data class YahooChartResponse(val chart: YahooChart)

    @Serializable
    private data class YahooChart(
        val result: List<YahooResult>? = null,
        val error: YahooError? = null
    )

    @Serializable
    private data class YahooResult(val meta: YahooMeta)

    @Serializable
    private data class YahooMeta(
        val regularMarketPrice: Double? = null,
        val regularMarketTime: Long? = null
    )

    @Serializable
    private data class YahooError(val code: String? = null, val description: String? = null)
}
