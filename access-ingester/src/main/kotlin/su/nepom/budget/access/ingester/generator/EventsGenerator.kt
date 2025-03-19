package su.nepom.budget.access.ingester.generator

import io.github.oshai.kotlinlogging.KotlinLogging
import org.ktorm.dsl.Query
import org.ktorm.dsl.asc
import org.ktorm.dsl.from
import org.ktorm.dsl.orderBy
import org.ktorm.dsl.select
import su.nepom.budget.access.ingester.Config
import su.nepom.budget.access.ingester.access.DataAccess
import su.nepom.budget.access.ingester.access.ObjectAccess
import su.nepom.budget.access.ingester.access.Reader
import su.nepom.budget.access.ingester.generator.mapper.AccountsMapper
import su.nepom.budget.access.ingester.generator.mapper.CurrencyMapper
import su.nepom.budget.access.ingester.generator.mapper.EventsAndActions
import su.nepom.budget.access.ingester.generator.mapper.Mapper
import su.nepom.budget.access.ingester.generator.mapper.ObjectProcessed
import su.nepom.budget.access.ingester.generator.mapper.TransactionsMapper
import su.nepom.budget.access.ingester.generator.procesed.dao.Accounts
import su.nepom.budget.access.ingester.generator.procesed.dao.Currencies
import su.nepom.budget.access.ingester.generator.procesed.dao.Transactions
import su.nepom.budget.access.ingester.generator.procesed.database
import su.nepom.budget.event.ActualEvent
import su.nepom.budget.events.EventStoreReader
import su.nepom.budget.events.EventStoreWriter
import su.nepom.budget.model.EventCoords
import su.nepom.budget.model.Place
import java.nio.file.Path

private val SOURCE = Place("access")

private val logger = KotlinLogging.logger {}

class EventsGenerator(
    private val accessReader: Reader,
    private val config: Config,
) {
    private val eventStoreReader = EventStoreReader(Path.of(config.eventStore), Place.NULL)

    private val eventStoreWriter = EventStoreWriter(Path.of(config.eventStore), SOURCE)

    private val events = mutableListOf<ActualEvent>()

    private val dbActions = mutableListOf<() -> Unit>()

    private lateinit var current: DataAccess

    private var sequenceNo = 0

    fun generateEvents() {
        events.clear()
        dbActions.clear()
        accessReader.prepare()
        current = accessReader.readAllAccountingEntries()
        sequenceNo = eventStoreReader.getMaxEventNoForSource(SOURCE) ?: 0
        val currencyMapper = CurrencyMapper(config.currencies)

        findDifferences(
            current.currencies.values,
            database.from(Currencies).select().orderBy(Currencies.id.asc()),
            currencyMapper
        )

        val accountsMapper = AccountsMapper(currencyMapper.getAllCurrencies())
        findDifferences(
            current.accounts.values,
            database.from(Accounts).select().orderBy(Accounts.id.asc()),
            accountsMapper
        )

        findDifferences(
            current.transactions,
            database.from(Transactions).select().orderBy(Transactions.id.asc()),
            TransactionsMapper(current.users, accountsMapper.getAllAccounts(), accountsMapper)
        )

        eventStoreWriter.writeEvents(events)
        database.useTransaction {
            dbActions.forEach { it() }
        }

        logger.warn { "New events: " + events.count() }
    }

    private fun findDifferences(accessObjects: Collection<ObjectAccess>, processedQuery: Query, mapper: Mapper) {
        val sourceIterator = accessObjects.iterator()
        val processedIterator = processedQuery.iterator()
        var source: ObjectAccess? = null
        var processed: ObjectProcessed? = null

        while (true) {
            if (source == null) {
                source = if (sourceIterator.hasNext()) sourceIterator.next() else null
            }
            if (processed == null) {
                if (processedIterator.hasNext()) {
                    processed = mapper.toProcessedObject(processedIterator.next())
                }
            }
            if (source == null && processed == null) break
            if (source == null) { // processed удален
                mapper.onDelete(processed!!).process()
                processed = null
            } else if (processed == null) { // новый source
                mapper.onNew(source).process()
                source = null
            } else if (source.id == processed.obj.id) {
                if (source != processed.obj) {
                    mapper.onUpdate(processed, source).process()
                }
                source = null
                processed = null
            } else if (source.id > processed.obj.id) {
                mapper.onDelete(processed).process()
                processed = null
            } else {
                throw IllegalStateException("source.id < processed.obj.id (${processed.obj.id}): $source")
            }
        }
    }

    private fun EventsAndActions.process() {
        events.forEach {
            val enriched = it.copy(coords = EventCoords(SOURCE, ++sequenceNo))
            this@EventsGenerator.events.add(enriched)
        }
        this@EventsGenerator.dbActions.addAll(dbActions)
    }
}