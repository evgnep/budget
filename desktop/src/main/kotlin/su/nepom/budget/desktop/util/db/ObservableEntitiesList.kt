package su.nepom.budget.desktop.util.db

import com.sun.javafx.collections.ObservableListWrapper
import javafx.application.Platform
import javafx.beans.property.ReadOnlyObjectProperty
import su.nepom.budget.db.Db
import su.nepom.budget.db.Session
import su.nepom.budget.desktop.util.fx.WeakListeners
import su.nepom.budget.event.Event
import su.nepom.budget.model.Uuid

class ObservableEntitiesList<T : ObservableEntity<*>>(
    private val session: ReadOnlyObjectProperty<Session?>,
    private val initialEntitiesGetter: (Session) -> Collection<T>,
    private val processEvents: (Collection<Event<*>>, ObservableEntitiesList<T>) -> Unit,
    private val subscribeKinds: Set<Db.SubscribeKind>,
) : ObservableListWrapper<T>(mutableListOf(), { it.properties() }) {

  val observableEntitiesByKey: Map<Uuid, T>
    field: MutableMap<Uuid, T> = associateByTo(mutableMapOf()) { it.uuid }

  private var innerOperationCounter = 0
    private val weakListeners = WeakListeners()

    init {
        weakListeners.addListenerAndCallNow(session) { _, _, session ->
            doInnerOperation {
                if (session != null) {
                    val initialEntities = initialEntitiesGetter(session)
                    setAll(initialEntities)
                    weakListeners.subscribe(session.db, subscribeKinds) { events ->
                        Platform.runLater { processEvents(events) }
                    }
                } else {
                    clear()
                }
            }
        }
    }

    private fun doInnerOperation(block: () -> Unit) {
        innerOperationCounter += 1
        try {
            block()
        } finally {
            innerOperationCounter -= 1
        }
    }

    operator fun get(uuid: Uuid): T? = observableEntitiesByKey[uuid]

    override fun doAdd(index: Int, element: T) {
        if (innerOperationCounter == 0) throw UnsupportedOperationException()
        observableEntitiesByKey[element.uuid] = element
        super.doAdd(index, element)
    }

    override fun doSet(index: Int, element: T): T {
        throw UnsupportedOperationException()
    }

    override fun doRemove(index: Int): T =
        if (innerOperationCounter != 0) {
            val element = super.doRemove(index)
            if (element != null) observableEntitiesByKey.remove(element.uuid)
            element
        } else throw UnsupportedOperationException()

    private fun processEvents(events: Collection<Event<*>>) = doInnerOperation {
        try {
            beginChange()
            processEvents(events, this)
        } finally {
            endChange()
        }
    }
}
