package su.nepom.budget.desktop.util

import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.value.ChangeListener
import su.nepom.budget.db.Session

open class CheckableDatabaseProperty<T>(
    private val key: String,
    private val session: ReadOnlyObjectProperty<Session?>,
    private val errorMessageIfNull: String,
    private val serializer: (T) -> String,
    private val deserializer: (String) -> T,
    private val checker: (T) -> CheckResult,
) {
    private val holder = ReadOnlyObjectWrapper<T>()

    private var lastWarning: CheckResult = CheckOk

    val property: ReadOnlyObjectProperty<T> = holder.readOnlyProperty

    init {
        session.addListener { _, _, _ -> setIfValidAndIgnoreNull(readFromDb()) }
        if (session.value != null) setIfValidAndIgnoreNull(readFromDb())
    }

    val value: T get() = property.value

    fun setIfValidAndIgnoreNull(value: T?): CheckResult {
        if (value == null) return CheckError(errorMessageIfNull)
        return setIfValid(value)
    }

    fun setIfValid(value: T?): CheckResult {
        if (value == null) throw IllegalArgumentException("Invalid value: $errorMessageIfNull")
        val result = if (value == holder.value) lastWarning else check(value)
        if (result is CheckError) throw IllegalArgumentException("Invalid value: ${result.message}")
        if (value == holder.value) return result
        lastWarning = result
        val session = session.value ?: throw IllegalStateException("Db is closed")
        session.propertyDao.save(key, serializer(value))
        holder.set(value)
        return result
    }

    fun check(value: T?): CheckResult {
        if (value == null) return CheckError(errorMessageIfNull)
        if (value == property.value) {
            return lastWarning
        }
        return checker(value)
    }

    fun addListenerAndCallItNow(listener: ChangeListener<in T>) {
        property.addListener(listener)
        if (property.value != null) {
            listener.changed(property, null, property.value)
        }
    }

    private fun readFromDb(): T? {
        val serialized = session.value?.propertyDao?.get(key) ?: return null
        val deserialized = runCatching { deserializer(serialized) }.getOrNull() ?: return null
        return if (checker(deserialized).isNotError) deserialized
        else null
    }
}

class CheckableDatabaseStringProperty(
    key: String,
    session: ReadOnlyObjectProperty<Session?>,
    errorMessageIfNull: String,
    checker: (String) -> CheckResult
): CheckableDatabaseProperty<String>(key, session, errorMessageIfNull, { it }, { it }, checker)