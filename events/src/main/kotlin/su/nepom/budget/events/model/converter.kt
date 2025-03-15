package su.nepom.budget.events.model

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