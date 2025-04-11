package su.nepom.budget.desktop.util.impl

import su.nepom.budget.db.Db
import su.nepom.budget.db.DbListener
import su.nepom.budget.event.Event
import java.lang.ref.WeakReference

class WeakDbListener(delegate: DbListener): DbListener {
    private val weakReference = WeakReference(delegate)

    lateinit var subscription: Db.Subscription

    override fun invoke(events: Collection<Event<*>>) {
        val delegate = weakReference.get()
        if (delegate == null) {
            subscription.unsubscribe()
        } else {
            delegate(events)
        }
    }
}