package su.nepom.budget.access.ingester

import io.github.oshai.kotlinlogging.KotlinLogging
import su.nepom.budget.access.ingester.access.Reader
import su.nepom.budget.access.ingester.access.connectToAccess
import su.nepom.budget.access.ingester.access.dao.AccountsReader
import su.nepom.budget.access.ingester.access.dao.CurrenciesReader
import su.nepom.budget.access.ingester.access.dao.TransactionDao
import su.nepom.budget.access.ingester.access.dao.UsersReader
import su.nepom.budget.access.ingester.generator.EventsGenerator
import su.nepom.budget.access.ingester.generator.procesed.connectToProcessed
import su.nepom.budget.utils.readObjectFromYamlResourceFile
import java.nio.file.Path
import kotlin.io.path.createDirectories

private val logger = KotlinLogging.logger {}

fun main() {
    val config = readObjectFromYamlResourceFile<Config>("/application.yml")
    Path.of(config.processedDb).parent.createDirectories()
    connectToAccess(config.accessPath)
    connectToProcessed(config.processedDb)
    val reader = Reader(UsersReader(), CurrenciesReader(), AccountsReader(), TransactionDao())
    val generator = EventsGenerator(reader, config)
    generator.generateEvents()

}