package su.nepom.budget.desktop.ui.balance

import jakarta.inject.Inject
import javafx.css.PseudoClass
import javafx.fxml.FXML
import javafx.scene.control.Label
import su.nepom.budget.desktop.model.AccountObservable
import su.nepom.budget.desktop.util.fx.Controller
import su.nepom.budget.desktop.util.fx.Disposable
import su.nepom.budget.desktop.util.fx.WeakListeners
import su.nepom.budget.utils.format

/**
 * One account's current balance card, shown inside AccountRestsController's FlowPane (see
 * accountRests.fxml). [setAccount] is called by the host right after the card is created, from
 * the FXMLLoader's controller factory - before @FXML fields are injected - so the actual binding
 * happens in [initialize] instead, same as AccountPickerController.configure()/initialize().
 *
 * [account] outlives this controller (it lives in AccountService.accounts for the whole session) -
 * every listener registered on it here goes through [weakListeners], so a single dispose() call
 * detaches all of them instead of undoing each field by hand.
 */
class AccountRestController @Inject constructor() : Controller, Disposable {
    @FXML private lateinit var nameLabel: Label
    @FXML private lateinit var restLabel: Label
    @FXML private lateinit var currencyLabel: Label

    private val weakListeners = WeakListeners()
    private lateinit var account: AccountObservable

    fun setAccount(account: AccountObservable) {
        this.account = account
    }

    @FXML
    private fun initialize() {
        weakListeners.addListenerAndCallNow(account.name) { _, _, name -> nameLabel.text = name }
        weakListeners.addListenerAndCallNow(account.currency) { _, _, _ -> updateRestAndCurrency() }
        weakListeners.addListenerAndCallNow(account.restProperty) { _, _, _ -> updateRestAndCurrency() }
    }

    override fun dispose() {
        weakListeners.dispose()
    }

    private fun updateRestAndCurrency() {
        val currency = account.currency.get()?.content
        currencyLabel.text = currency?.symbol?.takeIf { it.isNotEmpty() } ?: currency?.name ?: ""
        restLabel.text = account.restProperty.get().format(currency?.digitsAfterPoint ?: 2)
        restLabel.pseudoClassStateChanged(NEGATIVE, account.restProperty.get().value < 0)
    }

    private companion object {
        val NEGATIVE: PseudoClass = PseudoClass.getPseudoClass("negative")
    }
}
