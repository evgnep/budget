package su.nepom.budget.desktop.util.fx

import javafx.beans.InvalidationListener
import javafx.beans.WeakInvalidationListener
import javafx.beans.value.ChangeListener
import javafx.beans.value.WeakChangeListener
import javafx.collections.ListChangeListener
import javafx.collections.WeakListChangeListener


class WeakListeners {
    private val listenerRefs = mutableListOf<Any>()

    fun dispose() {
        listenerRefs.clear()
    }

    fun <T> remove(listener: ChangeListener<T>) {
        listenerRefs.remove(listener)
    }

    operator fun <T> invoke(listener: ChangeListener<T>): ChangeListener<T> {
        listenerRefs.add(listener)
        return WeakChangeListener(listener)
    }

    operator fun <T> invoke(listener: ListChangeListener<T>): ListChangeListener<T> {
        listenerRefs.add(listener)
        return WeakListChangeListener(listener)
    }

    operator fun invoke(listener: InvalidationListener): InvalidationListener {
        listenerRefs.add(listener)
        return WeakInvalidationListener(listener)
    }
}