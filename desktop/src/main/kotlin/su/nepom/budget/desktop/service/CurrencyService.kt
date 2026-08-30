package su.nepom.budget.desktop.service

import jakarta.inject.Inject
import jakarta.inject.Singleton
import su.nepom.budget.db.Db
import su.nepom.budget.desktop.model.CurrencyObservable
import su.nepom.budget.desktop.util.db.ObservableEntitiesList
import su.nepom.budget.event.CurrencyContent
import su.nepom.budget.event.Event

@Singleton
class CurrencyService @Inject constructor(
    dbService: DbService,
) {
    val currencyFactory = CurrencyObservable.Factory()

    val currencies =
        ObservableEntitiesList(
            dbService.sessionProperty,
            { it.currencyDao.getAll().map { CurrencyObservable(it) } },
            ::processEvents,
            setOf(Db.SubscribeKind.CURRENCY)
        )

    private fun processEvents(
        events: Collection<Event<*>>,
        target: ObservableEntitiesList<CurrencyObservable>
    ) {
        events.forEach { event ->
            val content = event.content
            if (content is CurrencyContent) {
                val current = target[event.uuid]
                if (current != null) current.contentProperty.set(content)
                else target.add(CurrencyObservable(content))
            }
        }
    }
}
