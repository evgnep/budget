package su.nepom.budget.model

// "Пометка остатка" - highlight an account rest when it matches this condition
enum class RestMark {
    OFF,
    IF_ZERO,
    IF_NOT_ZERO,
    IF_NEGATIVE,
    IF_POSITIVE,
    ;

    fun matches(rest: Long): Boolean = when (this) {
        OFF -> false
        IF_ZERO -> rest == 0L
        IF_NOT_ZERO -> rest != 0L
        IF_NEGATIVE -> rest < 0L
        IF_POSITIVE -> rest > 0L
    }
}
