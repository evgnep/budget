package su.nepom.budget.desktop.ui.balance

import jakarta.inject.Inject
import javafx.css.PseudoClass
import javafx.fxml.FXML
import javafx.scene.control.Label
import javafx.scene.layout.VBox
import su.nepom.budget.desktop.model.AccountObservable
import su.nepom.budget.desktop.util.fx.Controller
import su.nepom.budget.desktop.util.fx.Disposable
import su.nepom.budget.desktop.util.fx.WeakListeners
import su.nepom.budget.model.RawMoney
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
    @FXML private lateinit var creditLimitRestBox: VBox
    @FXML private lateinit var creditLimitRestLabel: Label
    @FXML private lateinit var creditLimitRestCurrencyLabel: Label

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
        weakListeners.addListenerAndCallNow(account.creditLimit) { _, _, _ -> updateRestAndCurrency() }
    }

    override fun dispose() {
        weakListeners.dispose()
    }

    private fun updateRestAndCurrency() {
        val currency = account.currency.get()?.content
        val digits = currency?.digitsAfterPoint ?: 2
        val currencyText = currency?.symbol?.takeIf { it.isNotEmpty() } ?: currency?.name ?: ""
        val rest = account.restProperty.get()
        currencyLabel.text = currencyText
        restLabel.text = rest.format(digits)
        restLabel.pseudoClassStateChanged(NEGATIVE, rest.value < 0)

        val creditLimit = account.content.creditLimit
        val showCreditLimit = creditLimit.value != 0L
        creditLimitRestBox.isVisible = showCreditLimit
        creditLimitRestBox.isManaged = showCreditLimit
        if (showCreditLimit) {
            val creditLimitRest = RawMoney(creditLimit.value + rest.value)
            creditLimitRestLabel.text = creditLimitRest.format(digits)
            creditLimitRestLabel.pseudoClassStateChanged(NEGATIVE, creditLimitRest.value < 0)
            creditLimitRestCurrencyLabel.text = currencyText
        }
    }

    private companion object {
        val NEGATIVE: PseudoClass = PseudoClass.getPseudoClass("negative")
    }
}
