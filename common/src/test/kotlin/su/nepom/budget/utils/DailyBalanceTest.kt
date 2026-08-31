package su.nepom.budget.utils

import kotlinx.datetime.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import su.nepom.budget.event.AccountBudget
import su.nepom.budget.event.DailyAllowance
import su.nepom.budget.event.Reserve
import su.nepom.budget.model.RawMoney

class DailyBalanceTest {

    private fun date(s: String) = LocalDate.parse(s)

    @Test
    fun `no replenish day means no daily balance`() {
        val result = calculateDailyBalance(RawMoney(100), date("2026-09-01"), AccountBudget.EMPTY)
        assertThat(result).isNull()
    }

    @Test
    fun `example 1 - single unbounded allowance`() {
        val budget = AccountBudget(
            replenishDay = 10,
            dailyAllowances = listOf(DailyAllowance(RawMoney(6))),
        )
        val result = calculateDailyBalance(RawMoney(100), date("2026-09-01"), budget)
        // days 2..9 -> 8 days * 6 = 48
        assertThat(result).isEqualTo(RawMoney(52))
    }

    @Test
    fun `example 2 - bounded allowance overrides the unbounded one`() {
        val budget = AccountBudget(
            replenishDay = 10,
            dailyAllowances = listOf(
                DailyAllowance(RawMoney(7), to = date("2026-09-05")),
                DailyAllowance(RawMoney(6)),
            ),
        )
        val result = calculateDailyBalance(RawMoney(100), date("2026-09-01"), budget)
        // days 2..5 * 7 = 28, days 6..9 * 6 = 24
        assertThat(result).isEqualTo(RawMoney(48))
    }

    @Test
    fun `example 3 - only active reserves count`() {
        val budget = AccountBudget(
            replenishDay = 10,
            dailyAllowances = listOf(
                DailyAllowance(RawMoney(7), to = date("2026-09-05")),
                DailyAllowance(RawMoney(6)),
            ),
            reserves = listOf(
                Reserve(RawMoney(100), from = date("2026-08-01"), to = date("2026-08-31")),
                Reserve(RawMoney(50), from = date("2026-08-30"), to = date("2026-09-05")),
            ),
        )
        val result = calculateDailyBalance(RawMoney(100), date("2026-09-01"), budget)
        // 48 - 50 (first reserve expired) = -2
        assertThat(result).isEqualTo(RawMoney(-2))
    }

    @Test
    fun `reserved item is excluded while its date is in the future`() {
        val budget = AccountBudget(replenishDay = 10)
        val reserved = listOf(ReservedAmount(RawMoney(30), until = date("2026-09-10")))
        val result = calculateDailyBalance(RawMoney(100), date("2026-09-01"), budget, reserved)
        assertThat(result).isEqualTo(RawMoney(70))
    }

    @Test
    fun `reserved item stops counting on its date`() {
        val budget = AccountBudget(replenishDay = 10)
        val reserved = listOf(ReservedAmount(RawMoney(30), until = date("2026-09-01")))
        val result = calculateDailyBalance(RawMoney(100), date("2026-09-01"), budget, reserved)
        assertThat(result).isEqualTo(RawMoney(100))
    }

    @Test
    fun `on the replenish day the range covers almost a whole month`() {
        val budget = AccountBudget(
            replenishDay = 10,
            dailyAllowances = listOf(DailyAllowance(RawMoney(1))),
        )
        // today == replenishDay -> next reset is 2026-10-10, days 2026-09-11..2026-10-09 = 29 days
        val result = calculateDailyBalance(RawMoney(100), date("2026-09-10"), budget)
        assertThat(result).isEqualTo(RawMoney(100 - 29))
    }

    @Test
    fun `next reset date rolls to next month when day already passed`() {
        assertThat(nextResetDate(date("2026-09-15"), 10)).isEqualTo(date("2026-10-10"))
        assertThat(nextResetDate(date("2026-09-05"), 10)).isEqualTo(date("2026-09-10"))
        assertThat(nextResetDate(date("2026-12-15"), 1)).isEqualTo(date("2027-01-01"))
    }
}
