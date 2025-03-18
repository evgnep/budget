package su.nepom.budget.access.ingester.generator.procesed

import io.github.oshai.kotlinlogging.KotlinLogging
import org.ktorm.database.Database
import org.sqlite.SQLiteDataSource
import javax.sql.DataSource

lateinit var processedDataSource: DataSource private set

lateinit var database: Database private set

private val logger = KotlinLogging.logger {}

fun connectToProcessed(path: String) {
    processedDataSource = SQLiteDataSource().apply { url = "jdbc:sqlite:$path" }
    database = Database.connect(processedDataSource)
    logger.info { "Connect to processed database at $path" }
    createTables()
    logger.info { "Tables created" }
}

private fun createTables() {
    processedDataSource.connection.use { con ->
        con.createStatement().use {
            it.execute(
                """
                    CREATE TABLE IF NOT EXISTS currency (
                        id INT PRIMARY KEY,
                        uuid TEXT NOT NULL,
                        code TEXT NOT NULL,
                        name TEXT NOT NULL
                    )
                """.trimIndent()
            )

            it.execute(
                """
                    CREATE TABLE IF NOT EXISTS account (
                        id INT PRIMARY KEY,
                        uuidMoney TEXT,
                        uuidBudget TEXT,
                        codeMoney TEXT,                          
                        codeBudget TEXT,                        
                        type INT NOT NULL,
                        name TEXT NOT NULL,
                        currencyId INT NOT NULL,
                        closed INT NOT NULL,
                        ordr INT NOT NULL
                    )
                """.trimIndent()
            )

            it.execute(
                """
                    CREATE TABLE IF NOT EXISTS trans (
                        id INT PRIMARY KEY,
                        uuid TEXT NOT NULL,
                        created INT NOT NULL,
                        userId INT NOT NULL,
                        accountId INT NOT NULL,
                        moneyReal INT NOT NULL,
                        accountBudgetId INT,
                        moneyBudget INT,
                        description TEXT,
                        accountTargetId INT,
                        moneyTransfer INT,
                        flag INT NOT NULL,
                        deleted INT NOT NULL
                    )
                """.trimIndent()
            )
        }
    }
}
