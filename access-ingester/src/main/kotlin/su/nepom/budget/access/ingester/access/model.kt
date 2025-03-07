package su.nepom.budget.access.ingester.access

import java.time.LocalDateTime

data class UserAccess(val id: Int, val name: String)

data class CurrencyAccess(val id: Int, val name: String)

enum class AccountType(val id: Int) {
    MONEY(1),
    BUDGET(2),
    MIXED(3)
}

data class AccountAccess(
    val id: Int,
    val type: AccountType,
    val name: String,
    val currency: CurrencyAccess,
    val closed: Boolean,
    val order: Int,
)

data class AccountingEntryAccess(
    val id: Int,
    val date: LocalDateTime,
    val user: UserAccess,
    val account: AccountAccess,
    val moneyReal: Long,
    val accountBudget: AccountAccess?,
    val moneyBudget: Long?,
    val description: String,
    val accountTarget: AccountAccess?,
    val moneyTransfer: Long?,
    val flag: Boolean,
)