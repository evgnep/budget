package su.nepom.budget.events.model

import kotlinx.serialization.Serializable
import su.nepom.budget.event.StorableEvent

@Serializable
internal data class FileContent(
    val events: List<StorableEvent>,
)
