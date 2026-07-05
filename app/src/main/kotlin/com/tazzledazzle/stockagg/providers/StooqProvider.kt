package com.tazzledazzle.stockagg.providers


import com.tazzledazzle.stockagg.model.ProviderException
import com.tazzledazzle.stockagg.model.ProviderReading
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Stooq's free quote-lookup CSV endpoint. Independent infrastructure from Yahoo Finance,
 * so a Yahoo outage/rate-limit doesn't take the whole symbol down - the Aggregator will
 * just average whichever vendor(s) responded, or serve the last-known price with a
 * staleness flag if both are down.
 *
 * Endpoint: https://stooq.com/q/l/?s={ticker}&f=sd2t2ohlcv&h&e=csv
 * US tickers need a ".us" suffix (aapl.us, msft.us, ...).
 */
class StooqProvider(private val client: HttpClient) : PriceProvider {
    override val name = "stooq"

    // Stooq times are quoted in US Eastern for US-listed tickers; approximate but
    // fine for a live dashboard where we mainly care about "how stale is this."
    private val usEastern = ZoneId.of("America/New_York")
    private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    override suspend fun fetch(symbol: String): ProviderReading {
        val ticker = "${symbol.lowercase()}.us"
        val csv: String = client.get("https://stooq.com/q/l/") {
            parameter("s", ticker)
            parameter("f", "sd2t2ohlcv")
            parameter("h", "")
            parameter("e", "csv")
        }.body()

        val lines = csv.trim().lines()
        if (lines.size < 2) {
            throw ProviderException("$name: empty response for $symbol")
        }

        // Header: Symbol,Date,Time,Open,High,Low,Close,Volume
        val fields = lines[1].split(",")
        if (fields.size < 8) {
            throw ProviderException("$name: unexpected CSV shape for $symbol: ${lines[1]}")
        }

        val close = fields[6].toDoubleOrNull()
        if (close == null || close <= 0.0) {
            // Stooq returns "N/D" fields and a negative/zero close when the market's
            // closed or the ticker is unknown - treat both as a hard failure.
            throw ProviderException("$name: no valid quote for $symbol (raw: ${lines[1]})")
        }

        val timestamp = try {
            LocalDateTime.parse("${fields[1]} ${fields[2]}", timestampFormat)
                .atZone(usEastern)
                .toInstant()
                .toEpochMilli()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }

        return ProviderReading(source = name, price = close, timestamp = timestamp)
    }
}
