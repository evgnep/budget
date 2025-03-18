package su.nepom.budget.event

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import su.nepom.budget.model.EventCoords

@Serializable
data class Event<T: StorableContent>(
    val coords: EventCoords,
    val created: Instant,
    val creator: String,
    val type: EventType,
    val basedOn: List<EventCoords>, // without this.coords.source events
    val content: T,
    val conflictResolve: ConflictResolve? = null,
    val importedId: String? = null,
) {
    @Serializable
    data class ConflictResolve(
        val left: EventCoords,
        val right: EventCoords,
    )
}

enum class EventType {
    NEW,
    UPDATE,
}
