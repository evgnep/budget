package su.nepom.budget.db.sqlite.mapping

import kotlinx.serialization.json.Json
import org.ktorm.database.Database
import org.ktorm.entity.Entity
import org.ktorm.entity.sequenceOf
import org.ktorm.schema.Table
import org.ktorm.schema.int
import org.ktorm.schema.jdbcTimestamp
import org.ktorm.schema.long
import org.ktorm.schema.varchar
import su.nepom.budget.db.sqlite.utils.DatabaseHolder
import su.nepom.budget.db.sqlite.utils.toTimestamp
import su.nepom.budget.event.ActualEvent

internal object Events: Table<EventEntity>("event") {
    val id = long("id").primaryKey().bindTo { it.id }
    val source = varchar("source").bindTo { it.source }
    val no = int("no").bindTo { it.no }
    val created = jdbcTimestamp("created").bindTo { it.created }
    val creator = varchar("creator").bindTo { it.creator }
    val objectUuid = varchar("object_uuid").bindTo { it.objectUuid }
    val objectKind = varchar("object_kind").bindTo { it.objectKind }
    val serialized = varchar("serialized").bindTo { it.serialized }
}

internal interface EventEntity: Entity<EventEntity> {
    val id: Long
    var source: String
    var no: Int?
    var created: java.sql.Timestamp
    var creator: String
    var objectUuid: String
    var objectKind: String
    var serialized: String

    companion object : Entity.Factory<EventEntity>()

    fun toEvent(): ActualEvent = Json.decodeFromString<ActualEvent>(serialized).let {
        val n = no ?: throw IllegalStateException("No isn't updated yet")
        if (it.coords.no == 0) it.copy(coords = it.coords.copy(no = n)) else it
    }
}

internal fun ActualEvent.toEntity() = EventEntity {
    val s = this@toEntity
    source = s.coords.source.code
    no = s.coords.no.takeIf { it != 0 }
    created = s.created.toTimestamp()
    creator = s.creator
    objectUuid = s.uuid.id
    objectKind = s.content.objectKind.name
    serialized = Json.encodeToString(s)
}

internal val Database.events get() = this.sequenceOf(Events)

internal val DatabaseHolder.events get() = this.getDb().sequenceOf(Events)