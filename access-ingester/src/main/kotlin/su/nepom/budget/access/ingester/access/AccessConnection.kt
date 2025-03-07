package su.nepom.budget.access.ingester.access

import io.github.oshai.kotlinlogging.KotlinLogging
import java.sql.Connection
import java.sql.DriverManager

private lateinit var accessConnectionHolder: Connection

private val logger = KotlinLogging.logger {}

val accessConnection: Connection get() = accessConnectionHolder

fun connectToAccess(path: String) {
    accessConnectionHolder = DriverManager.getConnection("jdbc:ucanaccess://" + path.replace("\\", "/"))
    logger.info { "Connect to access database at $path" }
}

