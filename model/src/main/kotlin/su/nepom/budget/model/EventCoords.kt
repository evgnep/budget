package su.nepom.budget.model

import kotlinx.serialization.Serializable

@Serializable
data class EventCoords(
    val source: String,
    val no: Int,
)