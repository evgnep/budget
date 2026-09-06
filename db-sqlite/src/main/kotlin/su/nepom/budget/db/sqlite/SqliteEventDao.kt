package su.nepom.budget.db.sqlite

import org.ktorm.database.Database
import org.ktorm.dsl.and
import org.ktorm.dsl.asc
import org.ktorm.dsl.desc
import org.ktorm.dsl.eq
import org.ktorm.dsl.gte
import org.ktorm.dsl.like
import org.ktorm.dsl.lte
import org.ktorm.entity.add
import org.ktorm.entity.drop
import org.ktorm.entity.filter
import org.ktorm.entity.find
import org.ktorm.entity.map
import org.ktorm.entity.sortedBy
import org.ktorm.entity.take
import su.nepom.budget.Global
import su.nepom.budget.db.Db
import su.nepom.budget.db.dao.EventDao
import su.nepom.budget.db.sqlite.mapping.events
import su.nepom.budget.db.sqlite.mapping.toEntity
import su.nepom.budget.db.sqlite.utils.DatabaseHolder
import su.nepom.budget.db.sqlite.utils.toTimestamp
import su.nepom.budget.event.ActualEvent
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.Place
import su.nepom.budget.model.Uuid

internal class SqliteEventDao(
    private val session: SqliteSession,
): EventDao, DatabaseHolder {
    override fun save(event: ActualEvent): Unit = session.doWriteOp(dontCommit = true) {
        if (event.coords.source == Global.currentPlace) {
            require(event.coords.no == 0) { "Coords.no must be zero for local event" }
        }
        events.add(event.toEntity())
        session.db.eventsNotifier.addEvent(Db.SubscribeKind.from(event.content.objectKind), event)
    }

    override fun getLastEventCoords(): Map<Place, Int> = session.db.eventProcessor.lastEventCoords

    override fun getLastEventForObject(uuid: Uuid, kind: ObjectKind): ActualEvent? = session.doReadOp {
        events.sortedBy { it.id.desc() }
            .find { (it.objectUuid eq uuid.id) and (it.objectKind eq kind.name) }
            ?.toEvent()
    }

    override fun getEventsForObject(uuid: Uuid, kind: ObjectKind): List<ActualEvent> = session.doReadOp {
        events.sortedBy { it.id.desc() }
            .filter { (it.objectUuid eq uuid.id) and (it.objectKind eq kind.name) }
            .map { it.toEvent() }
    }

    override fun getEventsForSourceFrom(source: Place, from: Int): List<ActualEvent> = session.doReadOp {
        val lastNo = getLastEventCoords()[source] ?: 0
        if (from > lastNo) listOf()
        else events.sortedBy { it.id }
            .filter { (it.source eq source.code) and (it.no gte from) and (it.no lte lastNo) }
            .map { it.toEvent() }
    }

    override fun getByQuery(query: EventDao.Query): List<ActualEvent> = session.doReadOp {
        val filter = query.filter
        var seq = events
        filter.place?.let { place -> seq = seq.filter { it.source eq place.code } }
        filter.no?.let { range -> seq = seq.filter { (it.no gte range.start) and (it.no lte range.endInclusive) } }
        filter.created?.let { range ->
            seq = seq.filter { (it.created gte range.start.toTimestamp()) and (it.created lte range.endInclusive.toTimestamp()) }
        }
        filter.creator?.let { creator -> seq = seq.filter { it.creator eq creator } }
        filter.objectKind?.let { kind -> seq = seq.filter { it.objectKind eq kind.name } }
        // type isn't a column, so match it inside the serialized json
        filter.type?.let { type -> seq = seq.filter { it.serialized like "%\"type\":\"${type.name}\"%" } }
        filter.contentPart?.let { part -> seq = seq.filter { it.serialized like "%$part%" } }
        seq = if (query.sortByDateAsc) seq.sortedBy({ it.created.asc() }, { it.id.asc() })
        else seq.sortedBy({ it.created.desc() }, { it.id.desc() })
        seq.drop(query.offset).take(query.limit).map { it.toEvent() }
    }

    override fun getDb(): Database = session.db.database
}