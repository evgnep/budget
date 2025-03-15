package su.nepom.budget.access.ingester.access.dao

import kotlinx.datetime.toKotlinInstant
import su.nepom.budget.access.ingester.access.TransactionAccess
import su.nepom.budget.access.ingester.access.utils.readAll
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.temporal.ChronoUnit

private const val QUERY = "" +
        "SELECT ИД, Дата, Кто, Счет, СуммаРеальная, СчетБюджета, СуммаБюджета, Примечание, " +
        "   СчетЦелевой, СуммаПеревода, Согл " +
        "FROM Проводки "


class TransactionDao {
    fun readAll(): List<TransactionAccess> {
        val mapper = RawMapper()
        return readAll(QUERY + "ORDER BY ИД", mapper)
    }

    private class RawMapper: (ResultSet) -> TransactionAccess {
        override fun invoke(rs: ResultSet): TransactionAccess {
            return TransactionAccess(
                id = rs.getInt("ИД"),
                date = rs.getTimestamp("Дата").toInstant().truncatedTo(ChronoUnit.SECONDS).toKotlinInstant(),
                userId = rs.getInt("Кто"),
                accountId = rs.getInt("Счет"),
                moneyReal = rs.getBigDecimal("СуммаРеальная").toLongMoney(),
                accountBudgetId = rs.getIntOrNull("СчетБюджета"),
                moneyBudget = rs.getBigDecimalNull("СуммаБюджета")?.toLongMoney(),
                description = rs.getString("Примечание") ?: "",
                accountTargetId = rs.getIntOrNull("СчетЦелевой"),
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