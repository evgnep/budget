package su.nepom.budget.currenciesexchangerates

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import su.nepom.budget.db.Db
import su.nepom.budget.db.Session
import su.nepom.budget.db.dao.SimpleObjectDao
import su.nepom.budget.event.CurrenciesExchangeRatesContent
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.Uuid
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class CurrenciesExchangeRatesServiceTest {

    private val today: LocalDate = LocalDate.now()
    private val yesterday: LocalDate = today.minusDays(1)

    private val saved = mutableMapOf<Uuid, CurrenciesExchangeRatesContent>()

    private val session = mockk<Session>(relaxed = true) {
        coEvery { coroDbOp<Any?>(any()) } coAnswers {
            arg<suspend Session.() -> Any?>(0).invoke(this@mockk)
        }
    }

    private val dao = mockk<SimpleObjectDao<CurrenciesExchangeRatesContent>> {
        every { session } returns this@CurrenciesExchangeRatesServiceTest.session
        every {
            hint(CurrenciesExchangeRatesContent::class)
            getById(any())
        } answers {
            val raw = firstArg<Any?>()
            val id = raw as? Uuid ?: Uuid(raw as String)
            saved[id]
        }
        every { getAll(false) } answers { saved.values.filter { !it.isHidden } }
        every { getAll(true) } answers { saved.values.toList() }
        every {
            hint(CurrenciesExchangeRatesContent::class)
            save(any())
        } answers {
            val content = firstArg<CurrenciesExchangeRatesContent>()
            saved[content.id] = content
            content
        }
    }

    private val db = mockk<Db> {
        every { createSession(any(), any(), any(), any()) } returns session
    }

    private val provider = mockk<CurrenciesExchangeRatesProvider>()

    private fun idFor(date: LocalDate) = Uuid(DateTimeFormatter.ISO_LOCAL_DATE.format(date))

    private fun content(date: LocalDate, isHidden: Boolean = false) = CurrenciesExchangeRatesContent(
        id = idFor(date),
        rates = mapOf("eur" to 0.9),
        isHidden = isHidden,
    )

    private fun newService() = CurrenciesExchangeRatesService(db, provider, retryInterval = 5.minutes)

    init {
        every {
            hint(SimpleObjectDao::class)
            session.simpleObjectDao<CurrenciesExchangeRatesContent>(ObjectKind.CURRENCY_EXCHANGE_RATE)
        } returns dao
    }

    @Test
    fun `fetches and saves today's rates on start`() = runTest {
        coEvery { provider.get(any(), today) } returns content(today)
        val service = newService()

        service.start(this)
        runCurrent()

        assertThat(saved[idFor(today)]).isNotNull()
        service.stop()
    }

    @Test
    fun `does nothing if today's rates are already saved`() = runTest {
        saved[idFor(today)] = content(today)
        val service = newService()

        service.start(this)
        runCurrent()

        coVerify(exactly = 0) { provider.get(any(), any()) }
        service.stop()
    }

    @Test
    fun `retries after an exception from the provider`() = runTest {
        var attempt = 0
        coEvery { provider.get(any(), any()) } answers {
            attempt++
            if (attempt == 1) throw RuntimeException("boom") else content(today)
        }
        val service = newService()

        service.start(this)
        runCurrent()
        assertThat(attempt).isEqualTo(1)
        assertThat(saved).isEmpty()

        advanceTimeBy(5.minutes)
        runCurrent()

        assertThat(attempt).isEqualTo(2)
        assertThat(saved[idFor(today)]).isNotNull()
        service.stop()
    }

    @Test
    fun `retries when the provider returns null`() = runTest {
        var attempt = 0
        coEvery { provider.get(any(), any()) } answers {
            attempt++
            if (attempt == 1) null else content(today)
        }
        val service = newService()

        service.start(this)
        runCurrent()
        assertThat(attempt).isEqualTo(1)
        assertThat(saved).isEmpty()

        advanceTimeBy(5.minutes)
        runCurrent()

        assertThat(attempt).isEqualTo(2)
        assertThat(saved[idFor(today)]).isNotNull()
        service.stop()
    }

    @Test
    fun `hides previously visible rates when saving new ones`() = runTest {
        saved[idFor(yesterday)] = content(yesterday, isHidden = false)
        coEvery { provider.get(any(), today) } returns content(today)
        val service = newService()

        service.start(this)
        runCurrent()

        assertThat(saved[idFor(yesterday)]!!.isHidden).isTrue()
        assertThat(saved[idFor(today)]!!.isHidden).isFalse()
        service.stop()
    }

    @Test
    fun `ratesFor returns the most recent rates not after the given date`() = runTest {
        val twoDaysAgo = today.minusDays(2)
        saved[idFor(twoDaysAgo)] = content(twoDaysAgo, isHidden = true)
        saved[idFor(yesterday)] = content(yesterday, isHidden = true)
        saved[idFor(today)] = content(today, isHidden = false)
        val service = newService()

        service.start(this)
        runCurrent()

        assertThat(service.ratesFor(today)?.date).isEqualTo(today)
        assertThat(service.ratesFor(yesterday)?.date).isEqualTo(yesterday)
        assertThat(service.ratesFor(twoDaysAgo.minusDays(10))).isNull()
        service.stop()
    }

    @Test
    fun `stop cancels the loop and closes the session`() = runTest {
        saved[idFor(today)] = content(today)
        val service = newService()

        service.start(this)
        runCurrent()
        service.stop()

        verify { session.close() }
        advanceTimeBy(1.days)
        runCurrent()
        coVerify(exactly = 0) { provider.get(any(), any()) }
    }
}
