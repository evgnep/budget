package su.nepom.budget.currenciesexchangerates

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import su.nepom.budget.event.CurrenciesExchangeRatesContent
import su.nepom.budget.model.Uuid
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Uses https://freecurrencyapi.com - needs a free API key (sign up on the site, 1000 requests/month
 * on the free plan). Historical rates are available from 1999-01-01 up to yesterday.
 */
class FreeCurrencyApiProvider(
  private val apiKey: String,
  private val httpClient: HttpClient = HttpClient(OkHttp),
) : CurrenciesExchangeRatesProvider {

  override suspend fun get(baseCurrencyCode: String, date: LocalDate): CurrenciesExchangeRatesContent? {
    val dateStr = DateTimeFormatter.ISO_LOCAL_DATE.format(date)
    // the "latest" endpoint has no date param and gives end-of-day data for the most recent day
    val isLatest = date == LocalDate.now()
    val url = if (isLatest) LATEST_URL else HISTORICAL_URL
    val response = httpClient.get(url) {
      header("apikey", apiKey)
      parameter("base_currency", baseCurrencyCode.uppercase())
      if (!isLatest) parameter("date", dateStr)
    }
    if (response.status == HttpStatusCode.NotFound) return null
    if (!response.status.isSuccess()) throw IllegalStateException("Unexpected status ${response.status} from $url")
    val body = response.bodyAsText()
    val data = Json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
      ?: throw IllegalStateException("No data in response from $url")
    val ratesJson = if (isLatest) data else data[dateStr]?.jsonObject
      ?: throw IllegalStateException("No rates for $dateStr in response from $url")
    return CurrenciesExchangeRatesContent(
      id = Uuid(dateStr),
      rates = ratesJson.mapValues { it.value.jsonPrimitive.double }.mapKeys { it.key.lowercase() },
    )
  }

  private companion object {
    const val LATEST_URL = "https://api.freecurrencyapi.com/v1/latest"
    const val HISTORICAL_URL = "https://api.freecurrencyapi.com/v1/historical"
  }
}
