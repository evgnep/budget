package su.nepom.budget.db.dao

import su.nepom.budget.event.ActualEvent
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

    fun getEventsForSourceFrom(source: Place, from: Int): List<ActualEvent>
}