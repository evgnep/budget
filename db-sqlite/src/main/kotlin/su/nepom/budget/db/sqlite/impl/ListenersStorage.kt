package su.nepom.budget.db.sqlite.impl

import su.nepom.budget.db.Db.SubscribeKind
import su.nepom.budget.db.Db.Subscription
import su.nepom.budget.db.DbListener
import java.util.concurrent.CopyOnWriteArrayList

class ListenersStorage {
    private val listenersHolder = CopyOnWriteArrayList<ListenerInfo>()

    val listeners: List<ListenerInfo> get() = listenersHolder

    fun addSubscribe(kinds: Set<SubscribeKind>, listener: DbListener): Subscription {
        val info = ListenerInfo(kinds, listener)
        listenersHolder.add(info)
        return object : Subscription {
            override fun close() {
                listenersHolder.removeIf { it === info }
            }
        }
    }

    data class ListenerInfo(val kinds: Set<SubscribeKind>, val listener: DbListener)
}