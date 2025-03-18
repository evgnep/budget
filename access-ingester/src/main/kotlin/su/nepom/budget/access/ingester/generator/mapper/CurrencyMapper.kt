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
import su.nepom.budget.model.CurrencyId
import su.nepom.budget.model.Uuid

internal class CurrencyMapper(
    private val currencies: Map<String, Config.CurrencyInfo>
) : Mapper {
    private val allCurrencies = mutableListOf<CurrencyProcessed>()

    fun getAllCurrencies() = allCurrencies.toList()

    override fun toProcessedObject(rs: QueryRowSet): CurrencyProcessed {
        val name = rs[Currencies.name]!!
        return CurrencyProcessed(
            CurrencyAccess(rs[Currencies.id]!!, name),
            CurrencyId(Uuid(rs[Currencies.uuid]!!), CurrencyCode(rs[Currencies.code]!!)),
            (currencies[name] ?: error("Currency info not found for name: $name")).divider
        ).also { allCurrencies.add(it) }
    }

    override fun onDelete(old: ObjectProcessed) =
        throw UnsupportedOperationException("Currency deletion is not supported: ${old.obj}")

    override fun onNew(new: ObjectAccess): EventsAndActions {
        val (content, divider) = (new as CurrencyAccess).toContent()
        val event = createEventForMapper(content, EventType.NEW)
        allCurrencies.add(CurrencyProcessed(new, content.id, divider))
        return EventsAndActions(event) {
            database.insert(Currencies) {
                set(it.id, new.id)
                set(it.uuid, content.id.uuid.id)
                set(it.code, content.id.readable.code)
                set(it.name, new.name)
            }
        }
    }

    override fun onUpdate(old: ObjectProcessed, new: ObjectAccess): EventsAndActions {
        throw UnsupportedOperationException("Currency updating is not supported: $new")
    }

    private fun CurrencyAccess.toContent(): Pair<CurrencyContent, Int> {
        val info = currencies[name] ?: error("Currency info not found for name: $name")
        return CurrencyContent(CurrencyId(CurrencyCode(info.code)), info.name, info.digitsAfterPoint, info.code) to
                info.divider
    }
}

internal data class CurrencyProcessed(override val obj: CurrencyAccess, val id: CurrencyId, val divider: Int) :
    ObjectProcessed
