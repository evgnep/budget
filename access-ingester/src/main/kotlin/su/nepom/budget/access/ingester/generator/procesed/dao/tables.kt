package su.nepom.budget.access.ingester.generator.procesed.dao

import org.ktorm.schema.Table
import org.ktorm.schema.int
import org.ktorm.schema.long
import org.ktorm.schema.varchar

object Currencies: Table<Nothing>("currency") {
    val id = int("id").primaryKey()
    val code = varchar("code")
    val name = varchar("name")
}

object Accounts: Table<Nothing>("account") {
    val id = int("id").primaryKey()
    val uidMoney = varchar("uidMoney")
    val uidBudget = varchar("uidBudget")
    val codeMoney = varchar("codeMoney")
    val codeBudget = varchar("codeBudget")
    val type = int("type")
    val name = varchar("name")
    val currencyId = int("currencyId")
    val closed = int("closed")
    val order = int("ordr")
}

object Transactions: Table<Nothing>("trans") {
    val id = int("id").primaryKey()
    val uuid = varchar("uid")
    val created = long("created")
    val userId = int("userId")
    val accountId = int("accountId")
    val moneyReal = long("moneyReal")
    val accountBudgetId = int("accountBudgetId")
    val moneyBudget = long("moneyBudget")
    val description = varchar("description")
    val accountTargetId = int("accountTargetId")
    val moneyTransfer = long("moneyTransfer")
    val flag = int("flag")
    val deleted = int("deleted")
}
