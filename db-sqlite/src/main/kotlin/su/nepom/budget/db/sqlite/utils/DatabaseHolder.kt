package su.nepom.budget.db.sqlite.utils

import org.ktorm.database.Database
import org.ktorm.dsl.AssignmentsBuilder
import org.ktorm.dsl.BatchInsertStatementBuilder
import org.ktorm.dsl.batchInsert
import org.ktorm.dsl.delete
import org.ktorm.dsl.from
import org.ktorm.dsl.insert
import org.ktorm.schema.BaseTable
import org.ktorm.schema.ColumnDeclaring

internal interface DatabaseHolder {
    fun getDb(): Database
}

internal fun DatabaseHolder.from(table: BaseTable<*>) = getDb().from(table)

internal fun <T : BaseTable<*>> DatabaseHolder.delete(table: T, predicate: (T) -> ColumnDeclaring<Boolean>): Int =
    getDb().delete(table, predicate)

internal fun <T : BaseTable<*>> DatabaseHolder.insert(table: T, block: AssignmentsBuilder.(T) -> Unit): Int =
    getDb().insert(table, block)

internal fun <T : BaseTable<*>> DatabaseHolder.insertBatch(
    table: T,
    block: BatchInsertStatementBuilder<T>.() -> Unit
): IntArray = getDb().batchInsert(table, block)
