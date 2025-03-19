package su.nepom.budget.event

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import su.nepom.budget.model.EventCoords
import su.nepom.budget.model.Uuid

@Serializable
data class Event<out T: StorableContent>(
    val coords: EventCoords,
    val created: Instant,
    val creator: String,
    val type: EventType,
    val basedOn: List<EventCoords>, // without this.coords.source events
    val content: T,
    val conflictResolve: List<EventCoords>? = null,
    val importedId: String? = null,
) {
    val uuid: Uuid get() = content.id.uuid
}

enum class EventType {
    NEW,
    UPDATE,
}

typealias ActualEvent = Event<ActualVersionContent>