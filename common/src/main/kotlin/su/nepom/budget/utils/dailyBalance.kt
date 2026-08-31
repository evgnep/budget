package su.nepom.budget.utils

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import su.nepom.budget.event.AccountBudget
import su.nepom.budget.event.DailyAllowance
import su.nepom.budget.model.RawMoney

/**
 * A transaction item amount that is put aside until [until] (exclusive). See docs/budget.md.
 */
data class ReservedAmount(val amount: RawMoney, val until: LocalDate)

/**
 * "Остаток на день" - how much of [rest] can be spent today.
 * Returns null when the account has no replenish day set.
 */
fun calculateDailyBalance(
    rest: RawMoney,
    today: LocalDate,
    budget: AccountBudget,
    reservedItems: List<ReservedAmount> = emptyList(),
): RawMoney? {
    val replenishDay = budget.replenishDay ?: return null
    val nextReset = nextResetDate(today, replenishDay)

    var blocked = 0L

    var day = today.plus(1, DateTimeUnit.DAY)
    while (day < nextReset) {
        blocked += dailyAllowanceFor(day, budget.dailyAllowances)?.value ?: 0L
        day = day.plus(1, DateTimeUnit.DAY)
    }

    for (reserve in budget.reserves) {
        val active = (reserve.from == null || today >= reserve.from) &&
            (reserve.to == null || today <= reserve.to)
        if (active) blocked += reserve.amount.value
    }

    for (item in reservedItems) {
        if (today < item.until) blocked += item.amount.value
    }

    return RawMoney(rest.value - blocked)
}

/**
 * The next moment when [replenishDay] starts (00:00), strictly in the future.
 * [replenishDay] is expected to be in 1..28.
 */
fun nextResetDate(today: LocalDate, replenishDay: Int): LocalDate {
    val thisMonth = LocalDate(today.year, today.monthNumber, replenishDay)
    return if (today.dayOfMonth < replenishDay) thisMonth
    else thisMonth.plus(1, DateTimeUnit.MONTH)
}

/**
 * The spending norm ("остаток на день" target) for [day], or null when none is set.
 */
fun dailyAllowanceOn(day: LocalDate, budget: AccountBudget): RawMoney? =
    dailyAllowanceFor(day, budget.dailyAllowances)

private fun dailyAllowanceFor(day: LocalDate, allowances: List<DailyAllowance>): RawMoney? {
    val matching = allowances.filter {
        (it.from == null || day >= it.from) && (it.to == null || day <= it.to)
    }
    if (matching.isEmpty()) return null
    val bounded = matching.filter { it.from != null || it.to != null }
    val pick = if (bounded.isNotEmpty()) bounded.minWith(compareBy({ it.from }, { it.to }))
    else matching.first()
    return pick.amount
}

fun Instant.toLocalDate(timeZone: TimeZone = TimeZone.currentSystemDefault()): LocalDate =
    toLocalDateTime(timeZone).date
