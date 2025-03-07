package su.nepom.budget.access.ingester.access.dao

import su.nepom.budget.access.ingester.access.CurrencyAccess
import su.nepom.budget.access.ingester.access.utils.readAll

class CurrenciesReader {
    fun read(): List<CurrencyAccess> = readAll("SELECT ИД, Название FROM Валюта") {
        CurrencyAccess(getInt("ИД"), getString("Название"))
    }
}