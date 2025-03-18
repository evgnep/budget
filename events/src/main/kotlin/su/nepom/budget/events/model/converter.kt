package su.nepom.budget.events.model

import su.nepom.budget.event.AccountContentV1
import su.nepom.budget.event.ActualVersionContent
import su.nepom.budget.event.CurrencyContentV1
import su.nepom.budget.event.Event
import su.nepom.budget.event.StorableContent
import su.nepom.budget.event.TransactionContentV1

fun StorableContent.toActualVersion(): ActualVersionContent = when(this) {
    is AccountContentV1 -> this
    is CurrencyContentV1 -> this
    is TransactionContentV1 -> this
}

fun Event<StorableContent>.toActualVersion(): Event<ActualVersionContent> {
    val actualVersionContent = content.toActualVersion()
    return (if (actualVersionContent === content) this else copy(content = actualVersionContent))
            as Event<ActualVersionContent>
}