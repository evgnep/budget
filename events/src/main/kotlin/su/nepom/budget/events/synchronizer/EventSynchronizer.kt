package su.nepom.budget.events.synchronizer

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import su.nepom.budget.Global
import su.nepom.budget.db.Db
import su.nepom.budget.db.Session
import su.nepom.budget.events.EventStoreReader
import su.nepom.budget.events.EventsSequence
import su.nepom.budget.events.synchronizer.impl.AbortException
import su.nepom.budget.events.synchronizer.impl.ObjectsKindSynchronizer
import su.nepom.budget.events.synchronizer.storage.EventStorage
import su.nepom.budget.events.synchronizer.storage.InMemoryEventStorage
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.Place
import su.nepom.budget.utils.ioOp

private val logger = KotlinLogging.logger { }

private val OBJECT_KINDS_IN_PROCESSED_ORDER = listOf(ObjectKind.CURRENCY, ObjectKind.ACCOUNT, ObjectKind.TRANSACTION)

class EventSynchronizer(
    private val reader: EventStoreReader,
    private val db: Db,
    private val conflictResolver: ConflictResolver,
) {
    fun synchronize(): EventSynchronizeResult {
        val result = EventSynchronizeResult()

        db.createSessionInBlockingMode(createEvents = false).use { session ->
            runBlocking {
                try {
                    doIt(result, session)
                    ioOp { session.commit() }
                    logger.info { "Synchronized ${result.imported} events" }
                } catch (e: AbortException) {
                    ioOp { session.rollback() }
                    logger.info { "Cancelled by user" }
                    result.imported = 0
                } catch (e: Exception) {
                    ioOp { session.rollback() }
                    logger.error(e) { "Error while synchronizing events" }
                    result.error = e.message
                }
            }
        }
        return result
    }

    private suspend fun doIt(result: EventSynchronizeResult, session: Session) {
        val from = ioOp { session.eventDao.getLastEventCoords() }
        val storage = createEventStorage(from) ?: return
        result.readingErrors.addAll(storage.loadEvents(reader, from))
        OBJECT_KINDS_IN_PROCESSED_ORDER.forEach { kind ->
            ObjectsKindSynchronizer(kind, storage, session, conflictResolver, result).synchronize()
        }
    }

    private suspend fun createEventStorage(from: Map<Place, Int>): EventStorage? {
        val newEvents = ioOp { reader.getMaxEventsNo() }.filterKeys { it != Global.currentPlace }
        if (newEvents == from.filterKeys { it != Global.currentPlace }) return null
        // now only InMemory. In future may be Database, if too many events
        return InMemoryEventStorage()
    }

}

data class EventSynchronizeResult(
    var imported: Int = 0,
    val readingErrors: MutableList<EventsSequence.ReadingError> = mutableListOf(),
    var error: String? = null
)