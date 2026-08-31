package su.nepom.budget.desktop.util

import su.nepom.budget.desktop.model.CurrencyObservable
import su.nepom.budget.model.RawMoney
import java.math.BigDecimal

fun RawMoney.toBigDecimal(currency: CurrencyObservable?): BigDecimal? =
    if (currency == null) null
    else BigDecimal.valueOf(value, currency.content.digitsAfterPoint)
