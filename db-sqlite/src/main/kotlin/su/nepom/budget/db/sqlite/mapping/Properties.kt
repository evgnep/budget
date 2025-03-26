package su.nepom.budget.db.sqlite.mapping

import org.ktorm.database.Database
import org.ktorm.entity.Entity
import org.ktorm.entity.sequenceOf
import org.ktorm.schema.Table
import org.ktorm.schema.varchar
import su.nepom.budget.db.sqlite.utils.DatabaseHolder

internal object Properties : Table<PropertyEntity>("property") {
    val key = varchar("key").primaryKey().bindTo { it.key }
    val value = varchar("value").bindTo { it.value }
}

internal interface PropertyEntity : Entity<PropertyEntity> {
    var key: String
    var value: String

    companion object : Entity.Factory<PropertyEntity>()
}

internal val Database.properties get() = this.sequenceOf(Properties)

internal val DatabaseHolder.properties get() = this.getDb().properties
