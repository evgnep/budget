package su.nepom.budget.events.synchronizer.impl

import su.nepom.budget.Global
import su.nepom.budget.db.Session
import su.nepom.budget.event.ActualEvent
import su.nepom.budget.event.ActualVersionContent
import su.nepom.budget.event.Event
import su.nepom.budget.event.EventType
import su.nepom.budget.events.synchronizer.ConflictResolver
import su.nepom.budget.events.synchronizer.EventSynchronizeResult
import su.nepom.budget.events.synchronizer.storage.EventStorage
import su.nepom.budget.model.EventCoords
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.Uuid
import su.nepom.budget.model.isAncestorOf
import su.nepom.budget.utils.SecondsClock
import su.nepom.budget.utils.ioOp

internal class ObjectsKindSynchronizer(
    private val objectKind: ObjectKind,
    private val storage: EventStorage,
    session: Session,
    private val conflictResolver: ConflictResolver,
    private val result: EventSynchronizeResult,
) {
    private val objectDao = session.dao(objectKind)

    private val eventDao = session.eventDao

    suspend fun synchronize() {
        storage.eventsForObjectByKind(objectKind).collect { (uuid, events) ->
            ObjectProcessor(uuid, events).synchronize()
        }
    }

    private inner class ObjectProcessor(private val uuid: Uuid, private val events: List<ActualEvent>) {
        suspend fun synchronize() {
            require(events.isNotEmpty()) { "Events must not be empty" }
            val ourDbLastEvent = ioOp { eventDao.getLastEventForObject(uuid, objectKind) }
            val heads = findHeads(if (ourDbLastEvent == null) events else (events + ourDbLastEvent))
            val mergedHead = heads.singleOrNull() ?: resolveConflict(heads)
            ioOp {
                events.forEach { eventDao.save(it) }
                if (heads.size > 1) eventDao.save(mergedHead)
                objectDao.save(mergedHead.content)
            }
            result.imported += events.size
        }

        private suspend fun resolveConflict(heads: List<ActualEvent>): ActualEvent {
            val result = conflictResolver.resolve(objectKind, heads)
            return when (result.action) {
                ConflictResolver.Action.RESOLVE -> {
                    requireNotNull(result.content) { "Content must not be null" }
                    createResolveConflictEvent(heads, result.content)
                }
                ConflictResolver.Action.CANCEL -> throw AbortException()
            }
        }

        private fun createResolveConflictEvent(
            heads: List<ActualEvent>,
            newContent: ActualVersionContent
        ): ActualEvent {
            val basedOn = heads.mapNotNull { if (it.coords.source == Global.currentPlace) null else it.coords }
            val conflictResolve = heads.map { it.coords }
            return Event(
                EventCoords(Global.currentPlace, 0),
                SecondsClock.now(),
                Global.currentUser,
                EventType.UPDATE,
                basedOn,
                newContent,
                conflictResolve = conflictResolve
            )
        }

        private fun findHeads(events: List<ActualEvent>): List<ActualEvent> {
            if (events.size == 1) return events
            val potentialHeads = events.groupingBy { it.coords.source }.reduce { _, acc, event ->
                if (acc.coords.no < event.coords.no) event else acc
            }.values
            if (potentialHeads.size == 1) return listOf(potentialHeads.single())
            val heads = mutableListOf<ActualEvent>()
            potentialHeads.forEach { candidate ->
                val isAncestor = potentialHeads.any { event ->
                    event != candidate && event.basedOn.any { candidate.coords isAncestorOf it }
                }
                if (!isAncestor) heads.add(candidate)
            }
            require(heads.isNotEmpty()) { "No heads found" }
            return heads
        }
    }
}