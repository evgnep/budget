package su.nepom.budget.desktop.ui.balance

import jakarta.inject.Inject
import javafx.beans.binding.Bindings
import javafx.beans.binding.StringBinding
import javafx.beans.value.ChangeListener
import javafx.css.PseudoClass
import javafx.fxml.FXML
import javafx.scene.control.Label
import su.nepom.budget.desktop.model.AccountObservable
import su.nepom.budget.desktop.util.fx.Controller
import su.nepom.budget.desktop.util.fx.Disposable
import su.nepom.budget.model.RawMoney
import su.nepom.budget.utils.format

/**
 * One account's current balance card, shown inside AccountRestsController's FlowPane (see
 * accountRests.fxml). [setAccount] is called by the host right after the card is created, from
 * the FXMLLoader's controller factory - before @FXML fields are injected - so the actual binding
 * happens in [initialize] instead, same as AccountPickerController.configure()/initialize().
 *
 * [account] outlives this controller (it lives in AccountService.accounts for the whole session),
 * so every listener registered on it here must be undone in [dispose] - otherwise account.currency/
 * restProperty keep this card (and its Labels) reachable forever, even after the host removes the
 * card from its FlowPane.
 */
class AccountRestController @Inject constructor() : Controller, Disposable {
    @FXML private lateinit var nameLabel: Label
    @FXML private lateinit var restLabel: Label
    @FXML private lateinit var currencyLabel: Label

    private lateinit var account: AccountObservable
    private lateinit var currencyBinding: StringBinding
    private lateinit var restBinding: StringBinding
    private val negativeListener = ChangeListener<RawMoney> { _, _, rest -> updateNegative(rest.value < 0) }

    fun setAccount(account: AccountObservable) {
        this.account = account
    }

    @FXML
    private fun initialize() {
        nameLabel.textProperty().bind(account.name)
        currencyBinding = Bindings.createStringBinding({ currencySymbol() }, account.currency)
        currencyLabel.textProperty().bind(currencyBinding)
        restBinding = Bindings.createStringBinding(
            { account.restProperty.get().format(account.currency.get()?.content?.digitsAfterPoint ?: 2) },
            account.restProperty, account.currency,
        )
        restLabel.textProperty().bind(restBinding)
        account.restProperty.addListener(negativeListener)
        updateNegative(account.restProperty.get().value < 0)
    }

    override fun dispose() {
        nameLabel.textProperty().unbind()
        currencyLabel.textProperty().unbind()
        restLabel.textProperty().unbind()
        currencyBinding.dispose()
        restBinding.dispose()
        account.restProperty.removeListener(negativeListener)
    }

    private fun updateNegative(negative: Boolean) {
        restLabel.pseudoClassStateChanged(NEGATIVE, negative)
    }

    private fun currencySymbol(): String {
        val currency = account.currency.get()?.content ?: return ""
        return currency.symbol.takeIf { it.isNotEmpty() } ?: currency.name
    }

    private companion object {
        val NEGATIVE: PseudoClass = PseudoClass.getPseudoClass("negative")
    }
}
