package su.nepom.budget.desktop.util

import su.nepom.budget.desktop.model.CurrencyObservable
import su.nepom.budget.model.RawMoney
import java.math.BigDecimal
import java.math.RoundingMode

fun RawMoney.toBigDecimal(currency: CurrencyObservable?): BigDecimal? =
    if (currency == null) null
    else BigDecimal.valueOf(value, currency.content.digitsAfterPoint)

fun RawMoney.toBigDecimal(digitsAfterPoint: Int): BigDecimal =
    BigDecimal.valueOf(value, digitsAfterPoint)

fun RawMoney.format(digitsAfterPoint: Int): String =
    toBigDecimal(digitsAfterPoint).toPlainString()

fun BigDecimal.toRawMoney(digitsAfterPoint: Int): RawMoney =
    RawMoney(movePointRight(digitsAfterPoint).setScale(0, RoundingMode.HALF_UP).longValueExact())

fun String.toRawMoneyOrNull(digitsAfterPoint: Int): RawMoney? =
    trim().replace(" ", "").replace(',', '.').toBigDecimalOrNull()?.toRawMoney(digitsAfterPoint)
