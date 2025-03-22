package su.nepom.budget.db

import su.nepom.budget.db.dao.AccountDao
import su.nepom.budget.db.dao.CrudDao
import su.nepom.budget.db.dao.CurrencyDao
import su.nepom.budget.db.dao.EventDao
import su.nepom.budget.db.dao.TransactionDao
import su.nepom.budget.event.ActualVersionContent
import su.nepom.budget.model.ObjectKind

interface Session: AutoCloseable {
    val currencyDao: CurrencyDao

    val accountDao: AccountDao

    val transactionDao: TransactionDao

    val eventDao: EventDao

    fun commit()

    fun rollback()

    @Suppress("UNCHECKED_CAST")
    fun dao(kind: ObjectKind): CrudDao<ActualVersionContent> = when(kind) {
        ObjectKind.CURRENCY -> currencyDao as CrudDao<ActualVersionContent>
        ObjectKind.ACCOUNT -> accountDao as CrudDao<ActualVersionContent>
        ObjectKind.TRANSACTION -> transactionDao as CrudDao<ActualVersionContent>
    }

    fun save(vararg objects: ActualVersionContent) {
        objects.forEach { dao(it.objectKind).save(it) }
    }

}