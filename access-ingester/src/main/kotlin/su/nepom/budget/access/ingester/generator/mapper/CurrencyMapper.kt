package su.nepom.budget.access.ingester.generator.mapper

import org.ktorm.dsl.QueryRowSet
import org.ktorm.dsl.insert
import su.nepom.budget.access.ingester.Config
import su.nepom.budget.access.ingester.access.CurrencyAccess
import su.nepom.budget.access.ingester.access.ObjectAccess
import su.nepom.budget.access.ingester.generator.procesed.dao.Currencies
import su.nepom.budget.access.ingester.generator.procesed.database
import su.nepom.budget.events.model.CurrencyContent
import su.nepom.budget.events.model.EventType
import su.nepom.budget.model.CurrencyCode

internal class CurrencyMapper(
    private val currencies: Map<String, Config.CurrencyInfo>
) : Mapper {
    override fun toProcessedObject(rs: QueryRowSet) = CurrencyProcessed(
        CurrencyAccess(rs[Currencies.id]!!, rs[Currencies.name]!!),
        CurrencyCode(rs[Currencies.code]!!)
    )

    override fun onDelete(old: ObjectProcessed) =
        throw UnsupportedOperationException("Currency deletion is not supported: ${old.obj}")

    override fun onNew(new: ObjectAccess): EventsAndActions {
        val content = (new as CurrencyAccess).toContent()
        val event = createEventForMapper(content, EventType.NEW)
        return EventsAndActions(event) {
            database.insert(Currencies) {
                set(it.id, new.id)
                set(it.code, content.code.code)
                set(it.name, new.name)
            }
        }
    }

    override fun onUpdate(old: ObjectProcessed, new: ObjectAccess): EventsAndActions {
        throw UnsupportedOperationException("Currency updating is not supported: $new")
    }

    private fun CurrencyAccess.toContent(): CurrencyContent {
        val info = currencies[name] ?: error("Currency info not found for name: $name")
        return CurrencyContent(CurrencyCode(info.code), info.name, info.digitsAfterPoint, info.code)
    }
}

internal data class CurrencyProcessed(override val obj: CurrencyAccess, val code: CurrencyCode): ObjectProcessed
