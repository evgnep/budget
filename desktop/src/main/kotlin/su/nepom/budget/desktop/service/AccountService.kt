package su.nepom.budget.desktop.service

import jakarta.inject.Inject
import jakarta.inject.Singleton
import javafx.collections.FXCollections
import javafx.collections.ListChangeListener
import javafx.collections.ObservableList
import su.nepom.budget.db.Db
import su.nepom.budget.desktop.model.AccountObservable
import su.nepom.budget.desktop.util.db.ObservableEntitiesList
import su.nepom.budget.event.AccountContent
import su.nepom.budget.event.Event
import su.nepom.budget.model.RawMoney

@Singleton
class AccountService @Inject constructor(
    dbService: DbService,
    private val currencyService: CurrencyService,
) {
    val accountFactory = AccountObservable.Factory(currencyService.currencies)

    val accounts =
        ObservableEntitiesList(
            dbService.sessionProperty,
            { session -> session.accountDao.getAll().map { newObservable(it) } },
            ::processEvents,
            setOf(Db.SubscribeKind.ACCOUNT)
        )

    /**
     * All tags used by accounts, sorted. Updates live when accounts change.
     */
    val tags: ObservableList<String> = FXCollections.observableArrayList()

    init {
        accounts.addListener(ListChangeListener { recomputeTags() })
        recomputeTags()
    }

    private fun recomputeTags() {
        val all = accounts.flatMapTo(sortedSetOf()) { it.content.tags }
        if (all != tags.toHashSet()) tags.setAll(all)
    }

    private fun newObservable(content: AccountContent) =
        AccountObservable(content, RawMoney.ZERO, currencyService.currencies)

    private fun processEvents(
        events: Collection<Event<*>>,
        target: ObservableEntitiesList<AccountObservable>
    ) {
        events.forEach { event ->
            val content = event.content
            if (content is AccountContent) {
                val current = target[event.uuid]
                if (current != null) current.contentProperty.set(content)
                else target.add(newObservable(content))
            }
        }
    }
}
