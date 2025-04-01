package su.nepom.budget.db.sqlite

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import su.nepom.budget.Global
import su.nepom.budget.db.Session
import su.nepom.budget.db.dao.PropertyDao
import su.nepom.budget.db.sqlite.impl.setInTransactionUnsafe
import su.nepom.budget.db.sqlite.utils.DatabaseHolder
import su.nepom.budget.event.ActualVersionContent
import su.nepom.budget.event.Event
import su.nepom.budget.event.EventType
import su.nepom.budget.model.no
import su.nepom.budget.utils.SecondsClock
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class SqliteSession(
    val name: String,
    override val db: SqliteDatabase,
    private val createEvents: Boolean,
    private val autoCommit: Boolean,
) : Session, DatabaseHolder {
    private val logger = KotlinLogging.logger(SqliteSession::class.qualifiedName!! + " [$name]")
    private val lock = ReentrantLock()
    private var sessionThread: Thread? = null
    private var closed: Boolean = false
    private val singleThreadDispatcher = lazy {
        Executors.newSingleThreadExecutor { Thread(it).also { t -> t.name = "SqliteDatabase-coro-$name" } }
            .asCoroutineDispatcher()
    }

    private val currencyDaoHolder by lazy { SqliteCurrencyDao(this) }
    private val eventDaoHolder by lazy { SqliteEventDao(this) }
    private val accountDaoHolder by lazy { SqliteAccountDao(this) }
    private val transactionDaoHolder by lazy { SqliteTransactionDao(this) }
    private val propertiesDaoHolder by lazy { SqlitePropertyDao(this) }

    fun <T> doReadOp(block: () -> T): T {
        beforeAnyOperation()
        return block()
    }

    fun <T> doWriteOp(dontCommit: Boolean = false, block: () -> T): T {
        beforeAnyOperation()
        db.checkAndStartTransaction(this)
        if (db.database.transactionManager.currentTransaction == null) {
            db.database.transactionManager.newTransaction()
            logger.info { "Started transaction" }
        }
        val result = block()
        if (autoCommit && !dontCommit) commit()
        return result
    }

    private fun beforeAnyOperation() {
        if (closed) throw IllegalStateException("Session is closed")
        checkThread()
    }

    private fun checkThread() {
        val thread = Thread.currentThread()
        if (sessionThread == null) {
            lock.withLock {
                if (sessionThread == null) {
                    db.registerSessionInThread(this, thread)
                    sessionThread = Thread.currentThread()
                }
            }
        }

        if (sessionThread != thread) {
            throw IllegalStateException(
                "You can use session only in one thread. " +
                        "Current thread: ${thread.name}, session thread: ${sessionThread?.name}"
            )
        }
    }

    override val currencyDao get() = currencyDaoHolder
    override val accountDao get() = accountDaoHolder
    override val transactionDao get() = transactionDaoHolder
    override val eventDao get() = eventDaoHolder
    override val propertyDao: PropertyDao get() = propertiesDaoHolder

    override fun commit() {
        beforeAnyOperation()
        if (!db.isSessionOwnsWriteTransaction(this)) {
            logger.info { "Zero commit - transaction wasn't started" }
            return
        }
        try {
            db.finishTransaction(this, true)
            logger.info { "Transaction commited" }
        } catch (e: Exception) {
            db.finishTransaction(this, false)
            logger.info { "Transaction rollback after commit error" }
            throw e
        }
    }

    override fun rollback() {
        beforeAnyOperation()
        if (!db.isSessionOwnsWriteTransaction(this)) {
            logger.info { "Zero rollback - transaction wasn't started" }
            return
        }
        db.finishTransaction(this, false)
        logger.info { "Transaction rollback" }
    }

    override suspend fun <T> coroDbOp(block: suspend Session.() -> T): T =
        withContext(singleThreadDispatcher.value) { block() }

    override fun close() {
        if (closed) return
        if (sessionThread == null) {
            closed = true
            db.onSessionClosed(this)
            logger.info { "Closed, was inactive" }
        }

        checkThread()
        closed = true
        if (singleThreadDispatcher.isInitialized()) singleThreadDispatcher.value.close()
        if (db.isSessionOwnsWriteTransaction(this)) {
            db.finishTransaction(this, false)
            logger.info { "Transaction rollback on close" }
        }
        db.database.transactionManager.currentTransaction?.run {
            close()
            logger.info { "Transaction closed" }
        }
        db.onSessionClosed(this)
        logger.info { "Closed" }
    }

    fun forceClose() {
        closed = true
        if (singleThreadDispatcher.isInitialized()) singleThreadDispatcher.value.close()
        logger.info { "Force closed" }
    }

    override fun getDb() = db.database

    fun saveEvent(entity: ActualVersionContent, eventType: EventType) {
        beforeAnyOperation()
        db.catalogCaches[entity::class]?.setInTransactionUnsafe(entity)
        if (!createEvents) return
        val basedOn = eventDao.getLastEventCoords()
            .filterNot { it.key == Global.currentPlace }
            .map { it.key no it.value }
        val event = Event(Global.currentPlace no 0, SecondsClock.now(), Global.currentUser, eventType, basedOn, entity)
        eventDao.save(event)
    }

    override fun toString(): String = "Session[$name]"
}