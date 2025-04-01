package su.nepom.budget.db.sqlite

import kotlinx.datetime.Instant
import org.ktorm.database.Database
import org.ktorm.dsl.Query
import org.ktorm.dsl.QueryRowSet
import org.ktorm.dsl.asc
import org.ktorm.dsl.associate
import org.ktorm.dsl.countDistinct
import org.ktorm.dsl.desc
import org.ktorm.dsl.eq
import org.ktorm.dsl.groupBy
import org.ktorm.dsl.gt
import org.ktorm.dsl.gte
import org.ktorm.dsl.inList
import org.ktorm.dsl.innerJoin
import org.ktorm.dsl.like
import org.ktorm.dsl.limit
import org.ktorm.dsl.lt
import org.ktorm.dsl.lte
import org.ktorm.dsl.map
import org.ktorm.dsl.orderBy
import org.ktorm.dsl.select
import org.ktorm.dsl.selectDistinct
import org.ktorm.dsl.sum
import org.ktorm.dsl.where
import org.ktorm.dsl.whereWithConditions
import org.ktorm.entity.add
import org.ktorm.entity.associateTo
import org.ktorm.entity.filter
import org.ktorm.schema.ColumnDeclaring
import org.ktorm.support.sqlite.iif
import su.nepom.budget.Global
import su.nepom.budget.db.Db
import su.nepom.budget.db.dao.TransactionDao
import su.nepom.budget.db.model.AccountRest
import su.nepom.budget.db.sqlite.mapping.AccountRestEntity
import su.nepom.budget.db.sqlite.mapping.Accounts
import su.nepom.budget.db.sqlite.mapping.TransactionItems
import su.nepom.budget.db.sqlite.mapping.Transactions
import su.nepom.budget.db.sqlite.mapping.accountRests
import su.nepom.budget.db.sqlite.mapping.fromTransactions
import su.nepom.budget.db.sqlite.mapping.fromTransactionsSelect
import su.nepom.budget.db.sqlite.mapping.setFromTransaction
import su.nepom.budget.db.sqlite.mapping.setFromTransactionItems
import su.nepom.budget.db.sqlite.mapping.toTransactions
import su.nepom.budget.db.sqlite.utils.DatabaseHolder
import su.nepom.budget.db.sqlite.utils.delete
import su.nepom.budget.db.sqlite.utils.from
import su.nepom.budget.db.sqlite.utils.insert
import su.nepom.budget.db.sqlite.utils.insertBatch
import su.nepom.budget.db.sqlite.utils.toTimestamp
import su.nepom.budget.db.sqlite.utils.uuidCode
import su.nepom.budget.event.Event
import su.nepom.budget.event.EventType
import su.nepom.budget.event.TransactionContent
import su.nepom.budget.event.TransactionContentItem
import su.nepom.budget.model.AccountId
import su.nepom.budget.model.AccountKind
import su.nepom.budget.model.CurrencyId
import su.nepom.budget.model.EventCoords
import su.nepom.budget.model.RawMoney
import su.nepom.budget.model.RawTurnover
import su.nepom.budget.model.Uuid
import su.nepom.budget.model.plus
import su.nepom.budget.model.rawMoney
import su.nepom.budget.model.uuidCode
import su.nepom.budget.utils.SecondsClock

internal class SqliteTransactionDao(private val session: SqliteSession) : TransactionDao, DatabaseHolder {
    override fun getDb(): Database = session.db.database

    override fun getById(id: Uuid): TransactionContent? = session.doReadOp {
        fromTransactionsSelect().where { Transactions.uuid eq id.id }.toTransactions().firstOrNull()
    }

    override fun count(): Int = session.doReadOp {
        from(Transactions).select(org.ktorm.dsl.count()).map { it.getInt(1) }.single()
    }

    override fun save(entity: TransactionContent): TransactionContent = session.doWriteOp {
        validate(entity)
        val oldEntity = getById(entity.id)
        session.saveEvent(entity, if (oldEntity == null) EventType.NEW else EventType.UPDATE)
        if (oldEntity != null) {
            delete(Transactions) { Transactions.uuid eq oldEntity.uuidCode() }
        }
        insert(Transactions) { setFromTransaction(entity) }
        insertBatch(TransactionItems) { setFromTransactionItems(entity) }
        updateAccountRests(oldEntity, entity)
        entity
    }

