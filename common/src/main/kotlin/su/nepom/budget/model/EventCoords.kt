package su.nepom.budget.model

import kotlinx.serialization.Serializable

@Serializable
data class EventCoords(
    val source: Place,
    val no: Int,
) {
    override fun toString() = "${source.code}-$no"
}

infix fun Place.no(no: Int) = EventCoords(this, no)

infix fun EventCoords.isAncestorOf(other: EventCoords): Boolean {
    return source == other.source && no <= other.no
}

infix fun EventCoords.isDescendant(other: EventCoords): Boolean {
    return source == other.source && no > other.no
}