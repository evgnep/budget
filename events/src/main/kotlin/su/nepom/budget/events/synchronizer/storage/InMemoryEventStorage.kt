package su.nepom.budget.events.synchronizer.storage

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import su.nepom.budget.event.ActualEvent
import su.nepom.budget.events.EventStoreReader
import su.nepom.budget.events.EventsSequence.ReadingError
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.Place
import su.nepom.budget.model.Uuid
import su.nepom.budget.utils.ioOp

internal class InMemoryEventStorage: EventStorage {
    private val eventsByKindAndUuid =
        mutableMapOf<ObjectKind,
                MutableMap<Uuid,
                        MutableList<ActualEvent>>>()

    override suspend fun loadEvents(reader: EventStoreReader, from: Map<Place, Int>): List<ReadingError> = ioOp {
        val sequence = reader.createEventsSequence(from)
        sequence.forEach { event ->
            eventsByKindAndUuid
                .computeIfAbsent(event.content.objectKind) { mutableMapOf() }
                .computeIfAbsent(event.content.id.uuid) { mutableListOf() }
                .add(event)
        }
        sequence.getReadingErrorsByPlace().values.toList()
    }

    override fun eventsForObjectByKind(kind: ObjectKind): Flow<EventStorage.EventsForObject> {
        val eventsForObjects = eventsByKindAndUuid[kind] ?: return emptyFlow()
        return eventsForObjects.entries.asSequence().map { EventStorage.EventsForObject(it.key, it.value) }.asFlow()
    }
}