    override fun getByQuery(query: TransactionDao.Query): List<TransactionContent> = session.doReadOp {
        val transactionUuids = fromTransactions()
            .selectDistinct(Transactions.uuid)
            .where(query.filter)
            .orderBy(Transactions.date.run { if (query.sortByDateAsc) asc() else desc() })
            .limit(query.offset, query.limit)
            .map { it.getString(1)!! }
        if (transactionUuids.isEmpty()) emptyList()
        else fromTransactionsSelect()
            .where { Transactions.uuid inList transactionUuids }
            .orderBy(Transactions.date.run { if (query.sortByDateAsc) asc() else desc() })
            .toTransactions()
    }

    override fun countByFilter(filter: TransactionDao.Filter): Int = session.doReadOp {
        fromTransactions()
            .select(countDistinct(Transactions.uuid))
            .where(filter)
            .map { it.getInt(1) }.single()
    }

    override fun accountRest(accounts: Set<AccountId>, forDate: Instant?): Map<AccountId, RawMoney> = session.doReadOp {
        if (forDate == null) session.db.accountRestCache.getAll().let {
            if (accounts.isEmpty()) it
            else it.filterKeys { k -> k in accounts }
        } else from(TransactionItems)
            .select(TransactionItems.accountUuid, sum(TransactionItems.money))
            .whereWithConditions {
                it.add(TransactionItems.transactionDeleted eq false)
                it.add(TransactionItems.transactionDate lte forDate.toTimestamp())
                if (accounts.isNotEmpty()) {
                    it.add(TransactionItems.accountUuid inList accounts.map { c -> c.uuidCode() })
                }
            }
            .groupBy(TransactionItems.accountUuid)
            .associate { AccountId(Uuid(it[TransactionItems.accountUuid]!!)) to RawMoney(it.getLong(2)) }
    }

    override fun accountTurnover(
        accounts: Set<AccountId>,
        dateRange: ClosedRange<Instant>?
    ): Map<AccountId, RawTurnover> = session.doReadOp {
        from(TransactionItems)
            .select(
                TransactionItems.accountUuid,
                turnoverSumIncome(),
                turnoverSumExpenditure()
            )
            .whereWithConditions {
                if (accounts.isNotEmpty()) it.add(TransactionItems.accountUuid inList accounts.map { a -> a.uuidCode() })
                it.addTurnoverWhere(dateRange)
            }
            .groupBy(TransactionItems.accountUuid)
            .associate { AccountId(Uuid(it[TransactionItems.accountUuid]!!)) to it.toRawTurnover() }
    }

    override fun currencyRest(currencies: Set<CurrencyId>, forDate: Instant?): Map<CurrencyId, RawMoney> =
        session.doReadOp {
            if (forDate == null) {
                val accountRests = session.db.accountRestCache.getAll()
                val allAccountsByCurrency = session.db.accountCache.getAll().values
                    .filter { it.kind == AccountKind.MONEY }
                    .groupBy { it.currency }
                currencies.ifEmpty { session.db.currencyCache.getAll().keys }.associateWith { currency ->
                    allAccountsByCurrency[currency]
                        ?.fold(RawMoney.ZERO) { total, it -> total + (accountRests[it.id] ?: RawMoney.ZERO) }
                        ?: RawMoney.ZERO
                }
            } else from(TransactionItems)
                .innerJoin(Accounts, on = Accounts.uuid eq TransactionItems.accountUuid)
                .select(Accounts.currencyUuid, sum(TransactionItems.money))
                .whereWithConditions {
                    it.add(Accounts.kind eq AccountKind.MONEY.code)
                    it.add(TransactionItems.transactionDeleted eq false)
                    it.add(TransactionItems.transactionDate lte forDate.toTimestamp())
                    if (currencies.isNotEmpty()) {
                        it.add(Accounts.currencyUuid inList currencies.map { c -> c.uuidCode() })
                    }
                }
                .groupBy(Accounts.currencyUuid)
                .associate { CurrencyId(Uuid(it[Accounts.currencyUuid]!!)) to RawMoney(it.getLong(2)) }
        }

    override fun currencyTurnover(
        currencies: Set<CurrencyId>,
        dateRange: ClosedRange<Instant>?
    ): Map<CurrencyId, RawTurnover> = session.doReadOp {
        from(TransactionItems)
            .innerJoin(Accounts, on = TransactionItems.accountUuid eq Accounts.uuid)
            .select(
                Accounts.currencyUuid,
                turnoverSumIncome(),
                turnoverSumExpenditure()
            )
            .whereWithConditions {
                it.add(Accounts.kind eq AccountKind.MONEY.code)
                if (currencies.isNotEmpty()) it.add(Accounts.currencyUuid inList currencies.map { c -> c.uuidCode() })
                it.addTurnoverWhere(dateRange)
            }
            .groupBy(Accounts.currencyUuid)
            .associate { CurrencyId(Uuid(it[Accounts.currencyUuid]!!)) to it.toRawTurnover() }
    }

