package su.nepom.budget.currenciesexchangerates

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
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
 * Uses https://github.com/fawazahmed0/exchange-api - a free, no-key-needed exchange rates API.
 */
class Fawazahmed0CurrenciesExchangeRatesProvider(
  private val httpClient: HttpClient = HttpClient(OkHttp),
) : CurrenciesExchangeRatesProvider {

  override suspend fun get(baseCurrencyCode: String, date: LocalDate): CurrenciesExchangeRatesContent? {
    // "latest" is used for today because a dated request may 404 before the source publishes that day's file
    val dateSegment = if (date == LocalDate.now()) "latest" else DateTimeFormatter.ISO_LOCAL_DATE.format(date)
    val url =
      "https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@$dateSegment/v1/currencies/$baseCurrencyCode.json"
    val response = httpClient.get(url)
    if (response.status == HttpStatusCode.NotFound) return null
    if (!response.status.isSuccess()) throw IllegalStateException("Unexpected status ${response.status} from $url")
    val body = response.bodyAsText()
    val ratesJson = Json.parseToJsonElement(body).jsonObject[baseCurrencyCode]?.jsonObject
      ?: throw IllegalStateException("No rates for $baseCurrencyCode in response from $url")
    return CurrenciesExchangeRatesContent(
      id = Uuid(DateTimeFormatter.ISO_LOCAL_DATE.format(date)),
      rates = ratesJson.mapValues { it.value.jsonPrimitive.double },
    )
  }
}
