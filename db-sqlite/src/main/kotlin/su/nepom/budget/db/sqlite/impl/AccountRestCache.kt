package su.nepom.budget.db.sqlite.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import org.ktorm.dsl.associate
import org.ktorm.dsl.eq
import org.ktorm.dsl.groupBy
import org.ktorm.dsl.select
import org.ktorm.dsl.sum
import org.ktorm.dsl.where
import org.ktorm.entity.add
import org.ktorm.entity.associate
import org.ktorm.entity.clear
import su.nepom.budget.db.sqlite.SqliteDatabase
import su.nepom.budget.db.sqlite.mapping.AccountRestEntity
import su.nepom.budget.db.sqlite.mapping.TransactionItems
import su.nepom.budget.db.sqlite.mapping.accountRests
import su.nepom.budget.db.sqlite.utils.from
import su.nepom.budget.model.AccountId
import su.nepom.budget.model.RawMoney
import su.nepom.budget.model.Uuid
import su.nepom.budget.model.uuidCode

private val logger = KotlinLogging.logger { }

internal class AccountRestCache(db: SqliteDatabase) :
    TableCopy<AccountId, RawMoney>(
        RawMoney::class.java,
        { db.accountRests.associate { AccountId(Uuid(it.uuid)) to RawMoney(it.rest) } }
    ) {
    init {
        checkRests(db)
    }

    private fun checkRests(db: SqliteDatabase) {
        val correct = db.database.useTransaction {
            val realRests = db.from(TransactionItems)
                .select(TransactionItems.accountUuid, sum(TransactionItems.money))
                .where { TransactionItems.transactionDeleted eq false }
                .groupBy(TransactionItems.accountUuid)
                .associate { AccountId(Uuid(it[TransactionItems.accountUuid]!!)) to RawMoney(it.getLong(2)) }
            if (realRests == committed) true
            else {
                logger.warn { "Rests are incorrect\nExpected: $committed\nActual $realRests"  }
                committed.clear()
                committed.putAll(realRests)
                db.accountRests.clear()
                realRests.forEach { (accountId, rest) ->
                    db.accountRests.add(AccountRestEntity {
                        this.uuid = accountId.uuidCode()
                        this.rest = rest.value
                        this.name = db.accountCache[accountId]!!.name
                    })
                }
                false
            }
        }
        logger.info { if (correct) "Rests are correct" else "Rests are updated" }
    }
}