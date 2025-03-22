package su.nepom.budget.db.sqlite

import org.ktorm.database.Database
import org.ktorm.dsl.and
import org.ktorm.dsl.desc
import org.ktorm.dsl.eq
import org.ktorm.dsl.gte
import org.ktorm.dsl.lte
import org.ktorm.entity.add
import org.ktorm.entity.filter
import org.ktorm.entity.find
import org.ktorm.entity.map
import org.ktorm.entity.sortedBy
import su.nepom.budget.Global
import su.nepom.budget.db.dao.EventDao
import su.nepom.budget.db.sqlite.mapping.events
import su.nepom.budget.db.sqlite.mapping.toEntity
import su.nepom.budget.db.sqlite.utils.DatabaseHolder
import su.nepom.budget.event.ActualEvent
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.Place
import su.nepom.budget.model.Uuid

internal class SqliteEventDao(
    private val session: SqliteSession,
): EventDao, DatabaseHolder {
    override fun save(event: ActualEvent) {
        if (event.coords.source == Global.currentPlace) {
            require(event.coords.no == 0) { "Coords.no must be zero for local event" }
        }
        session.startTransactionIfNotYet()
        events.add(event.toEntity())
    }

    override fun getLastEventCoords(): Map<Place, Int> = session.db.eventProcessor.lastEventCoords

    override fun getLastEventForObject(uuid: Uuid, kind: ObjectKind): ActualEvent? =
        events.sortedBy { it.id.desc() }
            .find { (it.objectUuid eq uuid.id) and (it.objectKind eq kind.name) }
            ?.toEvent()

    override fun getEventsForSourceFrom(source: Place, from: Int): List<ActualEvent> {
        val lastNo = getLastEventCoords()[source] ?: 0
        if (from > lastNo) return listOf()
        return events.sortedBy { it.id }
            .filter { (it.source eq source.code) and (it.no gte from) and (it.no lte lastNo) }
            .map { it.toEvent() }
    }

    override fun getDb(): Database = session.db.database
}