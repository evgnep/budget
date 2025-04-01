package su.nepom.budget.db.sqlite.impl

import su.nepom.budget.db.Db
import su.nepom.budget.event.Event
import su.nepom.budget.model.Uuid

class EventsNotifier(
    private val listenersStorage: ListenersStorage
) {
    private val eventsInTransaction = mutableMapOf<Db.SubscribeKind, MutableMap<Uuid, Event<*>>>()
    
    fun addEvent(kind: Db.SubscribeKind, event: Event<*>) {
        eventsInTransaction.computeIfAbsent(kind) { mutableMapOf() }[event.content.uuid] = event
    }
    
    fun onTransactionStart() {
        eventsInTransaction.clear()
    }

    fun onTransactionCommit() {
        val eventsByKind = mutableMapOf<Db.SubscribeKind, Collection<Event<*>>>()
        val eventsByKinds = mutableMapOf<Set<Db.SubscribeKind>, Collection<Event<*>>>()
        listenersStorage.listeners.forEach { (kinds, listener) -> 
            val events = eventsByKinds.computeIfAbsent(kinds) {
                kinds.map { kind ->
                    eventsByKind.computeIfAbsent(kind) { eventsInTransaction[kind]?.values ?: emptyList() }
                }.smartFlatten()
            }
            listener(events)
        }
    }
    
    private fun <T> List<Collection<T>>.smartFlatten(): Collection<T> = 
        when(size) {
            0 -> emptyList()
            1 -> first()
            else -> flatten()
        }

    fun onTransactionRollback() {
        eventsInTransaction.clear()
    }
}