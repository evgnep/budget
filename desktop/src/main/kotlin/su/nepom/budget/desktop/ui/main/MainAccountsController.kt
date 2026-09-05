package su.nepom.budget.desktop.ui.main

import jakarta.inject.Inject
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.collections.ListChangeListener
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.Cursor
import javafx.scene.control.Label
import javafx.scene.control.ProgressBar
import javafx.scene.input.MouseButton
import javafx.scene.layout.FlowPane
import javafx.scene.layout.VBox
import javafx.util.Duration
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlinx.datetime.toKotlinLocalDate
import su.nepom.budget.db.Db
import su.nepom.budget.desktop.model.AccountObservable
import su.nepom.budget.desktop.model.CurrencyObservable
import su.nepom.budget.desktop.service.AccountService
import su.nepom.budget.desktop.service.CurrencyService
import su.nepom.budget.desktop.service.DbService
import su.nepom.budget.desktop.ui.WindowManager
import su.nepom.budget.desktop.ui.transaction.TransactionController
import su.nepom.budget.desktop.util.fx.Controller
import su.nepom.budget.desktop.util.fx.WeakListeners
import su.nepom.budget.desktop.util.fx.runAndShowError
import su.nepom.budget.model.AccountId
import su.nepom.budget.model.AccountKind
import su.nepom.budget.model.RawMoney
import su.nepom.budget.utils.ReservedAmount
import su.nepom.budget.utils.calculateDailyBalance
import su.nepom.budget.utils.dailyAllowanceOn
import su.nepom.budget.utils.format
import java.net.URL
import java.time.LocalDate
import java.util.ResourceBundle

