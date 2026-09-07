package su.nepom.budget.db.sqlite

import org.ktorm.dsl.and
import org.ktorm.dsl.eq
import org.ktorm.entity.filter
import org.ktorm.entity.map
import su.nepom.budget.db.dao.AccountDao
import su.nepom.budget.db.dao.AccountDao.CurrencyExchangeAccount
import su.nepom.budget.db.sqlite.impl.AbstractSqliteCrudDao
import su.nepom.budget.db.sqlite.mapping.AccountEntity
import su.nepom.budget.db.sqlite.mapping.Accounts
import su.nepom.budget.db.sqlite.mapping.accounts
import su.nepom.budget.db.sqlite.mapping.tagsFromDb
import su.nepom.budget.db.sqlite.mapping.toEntity
import su.nepom.budget.event.AccountContent
import su.nepom.budget.model.AccountKind
import su.nepom.budget.model.CurrencyId
import su.nepom.budget.model.Uuid
import su.nepom.budget.model.uuidCode

internal class SqliteAccountDao(
    session: SqliteSession
) : AbstractSqliteCrudDao<AccountContent, AccountEntity, Accounts>(
    session,
    { accounts },
    { toAccountContent() },
    { toEntity() }),
    AccountDao {

    override fun idPredicate(table: Accounts, id: Uuid) = table.uuid eq id.id

    override fun getAllTags(): Set<String> = session.doReadOp {
        sequence.map { it.tags }.flatMapTo(sortedSetOf()) { it.tagsFromDb() }
    }

    override fun getCurrencyExchangeAccounts(
        currencyOne: CurrencyId,
        currencyTwo: CurrencyId,
    ): CurrencyExchangeAccount = session.doReadOp {
        val ones = sequence
            .filter { (it.currencyUuid eq currencyOne.uuidCode()) and (it.pairCurrency eq currencyTwo.uuidCode()) }
            .map { it.toAccountContent() }
        val twos = sequence
            .filter { (it.currencyUuid eq currencyTwo.uuidCode()) and (it.pairCurrency eq currencyOne.uuidCode()) }
            .map { it.toAccountContent() }
        when {
            ones.isEmpty() && twos.isEmpty() -> CurrencyExchangeAccount.NotFound
            ones.size == 1 && twos.size == 1 -> CurrencyExchangeAccount.Success(ones.single(), twos.single())
            ones.size > 1 || twos.size > 1 -> CurrencyExchangeAccount.Failure.TOO_MANY_PAIRS
            else -> CurrencyExchangeAccount.Failure.NO_PAIR
        }
    }

    override fun validateNew(new: AccountContent) {
        validatePairCurrency(new)
    }

    override fun validateUpdate(old: AccountContent, new: AccountContent) {
        require(new.currency.uuid == old.currency.uuid) { "You can't change currency" }
        require(new.kind == old.kind) { "You can't change kind" }
        validatePairCurrency(new)
    }

    private fun validatePairCurrency(content: AccountContent) {
        val pairCurrency = content.pairCurrency ?: return
        require(content.kind == AccountKind.BUDGET) { "pairCurrency is only allowed for BUDGET accounts" }
        require(pairCurrency.uuid != content.currency.uuid) { "pairCurrency can't be the same as account currency" }
    }
}