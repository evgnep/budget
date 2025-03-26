package su.nepom.budget.db.sqlite

import org.ktorm.database.Database
import org.ktorm.dsl.eq
import org.ktorm.entity.add
import org.ktorm.entity.find
import org.ktorm.entity.removeIf
import org.ktorm.entity.update
import su.nepom.budget.db.dao.PropertyDao
import su.nepom.budget.db.sqlite.mapping.Properties
import su.nepom.budget.db.sqlite.mapping.PropertyEntity
import su.nepom.budget.db.sqlite.mapping.properties
import su.nepom.budget.db.sqlite.utils.DatabaseHolder

internal class SqlitePropertyDao(private val session: SqliteSession): PropertyDao, DatabaseHolder {
    override fun get(key: String): String? = session.doReadOp {
        properties.find { Properties.key eq key }?.value
    }

    override fun save(key: String, value: String): Unit = session.doWriteOp {
        val exists = get(key) != null
        val entity = PropertyEntity {
            this.key = key
            this.value = value
        }
        if (exists) properties.update(entity)
        else properties.add(entity)
    }

    override fun delete(key: String): Unit = session.doWriteOp {
        properties.removeIf { it.key eq key }
    }

    override fun getDb(): Database = session.db.database
}