package su.nepom.budget.desktop.util

sealed interface CheckResult {
    val isOk get() = this is CheckOk

    val isWarning get() = this is CheckWarning

    val isError get() = this is CheckError

    val isNotError get() = !isError
}

data object CheckOk : CheckResult

data class CheckWarning(val message: String) : CheckResult

data class CheckError(val message: String) : CheckResult

interface Checkable<T> {
    fun check(value: T?): CheckResult
}