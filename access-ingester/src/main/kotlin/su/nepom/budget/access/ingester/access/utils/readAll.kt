package su.nepom.budget.access.ingester.access.utils

import su.nepom.budget.access.ingester.access.accessConnection
import java.sql.ResultSet

fun <T> readAll(query: String, builder: ResultSet.() -> T): List<T> =
    accessConnection.createStatement().use {
        it.executeQuery(query).use { resultSet ->
            val data = mutableListOf<T>()
            while (resultSet.next()) {
                data.add(builder(resultSet))
            }
            data
        }
    }
