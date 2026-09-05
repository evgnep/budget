package su.nepom.budget.desktop.service

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Inject
import jakarta.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import su.nepom.budget.currenciesexchangerates.CurrenciesExchangeRatesService
import su.nepom.budget.currenciesexchangerates.Fawazahmed0CurrenciesExchangeRatesProvider

private val logger = KotlinLogging.logger { }

/**
 * Starts [CurrenciesExchangeRatesService] as soon as a database is available and keeps it running
 * for the lifetime of the app.
 */
@Singleton
class CurrenciesExchangeRatesAppService @Inject constructor(
  private val dbService: DbService,
) {
  private val scope = CoroutineScope(Dispatchers.Default)

  private var service: CurrenciesExchangeRatesService? = null

  init {
    dbService.sessionProperty.addListener { _, _, _ -> onDbChanged() }
    onDbChanged()
  }

  private fun onDbChanged() {
    if (service != null) return
    val db = dbService.db ?: return
    logger.info { "Starting currencies exchange rates service" }
    service?.let { runBlocking { it.stop() } }
    service = CurrenciesExchangeRatesService(db, Fawazahmed0CurrenciesExchangeRatesProvider()).also { it.start(scope) }
  }

  fun onStop() {
    runBlocking { service?.stop() }
    scope.cancel()
  }
}
