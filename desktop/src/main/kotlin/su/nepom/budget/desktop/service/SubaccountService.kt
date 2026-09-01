package su.nepom.budget.desktop.service

import jakarta.inject.Inject
import jakarta.inject.Singleton
import su.nepom.budget.db.Db
import su.nepom.budget.desktop.model.SubaccountObservable
import su.nepom.budget.desktop.util.db.ObservableEntitiesList
import su.nepom.budget.event.Event
import su.nepom.budget.event.SubaccountContent
import su.nepom.budget.model.ObjectKind

@Singleton
class SubaccountService @Inject constructor(
    dbService: DbService,
) {
    val subaccounts =
        ObservableEntitiesList(
            dbService.sessionProperty,
            { session ->
                session.simpleObjectDao<SubaccountContent>(ObjectKind.SUBACCOUNT)
                    .getAll(withHidden = true)
                    .map { SubaccountObservable(it) }
            },
            ::processEvents,
            setOf(Db.SubscribeKind.SIMPLE_OBJECT)
        )

    private fun processEvents(
        events: Collection<Event<*>>,
        target: ObservableEntitiesList<SubaccountObservable>
    ) {
        events.forEach { event ->
            val content = event.content
            if (content is SubaccountContent) {
                val current = target[event.uuid]
                if (current != null) current.contentProperty.set(content)
                else target.add(SubaccountObservable(content))
            }
        }
    }
}
