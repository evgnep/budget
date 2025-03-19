package su.nepom.budget.access.ingester.generator.mapper

import kotlinx.datetime.Instant
import org.ktorm.dsl.AssignmentsBuilder
import org.ktorm.dsl.QueryRowSet
import org.ktorm.dsl.eq
import org.ktorm.dsl.insert
import org.ktorm.dsl.update
import su.nepom.budget.access.ingester.access.ObjectAccess
import su.nepom.budget.access.ingester.access.TransactionAccess
import su.nepom.budget.access.ingester.access.UserAccess
import su.nepom.budget.access.ingester.generator.procesed.dao.Accounts
import su.nepom.budget.access.ingester.generator.procesed.dao.Transactions
import su.nepom.budget.access.ingester.generator.procesed.database
import su.nepom.budget.event.ActualEvent
import su.nepom.budget.event.EventType
import su.nepom.budget.event.TransactionContent
import su.nepom.budget.event.TransactionContentItem
import su.nepom.budget.model.AccountId
import su.nepom.budget.model.AccountKind
import su.nepom.budget.model.Uuid

internal class TransactionsMapper(
    private val accessUsers: Map<Int, UserAccess>,
    allAccounts: List<AccountProcessed>,
    private val accountsMapper: AccountsMapper,
) : Mapper {
    private val accountByAccessId: MutableMap<Int, AccountProcessed> =
        allAccounts.associateByTo(mutableMapOf()) { it.obj.id }

    override fun toProcessedObject(rs: QueryRowSet) = TransactionProcessed(
        TransactionAccess(
            rs[Transactions.id]!!,
            Instant.fromEpochSeconds(rs[Transactions.created]!!),
            rs[Transactions.userId]!!,
            rs[Transactions.accountId]!!,
            rs[Transactions.moneyReal]!!,
            rs[Transactions.accountBudgetId],
            rs[Transactions.moneyBudget],
            rs[Transactions.description]!!,
            rs[Transactions.accountTargetId],
            rs[Transactions.moneyTransfer],
            rs[Transactions.flag]!! == 1,
        ),
        Uuid(rs[Transactions.uuid]!!),
        rs[Transactions.deleted]!! == 1
    )

    override fun onDelete(old: ObjectProcessed): EventsAndActions {
        old as TransactionProcessed
        if (old.deleted) return EventsAndActions()
        return EventsAndActionsBuilder(old.obj, old.uuid, true, Action.DELETE).build()
    }

    override fun onNew(new: ObjectAccess): EventsAndActions {
        new as TransactionAccess
        return EventsAndActionsBuilder(new, Uuid.generate(), false, Action.NEW).build()
    }

    override fun onUpdate(old: ObjectProcessed, new: ObjectAccess): EventsAndActions {
        old as TransactionProcessed
        new as TransactionAccess
        return EventsAndActionsBuilder(new, old.uuid, false, Action.UPDATE).build()
    }

    private enum class Action { NEW, UPDATE, DELETE }

    private inner class EventsAndActionsBuilder(
        val obj: TransactionAccess,
        val uuid: Uuid,
        val deleted: Boolean,
        val action: Action,
    ) {
        private val events = mutableListOf<ActualEvent>()
        private val dbActions = mutableListOf<() -> Unit>()

        fun build(): EventsAndActions {
            val context = makeContext()
            events.add(
                createEventForMapper(
                    context,
                    if (action == Action.NEW) EventType.NEW else EventType.UPDATE,
                    accessUsers[obj.userId]?.name ?: "unknown",
                    obj.id.toString()
                )
            )
            dbActions.add {
                if (action == Action.NEW) {
                    database.insert(Transactions) {
                        mapTransaction(it, context)
                    }
                } else {
                    database.update(Transactions) {
                        if (action == Action.DELETE) {
                            set(it.deleted, 1)
                        } else {
                            mapTransaction(it, context)
                        }
                        where { it.id eq obj.id }
                    }
                }
            }
            return EventsAndActions(events, dbActions)
        }

        private fun AssignmentsBuilder.mapTransaction(it: Transactions, context: TransactionContent) {
            set(it.id, obj.id)
            set(it.created, obj.date.epochSeconds)
            set(it.userId, obj.userId)
            set(it.accountId, obj.accountId)
            set(it.moneyReal, obj.moneyReal)
            set(it.accountBudgetId, obj.accountBudgetId)
            set(it.moneyBudget, obj.moneyBudget)
            set(it.description, obj.description)
            set(it.accountTargetId, obj.accountTargetId)
            set(it.moneyTransfer, obj.moneyTransfer)
            set(it.flag, if (obj.flag) 1 else 0)
            set(it.uuid, context.id.id)
            set(it.deleted, 0)
        }

        private fun makeContext() = TransactionContent(
            uuid,
            obj.date,
            obj.description,
            makeItems(),
            obj.flag,
            deleted
        )

        private fun makeItems() = when {
            obj.accountTargetId != null && obj.moneyTransfer == null -> makeItemsForTransfer()
            obj.accountTargetId != null && obj.moneyTransfer != null -> makeItemsForExchange()
            obj.accountBudgetId != null -> makeItemsForBudget()
            else -> makeItemsForSimple()
        }

        private fun makeItemsForTransfer() = buildList<TransactionContentItem> {
            if (obj.moneyReal != 0L) {
                val money = convertMoney(obj.moneyReal, obj.accountId)
                add(TransactionContentItem(obj.accountId.getOrCreateAccount(AccountKind.MONEY), money))
                add(TransactionContentItem(obj.accountTargetId!!.getOrCreateAccount(AccountKind.MONEY), -money))
            }
            if ((obj.moneyBudget ?: 0L) != 0L) {
                val money = convertMoney(obj.moneyBudget!!, obj.accountId)
                add(TransactionContentItem(obj.accountId.getOrCreateAccount(AccountKind.BUDGET), money))
                add(TransactionContentItem(obj.accountTargetId!!.getOrCreateAccount(AccountKind.BUDGET), -money))
            }
        }

        private fun makeItemsForExchange() = buildList<TransactionContentItem> {
            val moneyFrom = convertMoney(obj.moneyReal, obj.accountId)
            add(TransactionContentItem(obj.accountId.getOrCreateAccount(AccountKind.MONEY), moneyFrom))
            add(TransactionContentItem(obj.accountId.getOrCreateAccount(AccountKind.BUDGET), moneyFrom))

            val moneyTo = -convertMoney(obj.moneyTransfer!!, obj.accountTargetId!!)
            add(TransactionContentItem(obj.accountTargetId.getOrCreateAccount(AccountKind.MONEY), moneyTo))
            add(TransactionContentItem(obj.accountTargetId.getOrCreateAccount(AccountKind.BUDGET), moneyTo))
        }

        private fun makeItemsForBudget() = buildList<TransactionContentItem> {
            val money = convertMoney(obj.moneyReal, obj.accountId)
            add(TransactionContentItem(obj.accountId.getOrCreateAccount(AccountKind.MONEY), money))
            add(TransactionContentItem(obj.accountBudgetId!!.getOrCreateAccount(AccountKind.BUDGET), money))
        }

        private fun makeItemsForSimple() = buildList<TransactionContentItem> {
            val money = convertMoney(obj.moneyReal, obj.accountId)
            add(TransactionContentItem(obj.accountId.getOrCreateAccount(AccountKind.MONEY), money))
            add(TransactionContentItem(obj.accountId.getOrCreateAccount(AccountKind.BUDGET), money))
        }

        private fun Int.getOrCreateAccount(kind: AccountKind): AccountId {
            val accountProcessed = accountByAccessId[this] ?: error("Account not found: $this")
            if (kind == AccountKind.BUDGET && accountProcessed.budget != null) return accountProcessed.budget
            if (kind == AccountKind.MONEY && accountProcessed.money != null) return accountProcessed.money

            val account = accountProcessed.obj
            val currency = accountsMapper.currencyCode(account.currencyId)
            val accountId = AccountId(codeOfAccount(this, account.name, currency, kind))
            val content = accountsMapper.makeContent(account, accountId, kind).copy(hidden = true)
            events.add(createEventForMapper(content, EventType.NEW))
            dbActions.add {
                database.update(Accounts) {
                    if (kind == AccountKind.MONEY) {
                        set(it.codeMoney, accountId.readable.code)
                        set(it.uuidMoney, accountId.uuid.id)
                    }
                    else {
                        set(it.codeBudget, accountId.readable.code)
                        set(it.uuidBudget, accountId.uuid.id)
                    }
                    where { it.id eq account.id }
                }
            }

            val newAccountProcessed = if (kind == AccountKind.MONEY) accountProcessed.copy(money = accountId)
            else accountProcessed.copy(budget = accountId)

            accountByAccessId[this] = newAccountProcessed
            return accountId
        }

        private fun convertMoney(source: Long, accountId: Int): Long {
            val divider = accountsMapper
                .currencyInfo(accountByAccessId[accountId]?.obj?.currencyId ?: error("Account not found: $accountId"))
                .divider
            return source / divider
        }
    }
}

internal data class TransactionProcessed(
    override val obj: TransactionAccess,
    val uuid: Uuid,
    val deleted: Boolean
) : ObjectProcessed