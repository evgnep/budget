package su.nepom.budget.model

import kotlinx.serialization.Serializable

@Serializable
data class EventCoords(
    val source: Place,
    val no: Int,
) {
    override fun toString() = "${source.code}-$no"
}