@Suppress("unused")
class MainAccountsController @Inject constructor(
    private val dbService: DbService,
    private val accountService: AccountService,
    private val currencyService: CurrencyService,
    private val windowManager: WindowManager,
) : Controller, Initializable {

    private val weakListeners = WeakListeners()
    private val refreshPause = PauseTransition(Duration.millis(200.0)).apply { setOnFinished { reload() } }
    private val midnightRefreshTimer = PauseTransition().apply {
        setOnFinished {
            reload()
            scheduleMidnightRefresh()
        }
    }

    @FXML private lateinit var totalsPane: FlowPane
    @FXML private lateinit var cardsPane: FlowPane
    @FXML private lateinit var emptyLabel: Label

    override fun initialize(location: URL?, resources: ResourceBundle?) {
        emptyLabel.isManaged = false
        emptyLabel.isVisible = false

        weakListeners.addListenerAndCallNow(dbService.sessionProperty) { _, _, session ->
            if (session != null) {
                weakListeners.subscribe(session.db, Db.SubscribeKind.TRANSACTION, Db.SubscribeKind.ACCOUNT) {
                    Platform.runLater { refreshPause.playFromStart() }
                }
            }
            reload()
        }
        accountService.accounts.addListener(ListChangeListener { refreshPause.playFromStart() })
        scheduleMidnightRefresh()
    }

    private fun scheduleMidnightRefresh() {
        val now = java.time.LocalDateTime.now()
        val nextRun = now.toLocalDate().plusDays(1).atTime(0, 1)
        val delay = java.time.Duration.between(now, nextRun)
        midnightRefreshTimer.duration = Duration.millis(delay.toMillis().toDouble())
        midnightRefreshTimer.playFromStart()
    }

    private fun reload() {
        val session = dbService.session
        cardsPane.children.clear()
        totalsPane.children.clear()

        val accounts = if (session == null) emptyList()
        else accountService.accounts
            .filter { it.content.showOnMain && !it.content.hidden }
            .sortedBy { it.content.name.lowercase() }

        showEmpty(accounts.isEmpty())
        if (session == null || accounts.isEmpty()) return

        val today = LocalDate.now().toKotlinLocalDate()
        val budgetIds = accounts.filter { it.content.kind == AccountKind.BUDGET }
            .mapTo(mutableSetOf()) { it.content.id }
        val reservedSums = if (budgetIds.isEmpty()) emptyMap()
        else runAndShowError {
            session.transactionDao.sumReservedByAccount(budgetIds, today)
        }.getOrDefault(emptyMap())

        accounts.forEach { account ->
            val id = account.content.id
            val currency = currencyService.currencies[account.content.currency.uuid]
            val rest = account.restProperty.get()
            val daily = if (account.content.kind == AccountKind.BUDGET)
                dailyBalanceOf(account, rest, reservedSums[id], today) else null
            val norm = if (account.content.kind == AccountKind.BUDGET)
                dailyAllowanceOn(today, account.content.budget) else null
            cardsPane.children.add(buildCard(account, rest, currency, daily, norm))
        }

        buildTotals(accounts)
    }

    private fun buildTotals(accounts: List<AccountObservable>) {
        accounts.groupBy { it.content.currency.uuid }
            .map { (uuid, group) ->
                val currency = currencyService.currencies[uuid]
                val sum = RawMoney(group.sumOf { it.restProperty.get().value })
                (currency?.content?.name ?: "-") to formatMoney(sum, currency)
            }
            .sortedBy { it.first.lowercase() }
            .forEach { (name, sum) ->
                totalsPane.children.add(Label("$name:  $sum").apply {
                    style = "-fx-font-weight: bold; -fx-font-size: 24px;"
                })
            }
    }

    private fun buildCard(
        account: AccountObservable,
        rest: RawMoney,
        currency: CurrencyObservable?,
        daily: RawMoney?,
        norm: RawMoney?,
    ): VBox {
        val name = Label(account.content.name).apply { style = "-fx-font-weight: bold; -fx-font-size: 26px;" }
        val balance = Label(formatMoney(rest, currency)).apply {
            style = "-fx-font-size: 38px;" + if (rest.value < 0) " -fx-text-fill: #c62828;" else ""
        }
        val sub = Label("${kindText(account.content.kind)} · ${currency?.content?.name ?: "-"}").apply {
            style = "-fx-text-fill: #757575; -fx-font-size: 20px;"
        }

        val card = VBox(8.0, name, balance, sub)
        card.minWidth = 380.0
        card.prefWidth = 380.0
        card.cursor = Cursor.HAND
        applyCardStyle(card, false)
        card.setOnMouseEntered { applyCardStyle(card, true) }
        card.setOnMouseExited { applyCardStyle(card, false) }
        card.setOnMouseClicked { event ->
            if (event.button == MouseButton.PRIMARY && event.clickCount == 1) openTransactionsFor(account.content.id)
        }

        if (daily != null) {
            card.children.add(Label("на день:  ${formatMoney(daily, currency)}").apply {
                style = "-fx-font-size: 22px;" + if (daily.value < 0) " -fx-text-fill: #c62828;" else ""
            })
            if (norm != null && norm.value > 0) {
                val ratio = daily.value.toDouble() / norm.value.toDouble()
                card.children.add(ProgressBar(ratio.coerceIn(0.0, 1.0)).apply {
                    maxWidth = Double.MAX_VALUE
                    minHeight = 16.0
                    style = "-fx-accent: ${progressColor(ratio)};"
                })
            }
        }
        return card
    }

    // -fx-control-inner-background / -fx-box-border / -fx-accent are Modena-only variables -
    // AtlantaFX (PrimerLight) leaves them unresolved, which threw a ClassCastException on every
    // style pass. Use AtlantaFX's own tokens instead, which always resolve to a real Paint.
    private fun applyCardStyle(card: VBox, hover: Boolean) {
        val border = if (hover) "-color-accent-emphasis" else "-color-border-default"
        card.style = """
            -fx-background-color: -color-bg-default;
            -fx-border-color: $border;
            -fx-border-radius: 8; -fx-background-radius: 8;
            -fx-padding: 18;
        """.trimIndent()
    }

    private fun progressColor(ratio: Double): String = when {
        ratio >= 1.0 -> "#2e7d32"
        ratio >= 0.0 -> "#ef6c00"
        else -> "#c62828"
    }

    private fun dailyBalanceOf(
        account: AccountObservable,
        rest: RawMoney,
        reserved: RawMoney?,
        today: kotlinx.datetime.LocalDate,
    ): RawMoney? {
        val reservedItems = if (reserved != null && reserved.value != 0L)
            listOf(ReservedAmount(reserved, today.plus(1, DateTimeUnit.DAY))) else emptyList()
        return calculateDailyBalance(rest, today, account.content.budget, reservedItems)
    }

    private fun openTransactionsFor(id: AccountId) {
        windowManager.openTransactions(TransactionController.InitialFilter(setOf(id), null, null))
    }

    private fun showEmpty(empty: Boolean) {
        emptyLabel.isManaged = empty
        emptyLabel.isVisible = empty
    }

    private fun formatMoney(raw: RawMoney, currency: CurrencyObservable?): String =
        raw.format(currency?.content?.digitsAfterPoint ?: 2)

    private fun kindText(kind: AccountKind): String = when (kind) {
        AccountKind.MONEY -> "Деньги"
        AccountKind.BUDGET -> "Бюджет"
    }
}
