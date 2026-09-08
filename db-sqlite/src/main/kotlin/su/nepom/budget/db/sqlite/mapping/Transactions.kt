package su.nepom.budget.db.sqlite.mapping

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.ktorm.database.Database
import org.ktorm.dsl.AssignmentsBuilder
import org.ktorm.dsl.BatchInsertStatementBuilder
import org.ktorm.dsl.Query
import org.ktorm.dsl.QueryRowSet
import org.ktorm.dsl.eq
import org.ktorm.dsl.forEach
import org.ktorm.dsl.from
import org.ktorm.dsl.innerJoin
import org.ktorm.dsl.select
import org.ktorm.schema.Table
import org.ktorm.schema.boolean
import org.ktorm.schema.int
import org.ktorm.schema.jdbcTimestamp
import org.ktorm.schema.long
import org.ktorm.schema.varchar
import su.nepom.budget.db.sqlite.utils.DatabaseHolder
import su.nepom.budget.db.sqlite.utils.toTimestamp
import su.nepom.budget.db.sqlite.utils.uuidCode
import su.nepom.budget.event.TransactionContent
import su.nepom.budget.event.TransactionContentItem
import su.nepom.budget.event.TransactionRecord
import su.nepom.budget.model.AccountId
import su.nepom.budget.model.RawMoney
import su.nepom.budget.model.Uuid
import su.nepom.budget.model.uuidCode
import java.sql.Timestamp

internal object Transactions : Table<Nothing>("transaction") {
  val uuid = varchar("uuid").primaryKey()
  val date = jdbcTimestamp("date")
  val description = varchar("description")
  val flag = boolean("flag")
  val deleted = boolean("deleted")
  val modifiedAt = jdbcTimestamp("modified_at")
  val modifiedBy = varchar("modified_by")
}

internal object TransactionItems : Table<Nothing>("transaction_item") {
  val id = long("id").primaryKey()
  val transactionUuid = varchar("transaction_uuid")
  val transactionDate = jdbcTimestamp("transaction_date")
  val transactionDeleted = boolean("transaction_deleted")
  val accountUuid = varchar("account_uuid")
  val no = int("no")
  val money = long("money")
  val description = varchar("description")
  val flag = boolean("flag")
  val reservedUntil = varchar("reserved_until")
}

internal fun AssignmentsBuilder.setFromTransaction(
  transaction: TransactionContent,
  modifiedAt: Instant,
  modifiedBy: String,
) {
  set(Transactions.uuid, transaction.uuidCode())
  set(Transactions.date, Timestamp(transaction.date.toEpochMilliseconds()))
  set(Transactions.description, transaction.description)
  set(Transactions.flag, transaction.flag)
  set(Transactions.deleted, transaction.deleted)
  set(Transactions.modifiedAt, Timestamp(modifiedAt.toEpochMilliseconds()))
  set(Transactions.modifiedBy, modifiedBy)
}

internal fun BatchInsertStatementBuilder<TransactionItems>.setFromTransactionItems(transaction: TransactionContent) {
  val date = transaction.date.toTimestamp()
  transaction.items.forEachIndexed { index, item ->
    item {
      set(TransactionItems.transactionUuid, transaction.uuidCode())
      set(TransactionItems.transactionDate, date)
      set(TransactionItems.transactionDeleted, transaction.deleted)
      set(TransactionItems.accountUuid, item.account.uuidCode())
      set(TransactionItems.no, index)
      set(TransactionItems.money, item.money.value)
      set(TransactionItems.description, item.description)
      set(TransactionItems.flag, item.flag)
      set(TransactionItems.reservedUntil, item.reservedUntil?.toString())
    }
  }
}

internal fun QueryRowSet.toItemAndNo(): Pair<TransactionContentItem, Int> =
  TransactionContentItem(
    AccountId(Uuid(this[TransactionItems.accountUuid]!!)),
    RawMoney(this[TransactionItems.money]!!),
    this[TransactionItems.description]!!,
    this[TransactionItems.flag]!!,
    this[TransactionItems.reservedUntil]?.let { LocalDate.parse(it) }
  ) to this[TransactionItems.no]!!

internal fun Query.toTransactionsMap(): Map<String,
        Pair<TransactionRecord, MutableList<Pair<TransactionContentItem, Int>>>> {
  val data = mutableMapOf<String,
          Pair<TransactionRecord,
                  MutableList<Pair<TransactionContentItem, Int>>>>()

  forEach { rs ->
    val uuid = rs[Transactions.uuid]!!
    data.compute(uuid) { _, current ->
      if (current == null) {
        val transaction = TransactionRecord(
          TransactionContent(
            Uuid(uuid),
            Instant.fromEpochMilliseconds(rs[Transactions.date]!!.time),
            rs[Transactions.description]!!,
            listOf(),
            rs[Transactions.flag]!!,
            rs[Transactions.deleted]!!
          ),
          Instant.fromEpochMilliseconds(rs[Transactions.modifiedAt]!!.time),
          rs[Transactions.modifiedBy]!!,
        )
        val itemAndNo = rs.toItemAndNo()
        transaction to mutableListOf(itemAndNo)
      } else {
        current.second.add(rs.toItemAndNo())
        current
      }
    }
  }
  return data
}

internal fun Pair<TransactionRecord, MutableList<Pair<TransactionContentItem, Int>>>.
        toTransaction(): TransactionRecord {
  val (transactionWithoutItems, itemsWithNo) = this
  itemsWithNo.sortBy { it.second }
  return transactionWithoutItems.copy(
    transaction = transactionWithoutItems.transaction.copy(items = itemsWithNo.map { it.first })
  )
}

internal fun Query.toTransactions(): List<TransactionRecord> {
  val data = toTransactionsMap()
  return data.values.map { it.toTransaction() }
}

internal fun Database.fromTransactions() = from(TransactionItems)
  .innerJoin(Transactions, on = TransactionItems.transactionUuid eq Transactions.uuid)

internal fun DatabaseHolder.fromTransactions() = getDb().fromTransactions()

internal fun Database.fromTransactionsSelect() = fromTransactions().select()

internal fun DatabaseHolder.fromTransactionsSelect() = getDb().fromTransactionsSelect()




