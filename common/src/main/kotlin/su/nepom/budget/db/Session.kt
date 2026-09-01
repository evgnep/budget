package su.nepom.budget.db

import su.nepom.budget.db.dao.AccountDao
import su.nepom.budget.db.dao.CrudDao
import su.nepom.budget.db.dao.CurrencyDao
import su.nepom.budget.db.dao.EventDao
import su.nepom.budget.db.dao.PropertyDao
import su.nepom.budget.db.dao.SimpleObjectDao
import su.nepom.budget.db.dao.TransactionDao
import su.nepom.budget.event.ActualVersionContent
import su.nepom.budget.model.ObjectKind

interface Session: AutoCloseable {
    val db: Db

    val currencyDao: CurrencyDao

    val accountDao: AccountDao

    val transactionDao: TransactionDao

    val eventDao: EventDao

    val propertyDao: PropertyDao

    fun <T : ActualVersionContent> simpleObjectDao(kind: ObjectKind): SimpleObjectDao<T>

    fun commit(closeTransaction: Boolean = true)

    fun rollback()

    @Suppress("UNCHECKED_CAST")
    fun dao(kind: ObjectKind): CrudDao<ActualVersionContent> = when {
        kind == ObjectKind.CURRENCY -> currencyDao as CrudDao<ActualVersionContent>
        kind == ObjectKind.ACCOUNT -> accountDao as CrudDao<ActualVersionContent>
        kind == ObjectKind.TRANSACTION -> transactionDao as CrudDao<ActualVersionContent>
        kind.simpleObject -> simpleObjectDao(kind)
        else -> throw IllegalArgumentException("Unknown kind $kind")
    }

    fun save(vararg objects: ActualVersionContent) {
        objects.forEach { dao(it.objectKind).save(it) }
    }

    /**
     * If you are using coroutines then all calls to db (and closing session too!) should be inside coroDbOp and
     * you should not change dispatcher in block
     */
    suspend fun <T> coroDbOp(block: suspend Session.() -> T): T

    suspend fun <T> coroUse(block: suspend Session.() -> T): T {
        try {
            return block()
        } finally {
            coroDbOp { close() }
        }
    }
}