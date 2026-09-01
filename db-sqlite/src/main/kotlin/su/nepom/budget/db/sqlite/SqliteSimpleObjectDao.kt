package su.nepom.budget.db.sqlite

import org.ktorm.database.Database
import org.ktorm.dsl.and
import org.ktorm.dsl.eq
import org.ktorm.entity.add
import org.ktorm.entity.count
import org.ktorm.entity.filter
import org.ktorm.entity.find
import org.ktorm.entity.map
import org.ktorm.entity.update
import su.nepom.budget.db.dao.SimpleObjectDao
import su.nepom.budget.db.sqlite.mapping.simpleObjects
import su.nepom.budget.db.sqlite.mapping.toSimpleObjectEntity
import su.nepom.budget.db.sqlite.utils.DatabaseHolder
import su.nepom.budget.event.ActualVersionContent
import su.nepom.budget.event.EventType
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.Uuid

/**
 * One implementation for every "simple object" kind - see [ObjectKind.simpleObject]. Adding a new
 * kind needs no new DAO, only a new StorableContent subtype and an ObjectKind entry.
 */
internal class SqliteSimpleObjectDao<T : ActualVersionContent>(
    private val session: SqliteSession,
    private val kind: ObjectKind,
) : SimpleObjectDao<T>, DatabaseHolder {
    private val cache get() = session.db.simpleObjectCache

    @Suppress("UNCHECKED_CAST")
    override fun getAll(withHidden: Boolean): List<T> = session.doReadOp {
        if (!withHidden) cache.getVisible(kind) as List<T>
        else simpleObjects.filter { it.kind eq kind.name }.map { it.toContent() as T }
    }

    @Suppress("UNCHECKED_CAST")
    override fun getById(id: Uuid): T? = session.doReadOp {
        (cache.get(kind, id) ?: findInDb(id)) as T?
    }

    override fun count(): Int = session.doReadOp {
        simpleObjects.count { it.kind eq kind.name }
    }

    override fun save(entity: T): T = session.doWriteOp {
        require(entity.objectKind == kind) { "Expected kind $kind, but got ${entity.objectKind}" }
        kind.validator(entity, session)
        if (findInDb(entity.uuid) == null) {
            session.saveEvent(entity, EventType.NEW)
            simpleObjects.add(entity.toSimpleObjectEntity())
        } else {
            session.saveEvent(entity, EventType.UPDATE)
            simpleObjects.update(entity.toSimpleObjectEntity())
        }
        cache.setInTransaction(kind, entity)
        entity
    }

    private fun findInDb(id: Uuid): ActualVersionContent? =
        simpleObjects.find { (it.kind eq kind.name) and (it.uuid eq id.id) }?.toContent()

    override fun getDb(): Database = session.db.database
}
