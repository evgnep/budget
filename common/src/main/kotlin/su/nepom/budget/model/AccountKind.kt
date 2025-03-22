package su.nepom.budget.model

enum class AccountKind(val code: String) {
    MONEY("M"),
    BUDGET("B"),
    ;

    fun invert(): AccountKind = when (this) {
        MONEY -> BUDGET
        BUDGET -> MONEY
    }

    companion object {
        fun byCode(code: String): AccountKind = entries.find { it.code == code }
            ?: throw IllegalArgumentException("No account kind with code $code")
    }
}