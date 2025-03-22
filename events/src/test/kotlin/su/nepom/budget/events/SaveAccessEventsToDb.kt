package su.nepom.budget.events

import su.nepom.budget.db.sqlite.createSqliteDatabase
import su.nepom.budget.event.ActualEvent
import su.nepom.budget.events.synchronizer.ConflictResolver
import su.nepom.budget.events.synchronizer.EventSynchronizer
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.Place
import java.nio.file.Path

internal object FakeConflictResolver: ConflictResolver {
    override suspend fun resolve(kind: ObjectKind, events: List<ActualEvent>): ConflictResolver.Result {
        throw IllegalStateException("No conflicts expected")
    }
}


fun main() {
    val reader = EventStoreReader(Path.of("_data/events"), Place.NULL)
    createSqliteDatabase(Path.of("_data/budget.sqlite")).use { db ->
        EventSynchronizer(reader, db, FakeConflictResolver).synchronize()
    }
}