package su.nepom.budget.currenciesexchangerates

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import su.nepom.budget.db.Db
import su.nepom.budget.db.dao.SimpleObjectDao
import su.nepom.budget.event.BASE_CURRENCY_CODE
import su.nepom.budget.event.CurrenciesExchangeRatesContent
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.Uuid
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger { }

/**
 * Keeps daily currency exchange rates up to date. On start (and once a day after that, while the
 * app keeps running) checks whether today's rates are already saved, and if not, fetches and saves
 * them via [provider], retrying every [retryInterval] on failure.
 *
 * Only the freshest rates are kept visible (`isHidden = false`) - older ones are marked hidden
 * instead of removed, so [ratesFor] can still look them up.
 */
class CurrenciesExchangeRatesService(
  private val db: Db,
  private val provider: CurrenciesExchangeRatesProvider,
  private val baseCurrencyCode: String = BASE_CURRENCY_CODE,
  private val retryInterval: Duration = 5.minutes,
) {
  private var dao: SimpleObjectDao<CurrenciesExchangeRatesContent>? = null
  private var job: Job? = null

  fun start(scope: CoroutineScope) {
    check(job == null) { "Already started" }
    val session = db.createSession("currenciesExchangeRates", autoCommit = true)
    this.dao = session.simpleObjectDao(ObjectKind.CURRENCY_EXCHANGE_RATE)
    job = scope.launch {
      while (true) {
        ensureTodayRatesSaved()
        delay(1.days)
      }
    }
  }

  suspend fun stop() {
    job?.cancel()
    job = null
    dao?.session?.coroDbOp { close() }
    dao = null
  }

  suspend fun ratesFor(date: LocalDate): CurrenciesExchangeRatesContent? {
    val dao = dao ?: return null
    return dao.session.coroDbOp {
      dao.getAll(withHidden = true)
        .filter { !it.date.isAfter(date) }
        .maxByOrNull { it.date }
    }
  }

  private suspend fun ensureTodayRatesSaved() {
    val dao = dao ?: return
    while (true) {
      val today = LocalDate.now()
      val todayId = Uuid(DateTimeFormatter.ISO_LOCAL_DATE.format(today))
      val alreadySaved = dao.session.coroDbOp { dao.getById(todayId) } != null
      if (alreadySaved) return
      try {
        val rates = provider.get(baseCurrencyCode, today)
        if (rates == null) {
          logger.warn { "No exchange rates for $today yet, retrying in $retryInterval" }
          delay(retryInterval)
          continue
        }
        dao.session.coroDbOp {
          dao.getAll(withHidden = false).forEach { dao.save(it.copy(isHidden = true)) }
          dao.save(rates)
        }
        logger.info { "Saved exchange rates for $today" }
        return
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        logger.warn(e) { "Failed to get exchange rates for $today, retrying in $retryInterval" }
        delay(retryInterval)
      }
    }
  }
}
