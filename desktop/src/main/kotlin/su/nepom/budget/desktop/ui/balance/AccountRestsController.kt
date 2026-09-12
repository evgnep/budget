package su.nepom.budget.desktop.ui.balance

import jakarta.inject.Inject
import javafx.fxml.FXML
import javafx.scene.Node
import javafx.scene.layout.FlowPane
import javafx.scene.layout.VBox
import javafx.stage.Stage
import su.nepom.budget.desktop.service.AccountService
import su.nepom.budget.desktop.util.fx.Controller
import su.nepom.budget.desktop.util.fx.Disposable
import su.nepom.budget.desktop.util.fx.FxmlService
import su.nepom.budget.event.TransactionContent
import su.nepom.budget.model.AccountId
import su.nepom.budget.model.Uuid

/**
 * "Остатки по счетам" widget shown next to the transactions list (see TransactionController):
 * the host accumulates a set of MONEY accounts via [addAccountsFromTransaction] and clears it
 * with [clearAccounts] whenever its filter changes - this controller only renders whatever set
 * it is given, one AccountRestController card per account, sorted by name.
 */
class AccountRestsController @Inject constructor(
    private val accountService: AccountService,
    private val fxmlService: FxmlService,
) : Controller, Disposable {
    @FXML private lateinit var cardsPane: FlowPane

    private lateinit var stage: Stage
    private val order = mutableListOf<Uuid>()
    private val cards = mutableMapOf<Uuid, Pair<AccountRestController, Node>>()

    fun setStage(stage: Stage) {
        this.stage = stage
    }

    fun clearAccounts() {
        cards.values.forEach { it.first.dispose() }
        cards.clear()
        order.clear()
        cardsPane.children.clear()
    }

    fun setAccounts(uuids: List<AccountId>, transaction: TransactionContent? = null) {
        val newAccounts = uuids.mapTo(mutableSetOf()) { it.uuid }
        transaction?.items?.forEach { newAccounts.add(it.account.uuid) }
        val accountsToAdd = newAccounts - cards.keys
        val accountsToRemove = cards.keys - newAccounts
        accountsToRemove.forEach { uuid ->
            order.remove(uuid)
            val controllerAndNode = cards.remove(uuid) ?: return@forEach
            controllerAndNode.first.dispose()
            cardsPane.children.remove(controllerAndNode.second)
        }
        accountsToAdd.forEach { uuid -> addAccount(uuid) }
    }

    // card removal is not needed elsewhere - the set only ever grows between clearAccounts() calls
    // (see TransactionController), so dispose() here is exactly clearAccounts()
    override fun dispose() = clearAccounts()

    private fun addAccount(uuid: Uuid) {
        if (uuid in cards) return
        val account = accountService.accounts[uuid] ?: return
        lateinit var cardController: AccountRestController
        val card: VBox = fxmlService.load("balance/accountRest.fxml", stage, null) { controller ->
            cardController = controller as AccountRestController
            cardController.setAccount(account)
        }
        cards[uuid] = cardController to card
        val index = order.indexOfFirst { accountName(it) > account.content.name.lowercase() }
            .let { if (it < 0) order.size else it }
        order.add(index, uuid)
        cardsPane.children.add(index, card)
    }

    private fun accountName(uuid: Uuid): String =
        accountService.accounts[uuid]?.content?.name?.lowercase() ?: ""
}
