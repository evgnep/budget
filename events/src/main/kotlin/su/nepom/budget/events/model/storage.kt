package su.nepom.budget.events.model

import kotlinx.serialization.Serializable
import su.nepom.budget.event.Event
import su.nepom.budget.event.StorableContent

@Serializable
internal data class FileContent(
    val events: List<Event<StorableContent>>,
)
