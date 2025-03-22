package su.nepom.budget.db.sqlite

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.data.Index
import org.junit.jupiter.api.Test
import org.ktorm.entity.count
import su.nepom.budget.Global
import su.nepom.budget.db.sqlite.mapping.currencies
import su.nepom.budget.db.sqlite.mapping.events
import su.nepom.budget.event.EventType
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.no
import java.util.concurrent.CountDownLatch
import java.util.function.Consumer
import kotlin.concurrent.thread

internal class SqliteCurrencyDaoTest : AbstractDbTest() {
    private val dao by lazy { session.currencyDao }

    @Test
    fun createNew() {
        val currency = createCurrency("rub", "rubles")
        dao.save(currency)
        session.commit()
        // then
        assertThat(dao.getAll()).containsExactlyInAnyOrder(currency)
        assertThat(dao.count()).isEqualTo(1)
        assertThat(eventDao.getLastEventCoords()).containsExactlyInAnyOrderEntriesOf(mapOf(Global.currentPlace to 1))
        assertThat(eventDao.getEventsForSourceFrom(Global.currentPlace, 1))
            .singleElement()
            .satisfies(Consumer {
                assertThat(it.coords).isEqualTo(Global.currentPlace no 1)
                assertThat(it.creator).isEqualTo(Global.currentUser)
                assertThat(it.type).isEqualTo(EventType.NEW)
                assertThat(it.basedOn).isEmpty()
                assertThat(it.content).isEqualTo(currency)
            })
        assertThat(eventDao.getLastEventForObject(currency.uuid, ObjectKind.CURRENCY))
            .satisfies(Consumer {
                requireNotNull(it)
                assertThat(it.coords).isEqualTo(Global.currentPlace no 1)
            })
    }

    @Test
    fun update() {
        val currency1 = createCurrency("rub", "rubles")
        val currency2 = currency1.copy(name = "other")
        // when
        dao.save(currency1)
        session.commit()
        //--
        dao.save(currency2)
        assertThat(eventDao.getLastEventCoords()).containsExactlyInAnyOrderEntriesOf(mapOf(Global.currentPlace to 1))
        session.commit()
        // then
        assertThat(dao.getAll()).containsExactlyInAnyOrder(currency2)
        assertThat(eventDao.getLastEventCoords()).containsExactlyInAnyOrderEntriesOf(mapOf(Global.currentPlace to 2))
        assertThat(eventDao.getEventsForSourceFrom(Global.currentPlace, 1))
            .satisfies({
                assertThat(it.coords).isEqualTo(Global.currentPlace no 1)
                assertThat(it.creator).isEqualTo(Global.currentUser)
                assertThat(it.type).isEqualTo(EventType.NEW)
                assertThat(it.basedOn).isEmpty()
                assertThat(it.content).isEqualTo(currency1)
            }, Index.atIndex(0))
            .satisfies({
                assertThat(it.coords).isEqualTo(Global.currentPlace no 2)
                assertThat(it.creator).isEqualTo(Global.currentUser)
                assertThat(it.type).isEqualTo(EventType.UPDATE)
                assertThat(it.basedOn).isEmpty()
                assertThat(it.content).isEqualTo(currency2)
            }, Index.atIndex(1))
        assertThat(eventDao.getLastEventForObject(currency1.uuid, ObjectKind.CURRENCY))
            .satisfies(Consumer {
                requireNotNull(it)
                assertThat(it.coords).isEqualTo(Global.currentPlace no 2)
            })
    }

    @Test
    fun rollback() {
        val currency = createCurrency("rub", "rubles")
        dao.save(currency)
        session.rollback()
        // then
        assertThat(dao.getAll()).isEmpty()
        assertThat(db.events.count()).isZero()
    }

    @Test
    fun dontCreateEvents() {
        db.createSessionInBlockingMode(false).use { session ->
            val currency = createCurrency("rub", "rubles")
            session.currencyDao.save(currency)
            session.commit()
        }
        // then
        assertThat(db.currencies.count()).isEqualTo(1)
        assertThat(db.events.count()).isZero()
    }

    @Test
    fun blockingMode() {
        val currency = createCurrency("rub", "rubles")
        db.createSessionInBlockingMode(false).use { session ->
            session.currencyDao.save(currency)
            session.commit()

            assertThatThrownBy { db.createSessionInBlockingMode(false) }
                .hasMessageContaining("Another session blocked db")

            db.createSession().use { readingSession ->
                assertThat(readingSession.currencyDao.getAll()).hasSize(1)

                assertThatThrownBy { readingSession.currencyDao.save(currency) }
                    .hasMessageContaining("Another session blocked db")
            }
        }

        db.createSessionInBlockingMode(true).close()

        db.createSession().use { session ->
            session.currencyDao.save(currency)
        }
    }

    @Test
    fun `twoThreads - first successfully starts and commit transaction, other fails on first write operation`() {
        val latch1 = CountDownLatch(1)
        val latch2 = CountDownLatch(1)
        var successOk = false
        var failOk = false

        val success = thread(name="success") {
            val session = db.createSession()
            session.currencyDao.save(createCurrency("rub", "1-1"))
            latch1.countDown()
            latch2.await()
            session.currencyDao.save(createCurrency("rub", "1-1"))
            Thread.sleep(100)
            session.commit()
            session.close()
            successOk = true
        }

        val fail = thread(name="fail") {
            latch1.await()
            val session = db.createSession()
            try {
                session.currencyDao.save(createCurrency("rub", "2-1"))
                session.commit()
            } catch (e: Exception) {
                println(e.toString())
                failOk = true
            }
            session.close()
            latch2.countDown()
        }

        success.join()
        fail.join()
        assertThat(successOk).isTrue
        assertThat(failOk).isTrue

        val session = db.createSession()
        session.save(createCurrency("kzt", "3-1"))
        session.commit()
    }
}