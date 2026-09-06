package su.nepom.budget.db.dao

import kotlinx.datetime.Instant
import su.nepom.budget.event.ActualEvent
import su.nepom.budget.event.EventType
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.Place
import su.nepom.budget.model.Uuid

interface EventDao {
    /**
     * if current place event then should set coords.no automatically
     */
    fun save(event: ActualEvent)

    fun getLastEventCoords(): Map<Place, Int>

    fun getLastEventForObject(uuid: Uuid, kind: ObjectKind): ActualEvent?

    fun getEventsForObject(uuid: Uuid, kind: ObjectKind): List<ActualEvent>

    fun getEventsForSourceFrom(source: Place, from: Int): List<ActualEvent>

    data class Filter(
        val place: Place? = null,
        val no: ClosedRange<Int>? = null,
        val created: ClosedRange<Instant>? = null,
        val creator: String? = null,
        val type: EventType? = null,
        val contentPart: String? = null,
        val objectKind: ObjectKind? = null,
        val objectUuid: Uuid? = null,
    )

    data class Query(
        val filter: Filter = Filter(),
        val offset: Int = 0,
        val limit: Int = 100,
        val sortByDateAsc: Boolean = false,
    )

    fun getByQuery(query: Query): List<ActualEvent>

    fun countByFilter(filter: Filter): Int
}