package su.nepom.budget.access.ingester.access

import su.nepom.budget.access.ingester.access.dao.AccountingEntryDao
import su.nepom.budget.access.ingester.access.dao.AccountsReader
import su.nepom.budget.access.ingester.access.dao.CurrenciesReader
import su.nepom.budget.access.ingester.access.dao.UsersReader

class Reader(
    private val usersReader: UsersReader,
    private val currenciesReader: CurrenciesReader,
    private val accountsReader: AccountsReader,
    private val accountingEntryDao: AccountingEntryDao,
) {
    private lateinit var users: Map<Int, UserAccess>

    private lateinit var currencies: Map<Int, CurrencyAccess>

    lateinit var accounts: Map<Int, AccountAccess> private set

    fun prepare() {
        users = usersReader.read().associateBy { it.id }
        currencies = currenciesReader.read().associateBy { it.id }
        accounts = accountsReader.readAccounts(currencies).associateBy { it.id }
    }

    fun readAllAccountingEntries(): List<AccountingEntryAccess> = accountingEntryDao.readAll(accounts, users)

}