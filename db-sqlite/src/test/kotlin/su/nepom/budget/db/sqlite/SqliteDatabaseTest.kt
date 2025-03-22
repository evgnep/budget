package su.nepom.budget.db.sqlite

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.ktorm.entity.count
import su.nepom.budget.db.sqlite.mapping.currencies
import su.nepom.budget.db.sqlite.mapping.events
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

internal class SqliteDatabaseTest: AbstractDbTest() {
    private val dao by lazy { session.currencyDao }
    private val currency = createCurrency("rub", "rubles")

    @Test
    fun rollback() {
        dao.save(currency)
        session.rollback()
        // then
        assertThat(dao.getAll()).isEmpty()
        assertThat(db.events.count()).isZero()
    }

    @Test
    fun dontCreateEvents() {
        db.createSessionInBlockingMode("blocking", false).use { session ->
            session.currencyDao.save(currency)
            session.commit()
        }
        // then
        assertThat(db.currencies.count()).isEqualTo(1)
        assertThat(db.events.count()).isZero()
    }

    @Test
    fun canCreateSessionInOneThreadAndUseInAnother() {
        val session = db.createSession("x")
        thread {
            session.currencyDao.save(currency)
            session.commit()
            assertThat(session.currencyDao.count()).isEqualTo(1)
            session.close()
            assertThatThrownBy { session.currencyDao.count() }.hasMessageContaining("Session is closed")
        }.join()
    }

    @Test
    fun cantUseSessionInDifferentThreads() {
        val session = db.createSession("x")
        val latch1 = CountDownLatch(1)
        val latch2 = CountDownLatch(1)
        val t1 = thread {
            session.currencyDao.save(currency)
            latch1.countDown()
            latch2.await()
            session.commit()
            session.close()
        }

        val t2 = thread {
            latch1.await()
            assertThatThrownBy { session.currencyDao.save(currency) }
                .hasMessageContaining("You can use session only in one thread")
            latch2.countDown()
        }

        t1.join()
        t2.join()
    }

    @Test
    fun blockingMode() {
        var ok = false
        db.createSessionInBlockingMode("1", false).use { session ->
            session.currencyDao.save(currency)
            session.commit()

            assertThatThrownBy { db.createSessionInBlockingMode("2",false) }
                .hasMessageContaining("Session[1] in blocking mode already exists")

            thread {
                db.createSession("3").use { readingSession ->
                    assertThat(readingSession.currencyDao.getAll()).hasSize(1)

                    assertThatThrownBy { readingSession.currencyDao.save(currency) }
                        .hasMessageContaining("Session[1] is in blocking mode")
                }
                ok = true
            }.join()
        }
        assertThat(ok).isTrue()

        db.createSessionInBlockingMode("5", true).close()

        db.createSession("6").use { session ->
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
            val session = db.createSession("success")
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
            val session = db.createSession("fail")
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

        val session = db.createSession("autoclose")
        session.save(createCurrency("kzt", "3-1"))
        session.commit()
    }

    @Test
    fun coroutine() {
        runBlocking {
            db.createSession("coro").coroUse {
                launch {
                    coroDbOp {
                        currencyDao.save(currency)
                    }
                    coroDbOp {
                        commit()
                    }
                    coroDbOp {
                        assertThat(currencyDao.getAll()).contains(currency)
                    }
                }
                launch {
                    coroDbOp {
                        currencyDao.save(createCurrency("kzt", "4"))
                        commit()
                    }
                }.join()
                assertThatThrownBy { currencyDao.count() }.hasMessageContaining("You can use session only in one thread")
            }
        }
    }
}