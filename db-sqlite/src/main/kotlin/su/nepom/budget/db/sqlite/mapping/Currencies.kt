package su.nepom.budget.db.sqlite.mapping

import org.ktorm.database.Database
import org.ktorm.entity.Entity
import org.ktorm.entity.sequenceOf
import org.ktorm.schema.Table
import org.ktorm.schema.boolean
import org.ktorm.schema.int
import org.ktorm.schema.varchar
import su.nepom.budget.db.sqlite.utils.DatabaseHolder
import su.nepom.budget.db.sqlite.utils.uuidCode
import su.nepom.budget.event.CurrencyContent
import su.nepom.budget.model.CurrencyCode
import su.nepom.budget.model.CurrencyId
import su.nepom.budget.model.Uuid

internal object Currencies: Table<CurrencyEntity>("currency") {
    val uuid = varchar("uuid").primaryKey().bindTo { it.uuid }
    val name = varchar("name").bindTo { it.name }
    val digitsAfterPoint = int("digits_after_point").bindTo { it.digitsAfterPoint }
    val officialCode = varchar("official_code").bindTo { it.officialCode }
    val hidden = boolean("hidden").bindTo { it.hidden }
    val symbol = varchar("symbol").bindTo { it.symbol }
}

internal interface CurrencyEntity: Entity<CurrencyEntity> {
    var uuid: String
    var name: String
    var digitsAfterPoint: Int
    var officialCode: String
    var hidden: Boolean
    var symbol: String

    companion object : Entity.Factory<CurrencyEntity>()

    fun toCurrencyContent() = CurrencyContent(
        CurrencyId(Uuid(uuid), CurrencyCode(officialCode)),
        name,
        digitsAfterPoint,
        officialCode,
        hidden,
        symbol
    )
}

internal fun CurrencyContent.toEntity() = CurrencyEntity {
    val s = this@toEntity
    uuid = s.uuidCode()
    name = s.name
    digitsAfterPoint = s.digitsAfterPoint
    officialCode = s.officialCode
    hidden = s.hidden
    symbol = s.symbol
}

internal val Database.currencies get() = this.sequenceOf(Currencies)

internal val DatabaseHolder.currencies get() = this.getDb().sequenceOf(Currencies)