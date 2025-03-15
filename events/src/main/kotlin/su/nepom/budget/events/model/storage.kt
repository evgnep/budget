package su.nepom.budget.events.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import su.nepom.budget.model.EventCoords
import su.nepom.budget.model.ObjectKind

@Serializable
data class FileContent(
    val events: List<Event<StorableContent>>,
)

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

/**
 * This interface represents content that can be stored.
 */
@Serializable
sealed interface StorableContent {
    val objectKind: ObjectKind
}

/**
 * Represents the content of the actual version.
 */
@Serializable
sealed interface ActualVersionContent : StorableContent