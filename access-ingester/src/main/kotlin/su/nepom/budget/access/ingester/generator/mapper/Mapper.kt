package su.nepom.budget.access.ingester.generator.mapper

import org.ktorm.dsl.QueryRowSet
import su.nepom.budget.access.ingester.access.ObjectAccess
import su.nepom.budget.event.ActualVersionContent
import su.nepom.budget.event.Event
import su.nepom.budget.event.EventType
import su.nepom.budget.model.EventCoords
import su.nepom.budget.model.Place
import su.nepom.budget.utils.SecondsClock

internal interface Mapper {
    fun toProcessedObject(rs: QueryRowSet): ObjectProcessed

    fun onDelete(old: ObjectProcessed): EventsAndActions

    fun onNew(new: ObjectAccess): EventsAndActions

    fun onUpdate(old: ObjectProcessed, new: ObjectAccess): EventsAndActions
}

internal interface ObjectProcessed {
    val obj: ObjectAccess
}

internal data class EventsAndActions(val events: List<Event<ActualVersionContent>>, val dbActions: List<() -> Unit>) {
    constructor(event: Event<ActualVersionContent>, dbAction: () -> Unit) : this(listOf(event), listOf(dbAction))

    constructor() : this(listOf(), listOf())
}

internal fun createEventForMapper(
    content: ActualVersionContent,
    type: EventType,
    creator: String = "unknown",
    importedId: String? = null
) = Event(
    EventCoords(Place.NULL, 0),
    SecondsClock.now(),
    creator,
    type,
    listOf(),
    content,
    importedId = importedId
)