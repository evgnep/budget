package su.nepom.budget.access.ingester

import io.github.oshai.kotlinlogging.KotlinLogging
import su.nepom.budget.access.ingester.access.Reader
import su.nepom.budget.access.ingester.access.dao.UsersReader
import su.nepom.budget.access.ingester.access.connectToAccess
import su.nepom.budget.access.ingester.access.dao.AccountingEntryDao
import su.nepom.budget.access.ingester.access.dao.AccountsReader
import su.nepom.budget.access.ingester.access.dao.CurrenciesReader
import su.nepom.budget.utils.readObjectFromYamlResourceFile

private val logger = KotlinLogging.logger {}

fun main() {
    val config = readObjectFromYamlResourceFile<Config>("/application.yml")
    connectToAccess(config.accessPath)
    val reader = Reader(UsersReader(), CurrenciesReader(), AccountsReader(), AccountingEntryDao())
    reader.prepare()
    val data = reader.readAllAccountingEntries()

    logger.info { data.first().toString() }
    logger.info { data.last().toString() }
}