    private fun validate(entity: TransactionContent) {
        require(entity.items.size >= 1) { "Transaction must contain at least one item" }
        val totalByCurrencyAndKind: Map<Pair<Uuid, AccountKind>, RawMoney> = entity.items.groupingBy {
            val account = session.db.accountCache.getOrThrow(it.account)
            account.currency.uuid to account.kind
        }.fold(RawMoney.ZERO) { acc, it -> it.money + acc }
        totalByCurrencyAndKind.forEach { (currencyAndKind, total) ->
            val (currency, kind) = currencyAndKind
            if (total.value != 0L) {
                val other = totalByCurrencyAndKind[currency to kind.invert()]
                require(other == total) { "Totals for currency $currency in BUDGET and MONEY must be equal" }
            }
        }
    }

    private fun updateAccountRests(oldEntity: TransactionContent?, newEntity: TransactionContent) {
        if ((oldEntity == null || oldEntity.deleted) && newEntity.deleted) return
        val accountIds = buildSet<String> {
            oldEntity?.items?.forEach { add(it.account.uuidCode()) }
            newEntity.items.forEach { add(it.account.uuidCode()) }
        }
        val rests: MutableMap<String, Pair<AccountRestEntity, Boolean>> =
            accountRests.filter { it.uuid inList accountIds }.associateTo(mutableMapOf()) { it.uuid to (it to false) }
        oldEntity?.items?.forEach { it.updateRests(rests, -1) }
        newEntity.items.forEach { it.updateRests(rests, 1) }
        rests.values.forEach { (restEntity, isNew) ->
            if (isNew) accountRests.add(restEntity)
            else restEntity.flushChanges()
            val accountId = AccountId(Uuid(restEntity.uuid))
            val rawMoney = restEntity.rest.rawMoney
            session.db.accountRestCache.setInTransaction(accountId, rawMoney)
            restAccountRestEvent(accountId, rawMoney)
        }
    }

    private fun restAccountRestEvent(accountId: AccountId, rest: RawMoney) {
        session.db.eventsNotifier.addEvent(
            Db.SubscribeKind.ACCOUNT_REST,
            Event(
                EventCoords(Global.currentPlace, 0),
                SecondsClock.now(),
                Global.currentUser,
                EventType.UPDATE,
                listOf(),
                AccountRest(accountId, rest)
            )
        )
    }

    private fun TransactionContentItem.updateRests(
        rests: MutableMap<String, Pair<AccountRestEntity, Boolean>>,
        multiplier: Int
    ) {
        rests.compute(account.uuidCode()) { _, accountRest ->
            if (accountRest == null) AccountRestEntity {
                uuid = account.uuidCode()
                name = session.db.accountCache.getOrThrow(account).name
                rest = multiplier * money.value
            } to true
            else {
                accountRest.first.rest += multiplier * money.value
                accountRest
            }
        }
    }

    private fun Query.where(filter: TransactionDao.Filter) =
        whereWithConditions { conditions ->
            filter.from?.let { conditions.add(Transactions.date gte it.toTimestamp()) }
            filter.to?.let { conditions.add(Transactions.date lte it.toTimestamp()) }
            filter.accounts.takeIf { it.isNotEmpty() }
                ?.let { conditions.add(TransactionItems.accountUuid inList it.map { a -> a.uuidCode() }) }
            filter.deleted?.let { conditions.add(Transactions.deleted eq it) }
            filter.descriptionLike?.let { conditions.add(Transactions.description like it) }
            filter.flag?.let { conditions.add(Transactions.flag eq it) }
        }
}

private fun turnoverSumExpenditure() = sum(
    iif(
        TransactionItems.money lt 0,
        TransactionItems.money,
        TransactionItems.money.wrapArgument(0)
    )
)

private fun turnoverSumIncome() = sum(
    iif(
        TransactionItems.money gt 0,
        TransactionItems.money,
        TransactionItems.money.wrapArgument(0)
    )
)

private fun MutableList<ColumnDeclaring<Boolean>>.addTurnoverWhere(dateRange: ClosedRange<Instant>?) {
    add(TransactionItems.transactionDeleted eq false)
    if (dateRange != null) {
        add(TransactionItems.transactionDate gte dateRange.start.toTimestamp())
        add(TransactionItems.transactionDate lte dateRange.endInclusive.toTimestamp())
    }
}

private fun QueryRowSet.toRawTurnover() = RawTurnover.fromLong(getLong(2), getLong(3))