package su.nepom.budget.db.sqlite.impl

import org.ktorm.entity.Entity
import org.ktorm.entity.EntitySequence
import org.ktorm.entity.add
import org.ktorm.entity.count
import org.ktorm.entity.find
import org.ktorm.entity.map
import org.ktorm.entity.update
import org.ktorm.schema.ColumnDeclaring
import org.ktorm.schema.Table
import su.nepom.budget.db.dao.CrudDao
import su.nepom.budget.db.sqlite.SqliteSession
import su.nepom.budget.db.sqlite.utils.DatabaseHolder
import su.nepom.budget.event.ActualVersionContent
import su.nepom.budget.event.EventType
import su.nepom.budget.model.Uuid

internal abstract class AbstractSqliteCrudDao<
        Content : ActualVersionContent,
        EntityType : Entity<EntityType>,
        EntityTable : Table<EntityType>>(
    protected val session: SqliteSession,
    sequenceGetter: DatabaseHolder.() -> EntitySequence<EntityType, EntityTable>,
    private val toContent: EntityType.() -> Content,
    private val toEntity: Content.() -> EntityType,
) : CrudDao<Content>, DatabaseHolder {
    protected val sequence = session.sequenceGetter()

    protected abstract fun idPredicate(table: EntityTable, id: Uuid): ColumnDeclaring<Boolean>

    protected open fun validateNew(new: Content) {
    }

    protected open fun validateUpdate(old: Content, new: Content) {
    }

    override fun getAll(): List<Content> = sequence.map(toContent)

    override fun getById(id: Uuid): Content? =
        sequence.find { idPredicate(it, id) }?.toContent()

    override fun count(): Int = sequence.count()

    override fun save(entity: Content): Content {
        session.startTransactionIfNotYet()
        val current = sequence.find { idPredicate(it, entity.id.uuid) }?.toContent()
        if (current == null) {
            validateNew(entity)
            session.saveEvent(entity, EventType.NEW)
            sequence.add(entity.toEntity())
        } else {
            validateUpdate(current, entity)
            session.saveEvent(entity, EventType.UPDATE)
            sequence.update(entity.toEntity())
        }
        return entity
    }

    override fun getDb() = session.db.database
}