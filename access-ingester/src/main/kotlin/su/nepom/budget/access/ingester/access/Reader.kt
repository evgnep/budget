package su.nepom.budget.access.ingester.access

import su.nepom.budget.access.ingester.access.dao.AccountsReader
import su.nepom.budget.access.ingester.access.dao.CurrenciesReader
import su.nepom.budget.access.ingester.access.dao.TransactionDao
import su.nepom.budget.access.ingester.access.dao.UsersReader

class Reader(
    private val usersReader: UsersReader,
    private val currenciesReader: CurrenciesReader,
    private val accountsReader: AccountsReader,
    private val transactionDao: TransactionDao,
) {
    private lateinit var users: Map<Int, UserAccess>

    private lateinit var currencies: Map<Int, CurrencyAccess>

    private lateinit var accounts: Map<Int, AccountAccess>

    fun prepare() {
        users = usersReader.read().associateBy { it.id }
        currencies = currenciesReader.read().associateBy { it.id }
        accounts = accountsReader.readAccounts().associateBy { it.id }
    }

    fun readAllAccountingEntries() = DataAccess(
        users,
        currencies,
        accounts,
        transactionDao.readAll()
    )
}