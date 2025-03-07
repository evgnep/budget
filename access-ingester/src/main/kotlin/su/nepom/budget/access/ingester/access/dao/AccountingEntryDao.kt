package su.nepom.budget.access.ingester.access.dao

import su.nepom.budget.access.ingester.access.AccountAccess
import su.nepom.budget.access.ingester.access.AccountingEntryAccess
import su.nepom.budget.access.ingester.access.UserAccess
import su.nepom.budget.access.ingester.access.utils.readAll
import java.math.BigDecimal
import java.sql.ResultSet

private const val QUERY = "" +
        "SELECT ИД, Дата, Кто, Счет, СуммаРеальная, СчетБюджета, СуммаБюджета, Примечание, " +
        "   СчетЦелевой, СуммаПеревода, Согл " +
        "FROM Проводки"


class AccountingEntryDao {
    fun readAll(accounts: Map<Int, AccountAccess>, users: Map<Int, UserAccess>): List<AccountingEntryAccess> {
        val mapper = RawMapper(accounts, users)
        return readAll(QUERY, mapper)
    }

    private class RawMapper(
        private val accounts: Map<Int, AccountAccess>,
        private val users: Map<Int, UserAccess>,
    ): (ResultSet) -> AccountingEntryAccess {
        override fun invoke(rs: ResultSet): AccountingEntryAccess {
            return AccountingEntryAccess(
                id = rs.getInt("ИД"),
                date = rs.getTimestamp("Дата").toLocalDateTime(),
                user = users[rs.getInt("Кто")]!!,
                account = accounts[rs.getInt("Счет")]!!,
                moneyReal = rs.getBigDecimal("СуммаРеальная").toLongMoney(),
                accountBudget = rs.getIntOrNull("СчетБюджета")?.let { accounts[it]!! },
                moneyBudget = rs.getBigDecimalNull("СуммаБюджета")?.toLongMoney(),
                description = rs.getString("Примечание") ?: "",
                accountTarget = rs.getIntOrNull("СчетЦелевой")?.let { accounts[it]!! },
                moneyTransfer = rs.getBigDecimalNull("СуммаПеревода")?.toLongMoney(),
                flag = rs.getBoolean("Согл")
            )
        }

        private fun BigDecimal.toLongMoney(): Long = (this * BigDecimal(100)).toLong()

        private fun ResultSet.getIntOrNull(name: String): Int? {
            val v = getInt(name)
            return if (wasNull()) null else v
        }

        private fun ResultSet.getBigDecimalNull(name: String): BigDecimal? {
            val v = getBigDecimal(name)
            return if (wasNull()) null else v
        }
    }

}