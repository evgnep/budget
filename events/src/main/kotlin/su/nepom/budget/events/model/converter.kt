package su.nepom.budget.events.model

import su.nepom.budget.event.ActualEvent
import su.nepom.budget.event.ActualVersionContent
import su.nepom.budget.event.Event
import su.nepom.budget.event.StorableContent

fun StorableContent.toActualVersion(): ActualVersionContent {
    if (this is ActualVersionContent) return this
    return objectKind.toActualVersionConverter(this)
}

fun Event<StorableContent>.toActualVersion(): ActualEvent {
    val actualVersionContent = content.toActualVersion()
    return (if (actualVersionContent === content) this else copy(content = actualVersionContent))
            as ActualEvent
}