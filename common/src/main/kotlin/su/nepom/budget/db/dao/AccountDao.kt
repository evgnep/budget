package su.nepom.budget.db.dao

import su.nepom.budget.event.AccountContent
import su.nepom.budget.model.CurrencyId

interface AccountDao: CrudDao<AccountContent> {
    /**
     * All distinct tags used by accounts.
     */
    fun getAllTags(): Set<String>

    fun getCurrencyExchangeAccounts(currencyOne: CurrencyId, currencyTwo: CurrencyId): CurrencyExchangeAccount

    sealed interface CurrencyExchangeAccount {
        data class Success(
            val one: AccountContent,
            val two: AccountContent,
        ): CurrencyExchangeAccount

        data object NotFound: CurrencyExchangeAccount

        enum class Failure : CurrencyExchangeAccount {
            NO_PAIR, // there is no paired account
            TOO_MANY_PAIRS, // there are more than one pair of accounts for this currency
        }
    }
}
