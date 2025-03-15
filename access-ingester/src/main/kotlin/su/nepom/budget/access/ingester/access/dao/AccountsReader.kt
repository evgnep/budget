package su.nepom.budget.access.ingester.access.dao

import su.nepom.budget.access.ingester.access.AccountAccess
import su.nepom.budget.access.ingester.access.AccountType
import su.nepom.budget.access.ingester.access.utils.readAll

class AccountsReader {
    fun readAccounts(): List<AccountAccess> =
        readAll("SELECT ИД, Счет, Валюта, Закрыт, Тип, Порядок FROM Счета ORDER BY ИД") {
            val typeId = getInt("Тип")
            AccountAccess(
                getInt("ИД"),
                AccountType.entries.first { it.id == typeId },
                getString("Счет"),
                getInt("Валюта"),
                getBoolean("Закрыт"),
                getInt("Порядок")
            )
        }
}