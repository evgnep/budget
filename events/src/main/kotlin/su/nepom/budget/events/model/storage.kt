package su.nepom.budget.events.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import su.nepom.budget.event.Event
import su.nepom.budget.event.StorableContent
import su.nepom.budget.model.EventCoords
import su.nepom.budget.model.Id
import su.nepom.budget.model.ObjectKind

@Serializable
data class FileContent(
    val events: List<Event<StorableContent>>,
)
