package su.nepom.budget.db.sqlite

import io.github.oshai.kotlinlogging.KotlinLogging
import org.flywaydb.core.Flyway
import org.ktorm.database.Database
import org.ktorm.entity.associate
import org.sqlite.SQLiteDataSource
import su.nepom.budget.db.Db
import su.nepom.budget.db.Session
import su.nepom.budget.db.sqlite.impl.AccountRestCache
import su.nepom.budget.db.sqlite.impl.EventProcessor
import su.nepom.budget.db.sqlite.impl.TableCopy
import su.nepom.budget.db.sqlite.mapping.AccountRestEntity
import su.nepom.budget.db.sqlite.mapping.accounts
import su.nepom.budget.db.sqlite.mapping.currencies
import su.nepom.budget.db.sqlite.utils.DatabaseHolder
import su.nepom.budget.event.AccountContent
import su.nepom.budget.event.CurrencyContent
import su.nepom.budget.model.AccountId
import su.nepom.budget.model.CurrencyId
import su.nepom.budget.model.Uuid
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.absolute

private val logger = KotlinLogging.logger {}

private val activeSqliteDatabases = ConcurrentHashMap<Path, SqliteDatabase>()

internal abstract class FlywayShouldRunFirst(pathToDb: Path) {
    protected val normalizedPathToDb: Path = pathToDb.absolute().normalize()
    protected val datasource = SQLiteDataSource().apply {
        url = "jdbc:sqlite:$normalizedPathToDb"
        setEnforceForeignKeys(true)
    }

    init {
        val previous = activeSqliteDatabases.putIfAbsent(normalizedPathToDb, this as SqliteDatabase)
        if (previous != null) {
            throw IllegalStateException("SqliteDatabase already exists for path $normalizedPathToDb: $previous")
        }

        logger.info { "Connect to main database at $normalizedPathToDb" }

        Flyway.configure().dataSource(datasource).load().migrate()
        logger.info { "Migration complete" }
    }
}

internal class SqliteDatabase(pathToDb: Path): FlywayShouldRunFirst(pathToDb), Db, DatabaseHolder {
    val database = Database.connect(datasource)
    private val blocker = AtomicReference<Session>()
    private val threadWithActiveTransaction = AtomicReference<Thread>()
    private var sessionWithActiveTransaction: Session? = null
    val eventProcessor = EventProcessor(database)
    val currencyCache = TableCopy(CurrencyContent::class.java) {
        currencies.associate { CurrencyId(Uuid(it.uuid)) to it.toCurrencyContent() }
    }
    val accountCache = TableCopy(AccountContent::class.java) {
        accounts.associate { AccountId(Uuid(it.uuid)) to it.toAccountContent() }
    }
    val accountRestCache = AccountRestCache(this)
    val catalogCaches = mapOf(
        CurrencyContent::class to currencyCache,
        AccountContent::class to accountCache,
        AccountRestEntity::class to accountRestCache,
    )

    /**
     * In sqlite only one writing transaction is allowed
     */
    fun checkAndStartTransaction(session: Session) {
        val currentThread = Thread.currentThread()
        val prevThread = threadWithActiveTransaction.compareAndExchange(null, currentThread)
        if (prevThread == null) {
            sessionWithActiveTransaction = session
            catalogCaches.values.forEach { it.onTransactionStart() }
        } else if (prevThread === currentThread) {
            if (sessionWithActiveTransaction !== session) {
                throw IllegalStateException("Another session has active transaction")
            }
        } else {
            throw IllegalStateException("Another thread has active transaction")
        }
    }

    fun isSessionOwnsTransaction(session: Session): Boolean = sessionWithActiveTransaction === session

    fun checkTransactionFinish(session: Session): Boolean {
        if (sessionWithActiveTransaction == null) return false
        else if (sessionWithActiveTransaction !== session) {
            throw IllegalStateException("Wrong session with active transaction")
        } else if (threadWithActiveTransaction.get() !== Thread.currentThread()) {
            throw IllegalStateException("Wrong thread with active transaction")
        }
        return true
    }

    fun finishTransaction(session: Session, commit: Boolean) {
        val currentThread = Thread.currentThread()
        val prevThread = threadWithActiveTransaction.compareAndExchange(currentThread, null)
        if (prevThread !== currentThread) throw IllegalStateException("Wrong thread with active transaction")
        if (sessionWithActiveTransaction != session) {
            throw IllegalStateException("Wrong session with active transaction")
        }
        catalogCaches.values.forEach { if (commit) it.onTransactionCommit() else it.onTransactionRollback() }
        sessionWithActiveTransaction = null
    }

    fun checkBlocker(session: Session) {
        val current = blocker.compareAndExchange(session, session)
        if (current != null && current !== session) {
            throw IllegalStateException("Another session blocked db")
        }
    }

    fun onSessionClosed(session: Session) {
        blocker.compareAndExchange(session, null)
    }

    override fun createSession(): Session = SqliteSession(this, true)

    override fun createSessionInBlockingMode(createEvents: Boolean): Session {
        val session = SqliteSession(this, createEvents)
        if (blocker.compareAndExchange(null, session) != null) {
            session.close()
            throw IllegalStateException("Another session blocked db")
        }
        return session
    }

    override fun getDb() = database

    override fun close() {
        database.transactionManager.currentTransaction?.rollback()
        activeSqliteDatabases.remove(normalizedPathToDb)
        logger.info { "Close database at $  normalizedPathToDb" }
    }
}