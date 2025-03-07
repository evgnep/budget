package su.nepom.budget.access.ingester.access.dao

import su.nepom.budget.access.ingester.access.AccountAccess
import su.nepom.budget.access.ingester.access.AccountType
import su.nepom.budget.access.ingester.access.CurrencyAccess
import su.nepom.budget.access.ingester.access.utils.readAll

class AccountsReader {
    fun readAccounts(currencies: Map<Int, CurrencyAccess>): List<AccountAccess> =
        readAll("SELECT ИД, Счет, Валюта, Закрыт, Тип, Порядок FROM Счета") {
            val typeId = getInt("Тип")
            AccountAccess(
                getInt("ИД"),
                AccountType.entries.first { it.id == typeId },
                getString("Счет"),
                currencies[getInt("Валюта")]!!,
                getBoolean("Закрыт"),
                getInt("Порядок")
            )
        }
}