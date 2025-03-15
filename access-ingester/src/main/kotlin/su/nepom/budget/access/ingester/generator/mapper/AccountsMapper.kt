package su.nepom.budget.access.ingester.generator.mapper

import org.ktorm.dsl.QueryRowSet
import org.ktorm.dsl.eq
import org.ktorm.dsl.insert
import org.ktorm.dsl.update
import su.nepom.budget.access.ingester.Config
import su.nepom.budget.access.ingester.access.AccountAccess
import su.nepom.budget.access.ingester.access.AccountType
import su.nepom.budget.access.ingester.access.CurrencyAccess
import su.nepom.budget.access.ingester.access.ObjectAccess
import su.nepom.budget.access.ingester.generator.procesed.dao.Accounts
import su.nepom.budget.access.ingester.generator.procesed.database
import su.nepom.budget.events.model.AccountContent
import su.nepom.budget.events.model.EventType
import su.nepom.budget.model.AccountCode
import su.nepom.budget.model.AccountKind
import su.nepom.budget.model.CurrencyCode

internal class AccountsMapper(
    private val accessCurrencies: Map<Int, CurrencyAccess>,
    private val currencies: Map<String, Config.CurrencyInfo>,
    private val accountsInfo: AccountsInfo,
) : Mapper {
    override fun toProcessedObject(rs: QueryRowSet) = AccountProcessed(
        AccountAccess(
            rs[Accounts.id]!!,
            AccountType.entries.first { it.id == rs[Accounts.type]!! },
            rs[Accounts.name]!!,
            rs[Accounts.currencyId]!!,
            rs[Accounts.closed]!! == 1,
            rs[Accounts.order]!!
        ),
        rs[Accounts.codeMoney]?.let { AccountCode(it) },
        rs[Accounts.codeBudget]?.let { AccountCode(it) },
    ).also { accountsInfo[it.obj.id] = it }

    override fun onDelete(old: ObjectProcessed) =
        throw UnsupportedOperationException("Account deletion is not supported: ${old.obj}")

    override fun onNew(new: ObjectAccess): EventsAndActions {
        new as AccountAccess
        val code = accountsInfo.codeOfNewAccount(new.id, new.name, currencyCode(new.currencyId), new.kind())
        val content = makeContent(new, code)
        val accountProcessed = AccountProcessed(
            new,
            if (content.kind == AccountKind.MONEY) content.code else null,
            if (content.kind == AccountKind.BUDGET) content.code else null
        )
        accountsInfo[new.id] = accountProcessed
        return EventsAndActions(createEventForMapper(content, EventType.NEW)) {
            database.insert(Accounts) {
                set(it.id, new.id)
                set(it.type, new.type.id)
                set(it.name, new.name)
                set(it.currencyId, new.currencyId)
                set(it.closed, if (new.closed) 1 else 0)
                set(it.order, new.order)
                set(it.codeMoney, accountProcessed.money?.code)
                set(it.codeBudget, accountProcessed.budget?.code)
            }
        }
    }

    fun makeContent(
        obj: AccountAccess,
        code: AccountCode,
        kind: AccountKind = obj.kind(),
    ) = AccountContent(
        code,
        obj.name,
        "",
        currencyCode(obj.currencyId),
        kind,
        setOf(),
        obj.order,
        obj.closed
    )

    private fun AccountAccess.kind() = when (type) {
        AccountType.MONEY -> AccountKind.MONEY
        else -> AccountKind.BUDGET
    }

    fun currencyInfo(currencyId: Int): Config.CurrencyInfo =
        accessCurrencies[currencyId]?.let { currencies[it.name] }
            ?: throw IllegalStateException("Unknown currency: $this")

    fun currencyCode(currencyId: Int) = CurrencyCode(currencyInfo(currencyId).code)

    override fun onUpdate(old: ObjectProcessed, new: ObjectAccess): EventsAndActions {
        new as AccountAccess
        old as AccountProcessed
        if (new.currencyId != old.obj.currencyId || new.type != old.obj.type) {
            throw UnsupportedOperationException("Updating currencyId or type is not supported: ${old.obj}, $new")
        }
        accountsInfo[new.id] = old.copy(obj = new)
        val events = listOfNotNull(old.money, old.budget).map { code ->
            createEventForMapper(
                makeContent(
                    new,
                    code,
                    if (code == old.money) AccountKind.MONEY else AccountKind.BUDGET
                ), EventType.UPDATE
            )
        }
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
    val money: AccountCode?,
    val budget: AccountCode?,
) : ObjectProcessed

internal class AccountsInfo {
    private val accountsByAccessId = mutableMapOf<Int, AccountProcessed>()

    private val accountByCode = mutableMapOf<AccountCode, AccountProcessed>()

    operator fun set(accessId: Int, value: AccountProcessed) {
        accountsByAccessId[accessId] = value
        if (value.money != null)
            accountByCode[value.money] = value
        if (value.budget != null)
            accountByCode[value.budget] = value
    }

    operator fun get(accessId: Int): AccountProcessed =
        accountsByAccessId[accessId] ?: throw IllegalArgumentException("Can't find account $accessId")

    operator fun contains(code: AccountCode) = code in accountByCode

    fun codeOfNewAccount(id: Int, name: String, currencyCode: CurrencyCode, kind: AccountKind): AccountCode {
        val code = codeOfAccount(id, name, currencyCode, kind)
        if (code in accountByCode) throw IllegalArgumentException("Account with $code already exists")
        return code
    }

    fun codeOfAccount(id: Int, name: String, currencyCode: CurrencyCode, kind: AccountKind) =
        AccountCode("$name-$id ${currencyCode.code} ${kind.code}")
}