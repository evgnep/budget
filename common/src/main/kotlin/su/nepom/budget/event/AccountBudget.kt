package su.nepom.budget.event

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import su.nepom.budget.model.RawMoney

/**
 * Budget settings of an account. See docs/budget.md.
 */
@Serializable
data class AccountBudget(
    val replenishDay: Int? = null,
    val dailyAllowances: List<DailyAllowance> = emptyList(),
    val reserves: List<Reserve> = emptyList(),
) {
    companion object {
        val EMPTY = AccountBudget()
    }
}

/**
 * How much money must be kept for every future day. Period bounds are inclusive,
 * null bound means "open".
 */
@Serializable
data class DailyAllowance(
    val amount: RawMoney,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
)

/**
 * A sum put aside for a period. Period bounds are inclusive, null bound means "open".
 */
@Serializable
data class Reserve(
    val amount: RawMoney,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
)
