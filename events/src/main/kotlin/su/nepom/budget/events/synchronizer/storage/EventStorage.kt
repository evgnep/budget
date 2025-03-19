package su.nepom.budget.events.synchronizer.storage

import kotlinx.coroutines.flow.Flow
import su.nepom.budget.event.ActualEvent
import su.nepom.budget.events.EventStoreReader
import su.nepom.budget.events.EventsSequence.ReadingError
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.Place
import su.nepom.budget.model.Uuid

internal interface EventStorage {
    suspend fun loadEvents(reader: EventStoreReader, from: Map<Place, Int>): List<ReadingError>

    fun eventsForObjectByKind(kind: ObjectKind): Flow<EventsForObject>

    data class EventsForObject(val uuid: Uuid, val events: List<ActualEvent>)
}