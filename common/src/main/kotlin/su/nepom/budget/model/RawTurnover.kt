package su.nepom.budget.model

data class RawTurnover(val income: RawMoney, val expenditure: RawMoney) {
    companion object {
        fun fromLong(income: Long, expenditure: Long) = RawTurnover(RawMoney(income), RawMoney(expenditure))

        fun fromInt(income: Int, expenditure: Int) = RawTurnover(RawMoney(income), RawMoney(expenditure))
    }
}