package su.nepom.budget.db.sqlite.mapping

import kotlinx.serialization.json.Json
import org.ktorm.database.Database
import org.ktorm.entity.Entity
import org.ktorm.entity.sequenceOf
import org.ktorm.schema.Table
import org.ktorm.schema.boolean
import org.ktorm.schema.int
import org.ktorm.schema.long
import org.ktorm.schema.varchar
import su.nepom.budget.db.sqlite.utils.DatabaseHolder
import su.nepom.budget.db.sqlite.utils.uuidCode
import su.nepom.budget.event.AccountBudget
import su.nepom.budget.event.AccountContent
import su.nepom.budget.event.makeAccountCode
import su.nepom.budget.model.AccountId
import su.nepom.budget.model.AccountKind
import su.nepom.budget.model.CurrencyCode
import su.nepom.budget.model.CurrencyId
import su.nepom.budget.model.RestMark
import su.nepom.budget.model.Uuid
import su.nepom.budget.model.uuidCode

internal object Accounts : Table<AccountEntity>("account") {
    val uuid = varchar("uuid").primaryKey().bindTo { it.uuid }
    val name = varchar("name").bindTo { it.name }
    val description = varchar("description").bindTo { it.description }
    val currencyUuid = varchar("currency_uuid").bindTo { it.currencyUuid }
    val kind = varchar("kind").bindTo { it.kind }
    val tags = varchar("tags").bindTo { it.tags }
    val orderNo = int("order_no").bindTo { it.orderNo }
    val hidden = boolean("hidden").bindTo { it.hidden }
    val budget = varchar("budget").bindTo { it.budget }
    val showOnMain = boolean("show_on_main").bindTo { it.showOnMain }
    val groupPath = varchar("group_path").bindTo { it.groupPath }
    val restMark = varchar("rest_mark").bindTo { it.restMark }
}

internal interface AccountEntity : Entity<AccountEntity> {
    var uuid: String
    var name: String
    var description: String
    var currencyUuid: String
    var kind: String
    var tags: String
    var orderNo: Int
    var hidden: Boolean
    var budget: String
    var showOnMain: Boolean
    var groupPath: String
    var restMark: String

    companion object : Entity.Factory<AccountEntity>()

    fun toAccountContent() = AccountContent(
        AccountId(Uuid(uuid), makeAccountCode(name, AccountKind.byCode(kind))),
        name,
        description,
        CurrencyId(Uuid(currencyUuid), CurrencyCode.NULL),
        AccountKind.byCode(kind),
        tags.tagsFromDb(),
        orderNo,
        hidden,
        Json.decodeFromString<AccountBudget>(budget),
        showOnMain,
        Json.decodeFromString<List<String>>(groupPath),
        RestMark.valueOf(restMark)
    )
}

internal fun AccountContent.toEntity() = AccountEntity {
    val s = this@toEntity
    uuid = s.uuidCode()
    name = s.name
    description = s.description
    currencyUuid = s.currency.uuidCode()
    kind = s.kind.code
    tags = s.tags.tagsToDb()
    orderNo = s.orderNo
    hidden = s.hidden
    budget = Json.encodeToString(s.budget)
    showOnMain = s.showOnMain
    groupPath = Json.encodeToString(s.groupPath)
    restMark = s.restMark.name
}

internal val Database.accounts get() = this.sequenceOf(Accounts)

internal val DatabaseHolder.accounts get() = this.getDb().accounts

internal fun Set<String>.tagsToDb(): String = joinToString(separator = " ") {
    val encoded = it.replace("<", "&lt;").replace(">", "&gt;")
    "<$encoded>"
}

internal fun String.tagsFromDb(): Set<String> = split(">").mapNotNullTo(mutableSetOf()) {
    val value = it.trimStart(' ', '<').replace("&lt;", "<").replace("&gt;", ">")
    value.ifBlank { null }
}

internal object AccountRests : Table<AccountRestEntity>("account_rest") {
    val uuid = varchar("uuid").primaryKey().bindTo { it.uuid }
    val name = varchar("name").bindTo { it.name }
    val rest = long("rest").bindTo { it.rest }
}

internal interface AccountRestEntity : Entity<AccountRestEntity> {
    var uuid: String
    var name: String
    var rest: Long

    companion object : Entity.Factory<AccountRestEntity>()
}

internal val Database.accountRests get() = this.sequenceOf(AccountRests)

internal val DatabaseHolder.accountRests get() = this.getDb().accountRests