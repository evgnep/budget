package su.nepom.budget.utils

import su.nepom.budget.model.RawMoney
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

fun RawMoney.toBigDecimal(digitsAfterPoint: Int): BigDecimal =
    BigDecimal.valueOf(value, digitsAfterPoint)

fun RawMoney.format(digitsAfterPoint: Int): String =
    moneyFormat(digitsAfterPoint).format(toBigDecimal(digitsAfterPoint))

private fun moneyFormat(digitsAfterPoint: Int): DecimalFormat {
    val symbols = DecimalFormatSymbols(Locale.ROOT).apply {
        groupingSeparator = ' '
        decimalSeparator = '.'
    }
    return DecimalFormat().apply {
        decimalFormatSymbols = symbols
        isGroupingUsed = true
        groupingSize = 3
        minimumFractionDigits = digitsAfterPoint
        maximumFractionDigits = digitsAfterPoint
        roundingMode = RoundingMode.UNNECESSARY
    }
}

fun BigDecimal.toRawMoney(digitsAfterPoint: Int): RawMoney =
    RawMoney(movePointRight(digitsAfterPoint).setScale(0, RoundingMode.HALF_UP).longValueExact())

fun String.toRawMoneyOrNull(digitsAfterPoint: Int): RawMoney? =
    evalMoneyFormula(this)?.toRawMoney(digitsAfterPoint)
