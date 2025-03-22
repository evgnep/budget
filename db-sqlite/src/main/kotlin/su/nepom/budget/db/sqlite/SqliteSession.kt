package su.nepom.budget.db.sqlite

import io.github.oshai.kotlinlogging.KotlinLogging
import su.nepom.budget.Global
import su.nepom.budget.db.Session
import su.nepom.budget.db.sqlite.impl.setInTransactionUnsafe
import su.nepom.budget.db.sqlite.utils.DatabaseHolder
import su.nepom.budget.event.ActualVersionContent
import su.nepom.budget.event.Event
import su.nepom.budget.event.EventType
import su.nepom.budget.model.no
import su.nepom.budget.utils.SecondsClock

private val logger = KotlinLogging.logger { }

internal class SqliteSession(
    val db: SqliteDatabase,
    val createEvents: Boolean,
) : Session, DatabaseHolder {
    private var closed: Boolean = false
    private val currencyDaoHolder by lazy { SqliteCurrencyDao(this) }
    private val eventDaoHolder by lazy { SqliteEventDao(this) }
    private val accountDaoHolder by lazy { SqliteAccountDao(this) }
    private val transactionDaoHolder by lazy { SqliteTransactionDao(this) }

    fun raiseIfClosed() {
        if (closed) throw IllegalStateException("Session is closed")
    }

    fun startTransactionIfNotYet() {
        raiseIfClosed()
        db.checkBlocker(this)
        db.checkAndStartTransaction(this)

        if (db.database.transactionManager.currentTransaction == null) {
            db.database.transactionManager.newTransaction()
            logger.info { "Started transaction" }
        }
    }

    override val currencyDao get() = currencyDaoHolder
    override val accountDao get() = accountDaoHolder
    override val transactionDao get() = transactionDaoHolder
    override val eventDao get() = eventDaoHolder

    override fun commit() {
        raiseIfClosed()
        if (!db.checkTransactionFinish(this)) {
            logger.info { "Transaction wasn't started" }
            return
        }
        try {
            db.database.transactionManager.currentTransaction?.commit()
            db.finishTransaction(this, true)
            logger.info { "Transaction commited" }
        } catch (e: Exception) {
            db.database.transactionManager.currentTransaction?.rollback()
            db.finishTransaction(this, false)
            logger.info { "Transaction rollback after commit error" }
            throw e
        }
        db.eventProcessor.onTransactionFinished()
    }

    override fun rollback() {
        raiseIfClosed()
        if (!db.checkTransactionFinish(this)) {
            logger.info { "Transaction wasn't started" }
            return
        }
        db.database.transactionManager.currentTransaction?.rollback()
        db.finishTransaction(this, false)
        logger.info { "Transaction rollback" }
    }

    override fun close() {
        closed = true
        if (db.isSessionOwnsTransaction(this)) {
            if (db.checkTransactionFinish(this)) {
                db.database.transactionManager.currentTransaction?.rollback()
                db.finishTransaction(this, false)
                logger.info { "Transaction rollback on close" }
            }
        }
        db.database.transactionManager.currentTransaction?.let {
            it.rollback()
            logger.info { "Other transaction rollback on close" }
        }
        db.onSessionClosed(this)
        logger.info { "Close" }
    }

    override fun getDb() = db.database

    fun saveEvent(entity: ActualVersionContent, eventType: EventType) {
        db.catalogCaches[entity::class]?.setInTransactionUnsafe(entity)
        if (!createEvents) return
        val basedOn = eventDao.getLastEventCoords()
            .filterNot { it.key == Global.currentPlace }
            .map { it.key no it.value }
        val event = Event(Global.currentPlace no 0, SecondsClock.now(), Global.currentUser, eventType, basedOn, entity)
        eventDao.save(event)
    }
}