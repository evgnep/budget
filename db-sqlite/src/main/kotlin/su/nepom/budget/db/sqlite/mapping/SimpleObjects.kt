package su.nepom.budget.db.sqlite.mapping

import kotlinx.serialization.json.Json
import org.ktorm.database.Database
import org.ktorm.entity.Entity
import org.ktorm.entity.sequenceOf
import org.ktorm.schema.Table
import org.ktorm.schema.boolean
import org.ktorm.schema.varchar
import su.nepom.budget.db.sqlite.utils.DatabaseHolder
import su.nepom.budget.event.ActualVersionContent

// One table for all "simple object" kinds. Content is stored as polymorphic JSON, so a new
// kind needs no schema change - just a new StorableContent subtype.
internal object SimpleObjects : Table<SimpleObjectEntity>("simple_object") {
    val uuid = varchar("uuid").primaryKey().bindTo { it.uuid }
    val kind = varchar("kind").bindTo { it.kind }
    val hidden = boolean("hidden").bindTo { it.hidden }
    val content = varchar("content").bindTo { it.content }
}

internal interface SimpleObjectEntity : Entity<SimpleObjectEntity> {
    var uuid: String
    var kind: String
    var hidden: Boolean
    var content: String

    companion object : Entity.Factory<SimpleObjectEntity>()

    fun toContent(): ActualVersionContent = Json.decodeFromString<ActualVersionContent>(content)
}

internal fun ActualVersionContent.toSimpleObjectEntity() = SimpleObjectEntity {
    val s = this@toSimpleObjectEntity
    uuid = s.uuid.id
    kind = s.objectKind.name
    hidden = s.isHidden
    content = Json.encodeToString<ActualVersionContent>(s)
}

internal val Database.simpleObjects get() = this.sequenceOf(SimpleObjects)

internal val DatabaseHolder.simpleObjects get() = this.getDb().sequenceOf(SimpleObjects)
