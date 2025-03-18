package su.nepom.budget.access.ingester.generator.mapper

import org.ktorm.dsl.QueryRowSet
import org.ktorm.dsl.eq
import org.ktorm.dsl.insert
import org.ktorm.dsl.update
import su.nepom.budget.access.ingester.access.AccountAccess
import su.nepom.budget.access.ingester.access.AccountType
import su.nepom.budget.access.ingester.access.ObjectAccess
import su.nepom.budget.access.ingester.generator.procesed.dao.Accounts
import su.nepom.budget.access.ingester.generator.procesed.database
import su.nepom.budget.event.AccountContent
import su.nepom.budget.event.EventType
import su.nepom.budget.model.AccountCode
import su.nepom.budget.model.AccountId
import su.nepom.budget.model.AccountKind
import su.nepom.budget.model.CurrencyCode
import su.nepom.budget.model.Uuid

internal class AccountsMapper(
    allCurrencies: List<CurrencyProcessed>,
) : Mapper {
    private val currenciesByAccessId: Map<Int, CurrencyProcessed> = allCurrencies.associateBy { it.obj.id }

    private val allAccounts = mutableMapOf<Int, AccountProcessed>()

    fun getAllAccounts() = allAccounts.values.toList()

    override fun toProcessedObject(rs: QueryRowSet) = AccountProcessed(
        AccountAccess(
            rs[Accounts.id]!!,
            AccountType.entries.first { it.id == rs[Accounts.type]!! },
            rs[Accounts.name]!!,
            rs[Accounts.currencyId]!!,
            rs[Accounts.closed]!! == 1,
            rs[Accounts.order]!!
        ),
        rs[Accounts.codeMoney]?.let { AccountId(Uuid(rs[Accounts.uuidMoney]!!), AccountCode(it)) },
        rs[Accounts.codeBudget]?.let { AccountId(Uuid(rs[Accounts.uuidBudget]!!), AccountCode(it)) },
    ).also { allAccounts[it.obj.id] = it }

    override fun onDelete(old: ObjectProcessed) =
        throw UnsupportedOperationException("Account deletion is not supported: ${old.obj}")

    override fun onNew(new: ObjectAccess): EventsAndActions {
        new as AccountAccess
        val id = AccountId(codeOfAccount(new.id, new.name, currencyCode(new.currencyId), new.kind()))
        val content = makeContent(new, id)
        val accountProcessed = AccountProcessed(
            new,
            if (content.kind == AccountKind.MONEY) id else null,
            if (content.kind == AccountKind.BUDGET) id else null
        )
        allAccounts[accountProcessed.obj.id] = accountProcessed
        return EventsAndActions(createEventForMapper(content, EventType.NEW)) {
            database.insert(Accounts) {
                set(it.id, new.id)
                set(it.type, new.type.id)
                set(it.name, new.name)
                set(it.currencyId, new.currencyId)
                set(it.closed, if (new.closed) 1 else 0)
                set(it.order, new.order)
                set(it.codeMoney, accountProcessed.money?.readable?.code)
                set(it.codeBudget, accountProcessed.budget?.readable?.code)
                set(it.uuidMoney, accountProcessed.money?.uuid?.id)
                set(it.uuidBudget, accountProcessed.budget?.uuid?.id)
            }
        }
    }

    fun makeContent(
        obj: AccountAccess,
        id: AccountId,
        kind: AccountKind = obj.kind(),
    ) = AccountContent(
        id,
        obj.name,
        "",
        currenciesByAccessId[obj.currencyId]!!.id,
        kind,
        setOf(),
        obj.order,
        obj.closed
    )

    private fun AccountAccess.kind() = when (type) {
        AccountType.MONEY -> AccountKind.MONEY
        else -> AccountKind.BUDGET
    }

    fun currencyInfo(currencyId: Int): CurrencyProcessed =
        currenciesByAccessId[currencyId]
            ?: throw IllegalStateException("Unknown currency: $currencyId")

    fun currencyCode(currencyId: Int) = CurrencyCode(currencyInfo(currencyId).id.readable.code)

    override fun onUpdate(old: ObjectProcessed, new: ObjectAccess): EventsAndActions {
        new as AccountAccess
        old as AccountProcessed
        if (new.currencyId != old.obj.currencyId || new.type != old.obj.type) {
            throw UnsupportedOperationException("Updating currencyId or type is not supported: ${old.obj}, $new")
        }
        val events = listOfNotNull(old.money, old.budget).map { id ->
            createEventForMapper(
                makeContent(
                    new,
                    id,
                    if (id == old.money) AccountKind.MONEY else AccountKind.BUDGET
                ), EventType.UPDATE
            )
        }
        allAccounts[new.id] = old.copy(obj = new)
        return EventsAndActions(
            events,
            listOf {
                database.update(Accounts) {
                    set(it.name, new.name)
                    set(it.closed, if (new.closed) 1 else 0)
                    set(it.order, new.order)
                    where { it.id eq new.id }
                }
            })
    }
}

internal data class AccountProcessed(
    override val obj: AccountAccess,
    val money: AccountId?,
    val budget: AccountId?,
) : ObjectProcessed

internal fun codeOfAccount(id: Int, name: String, currencyCode: CurrencyCode, kind: AccountKind) =
    AccountCode("$name-$id ${currencyCode.code} ${kind.code}")
