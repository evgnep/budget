package su.nepom.budget.currenciesexchangerates

import su.nepom.budget.event.CurrenciesExchangeRatesContent
import java.time.LocalDate

interface CurrenciesExchangeRatesProvider {
  /**
   * @return null if there are no rates for [date] (eg it's in the future)
   * @throws Exception if rates can't be fetched for other reasons (eg network error). Callers are
   * expected to retry.
   */
  suspend fun get(baseCurrencyCode: String, date: LocalDate): CurrenciesExchangeRatesContent?
}