package su.nepom.budget.desktop.util.fx

import javafx.beans.InvalidationListener
import javafx.beans.WeakInvalidationListener
import javafx.beans.value.ChangeListener
import javafx.beans.value.ObservableValue
import javafx.beans.value.WeakChangeListener
import javafx.collections.ListChangeListener
import javafx.collections.WeakListChangeListener
import su.nepom.budget.db.Db
import su.nepom.budget.db.DbListener
import su.nepom.budget.desktop.util.impl.WeakDbListener


class WeakListeners {
    private val listenerRefs = mutableListOf<Any>()

    private val dbListenerRefs = mutableListOf<Any>()

    private val subscriptions = mutableListOf<Db.Subscription>()

    private val detachActions = mutableListOf<() -> Unit>()

    private var db: Db? = null

    // TODO detaches everything registered here; safe to call once when the owner window closes
    fun dispose() {
        detachActions.forEach { it() }
        detachActions.clear()
        subscriptions.forEach { it.unsubscribe() }
        subscriptions.clear()
        listenerRefs.clear()
        dbListenerRefs.clear()
        db = null
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

    fun <T> addListenerAndCallNow(observable: ObservableValue<T>, listener: ChangeListener<T>) {
        val weak = this(listener)
        observable.addListener(weak)
        detachActions.add { observable.removeListener(weak) }
        listener.changed(observable, observable.value, observable.value)
    }

    fun subscribe(db: Db, vararg kind: Db.SubscribeKind, listener: DbListener) {
        subscribe(db, kind.toSet(), listener)
    }

    fun subscribe(db: Db, kinds: Set<Db.SubscribeKind>, listener: DbListener) {
        if (db !== this.db) {
            dbListenerRefs.clear()
            this.db = db
        }
        dbListenerRefs.add(listener)
        subscriptions.add(db.subscribe(kinds, listener = WeakDbListener(listener)))
    }
}