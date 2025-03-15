package su.nepom.budget.access.ingester.access

import kotlinx.datetime.Instant

interface ObjectAccess {
    val id: Int
}

data class UserAccess(override val id: Int, val name: String): ObjectAccess

data class CurrencyAccess(override val id: Int, val name: String): ObjectAccess

enum class AccountType(val id: Int) {
    MONEY(1),
    BUDGET(2),
    MIXED(3)
}

data class AccountAccess(
    override val id: Int,
    val type: AccountType,
    val name: String,
    val currencyId: Int,
    val closed: Boolean,
    val order: Int,
): ObjectAccess

data class TransactionAccess(
    override val id: Int,
    val date: Instant,
    val userId: Int,
    val accountId: Int,
    val moneyReal: Long,
    val accountBudgetId: Int?,
    val moneyBudget: Long?,
    val description: String,
    val accountTargetId: Int?,
    val moneyTransfer: Long?,
    val flag: Boolean,
): ObjectAccess

data class DataAccess(
    val users: Map<Int, UserAccess>,
    val currencies: Map<Int, CurrencyAccess>,
    val accounts: Map<Int, AccountAccess>,
    val transactions: List<TransactionAccess>
)