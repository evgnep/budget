package su.nepom.budget.db.sqlite

import io.github.oshai.kotlinlogging.KotlinLogging
import org.flywaydb.core.Flyway
import org.ktorm.database.Database
import org.ktorm.entity.associate
import org.sqlite.SQLiteDataSource
import su.nepom.budget.db.Db
import su.nepom.budget.db.DbListener
import su.nepom.budget.db.Session
import su.nepom.budget.db.sqlite.impl.AccountRestCache
import su.nepom.budget.db.sqlite.impl.EventProcessor
import su.nepom.budget.db.sqlite.impl.EventsNotifier
import su.nepom.budget.db.sqlite.impl.ListenersStorage
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
import java.sql.Connection
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
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

        logger.info { "Connecting to main database at $normalizedPathToDb" }

        Flyway.configure().dataSource(datasource).load().migrate()
        logger.info { "Migration complete" }
    }
}

internal class SqliteDatabase(pathToDb: Path): FlywayShouldRunFirst(pathToDb), Db, DatabaseHolder {
    private val connections = Collections.newSetFromMap(IdentityHashMap<Connection, Boolean>())
    private var closed = false
    private val lock = ReentrantLock()
    private val sessionByThread = HashMap<Thread, SqliteSession>()
    private var sessionInBlockingMode: SqliteSession? = null
    /**
     * In sqlite only one writing transaction is allowed
     */
    private var sessionWithWriteTransaction: SqliteSession? = null
    val database = Database.connect { WrappedConnection(datasource.connection) }
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
    private val listenersStorage = ListenersStorage()
    val eventsNotifier = EventsNotifier(listenersStorage)

    init {
        database.transactionManager.currentTransaction?.rollback()
        logger.info { "Connected to main database at $normalizedPathToDb" }
    }

    fun registerSessionInThread(session: SqliteSession, thread: Thread) {
        lock.withLock {
            if (closed) throw IllegalStateException("Database is closed")
            val current = sessionByThread[thread]
            if (current != null) throw IllegalStateException("$current is already registered for thread $thread")
            sessionByThread[thread] = session
        }
    }

    fun checkAndStartTransaction(session: SqliteSession) {
        if (sessionWithWriteTransaction === session) return
        lock.withLock {
            if (sessionWithWriteTransaction !== null) {
                throw IllegalStateException("$session has active transaction")
            }
            if (sessionInBlockingMode != null && sessionInBlockingMode !== session) {
                throw IllegalStateException("$sessionInBlockingMode is in blocking mode")
            }
            sessionWithWriteTransaction = session
        }
        catalogCaches.values.forEach { it.onTransactionStart() }
        eventsNotifier.onTransactionStart()
    }

    fun isSessionOwnsWriteTransaction(session: SqliteSession): Boolean = sessionWithWriteTransaction === session

    fun finishTransaction(session: SqliteSession, commit: Boolean, closeTransaction: Boolean = true) {
        if (!isSessionOwnsWriteTransaction(session)) {
            throw IllegalStateException("Session does not own active transaction")
        }
        catalogCaches.values.forEach { if (commit) it.onTransactionCommit() else it.onTransactionRollback() }
        database.transactionManager.currentTransaction?.run {
            if (commit) commit() else rollback()
            if (!commit || closeTransaction) close()
        }
        if (commit) eventProcessor.onTransactionFinished()
        if (commit) eventsNotifier.onTransactionCommit() else eventsNotifier.onTransactionRollback()
        if (!commit || closeTransaction) sessionWithWriteTransaction = null
    }

    fun onSessionClosed(session: Session) {
        lock.withLock {
            val prev = sessionByThread.remove(Thread.currentThread())
            require(prev === session || prev == null) { "$session is not equal to $prev" }
            if (sessionWithWriteTransaction === session) {
                sessionWithWriteTransaction = null
            }
            if (sessionInBlockingMode === session) {
                sessionInBlockingMode = null
            }
        }
    }

    override fun createSession(
        name: String,
        blockingMode: Boolean,
        createEvents: Boolean,
        autoCommit: Boolean
    ): Session {
        if (blockingMode) {
            if (sessionInBlockingMode != null) {
                throw IllegalStateException("$sessionInBlockingMode in blocking mode already exists")
            }
            if (sessionWithWriteTransaction != null) {
                throw IllegalStateException("$sessionWithWriteTransaction already writes to db")
            }
        }
        return SqliteSession(name, this, createEvents, autoCommit).also {
            if (blockingMode) {
                sessionInBlockingMode = it
            }
        }
    }

    override fun getDb() = database

    override fun subscribe(kinds: Set<Db.SubscribeKind>, listener: DbListener): Db.Subscription =
        listenersStorage.addSubscribe(kinds, listener)

    override fun close() {
        lock.withLock {
            if (sessionByThread.isNotEmpty()) {
                val copy = sessionByThread.values.toList()
                logger.warn { "There are still sessions open: " + copy.joinToString { it.toString() }  }
                copy.forEach { it.forceClose() }
            }
            if (connections.isNotEmpty()) {
                logger.warn { "There are still connections open: "  + connections.size  }
            }
            connections.toList().forEach { it.close() }
        }
        activeSqliteDatabases.remove(normalizedPathToDb)
        logger.info { "Close database at $normalizedPathToDb" }
    }

    private inner class WrappedConnection(private val connection: Connection): Connection by connection {
        init {
            lock.withLock {
                connections.add(connection)
            }
        }
        override fun close() {
            lock.withLock {
                connections.remove(connection)
            }
            connection.close()
        }
    }
}

fun createSqliteDatabase(pathToDb: Path): Db = SqliteDatabase(pathToDb)
