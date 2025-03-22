package su.nepom.budget.db.sqlite.impl

import su.nepom.budget.event.ActualVersionContent
import java.util.concurrent.ConcurrentHashMap

internal open class TableCopy<K, V>(
    val clazz: Class<V>,
    queryAllRecords: () -> Map<K, V>,
) {
    protected val committed: MutableMap<K, V> = queryAllRecords().toMap(ConcurrentHashMap())

    private val currentTransaction: MutableMap<K, V> = ConcurrentHashMap()

    fun onTransactionStart() {
        currentTransaction.clear()
    }

    fun onTransactionCommit() {
        committed += currentTransaction
        currentTransaction.clear()
    }

    fun onTransactionRollback() {
        currentTransaction.clear()
    }

    fun getAll(): Map<K, V> = if (currentTransaction.isEmpty()) committed
    else HashMap(committed).apply { putAll(currentTransaction) }

    operator fun get(id: K): V? = currentTransaction[id] ?: committed[id]

    fun getOrThrow(id: K): V = get(id) ?:
        throw NoSuchElementException("No such element (${clazz.simpleName}) with id $id")

    fun setInTransaction(id: K, value: V) {
        currentTransaction[id] = value
    }
}

internal fun <K, V> TableCopy<K, V>.setInTransactionUnsafe(content: ActualVersionContent) {
    setInTransactionUnsafe(content.id as K, content)
}

internal fun <K, V> TableCopy<K, V>.setInTransactionUnsafe(id: K, value: Any) {
    if (value::class.java != clazz) {
        throw IllegalArgumentException("Content must be $clazz, but it is ${value::class.java}")
    }
    setInTransaction(id, value as V)
}