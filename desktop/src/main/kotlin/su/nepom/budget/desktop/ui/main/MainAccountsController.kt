package su.nepom.budget.desktop.ui.main

import jakarta.inject.Inject
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.input.MouseButton
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

    private class Row(
        val account: AccountId,
        val name: String,
        val kind: String,
        val currency: String,
        val rest: String,
        val dailyBalance: String,
    )

    private val weakListeners = WeakListeners()
    private val rows = FXCollections.observableArrayList<Row>()

    private val refreshPause = PauseTransition(Duration.millis(200.0)).apply { setOnFinished { reload() } }

    @FXML private lateinit var accountsTable: TableView<Row>
    @FXML private lateinit var nameColumn: TableColumn<Row, String>
    @FXML private lateinit var kindColumn: TableColumn<Row, String>
    @FXML private lateinit var currencyColumn: TableColumn<Row, String>
    @FXML private lateinit var restColumn: TableColumn<Row, String>
    @FXML private lateinit var dailyBalanceColumn: TableColumn<Row, String>

    override fun initialize(location: URL?, resources: ResourceBundle?) {
        setupTable()

        weakListeners.addListenerAndCallNow(dbService.sessionProperty) { _, _, session ->
            if (session != null) {
                weakListeners.subscribe(session.db, Db.SubscribeKind.TRANSACTION, Db.SubscribeKind.ACCOUNT) {
                    Platform.runLater { refreshPause.playFromStart() }
                }
            }
            reload()
        }
        accountService.accounts.addListener(javafx.collections.ListChangeListener { refreshPause.playFromStart() })
    }

    private fun setupTable() {
        accountsTable.items = rows
        listOf(nameColumn, kindColumn, currencyColumn, restColumn, dailyBalanceColumn).forEach { it.isSortable = false }
        nameColumn.setCellValueFactory { SimpleStringProperty(it.value.name) }
        kindColumn.setCellValueFactory { SimpleStringProperty(it.value.kind) }
        currencyColumn.setCellValueFactory { SimpleStringProperty(it.value.currency) }
        restColumn.setCellValueFactory { SimpleStringProperty(it.value.rest) }
        dailyBalanceColumn.setCellValueFactory { SimpleStringProperty(it.value.dailyBalance) }

        accountsTable.setOnMouseClicked { event ->
            if (event.button == MouseButton.PRIMARY && event.clickCount == 2) {
                accountsTable.selectionModel.selectedItem?.let(::openTransactionsFor)
            }
        }
    }

    private fun openTransactionsFor(row: Row) {
        windowManager.openTransactions(TransactionController.InitialFilter(setOf(row.account), null, null))
    }

    private fun reload() {
        val session = dbService.session
        rows.clear()
        if (session == null) return

        val accounts = accountService.accounts
            .filter { it.content.showOnMain && !it.content.hidden }
            .sortedBy { it.content.name.lowercase() }
        if (accounts.isEmpty()) return

        val ids = accounts.mapTo(mutableSetOf()) { it.content.id }
        val restById = runAndShowError {
            session.transactionDao.accountRest(ids, null)
        }.getOrDefault(emptyMap())

        val today = LocalDate.now().toKotlinLocalDate()
        val budgetIds = accounts.filter { it.content.kind == AccountKind.BUDGET }
            .mapTo(mutableSetOf()) { it.content.id }
        val reservedSums = if (budgetIds.isEmpty()) emptyMap()
        else runAndShowError {
            session.transactionDao.sumReservedByAccount(budgetIds, today)
        }.getOrDefault(emptyMap())

        rows.setAll(accounts.map { account ->
            val id = account.content.id
            val currency = currencyService.currencies[account.content.currency.uuid]
            val rest = restById[id] ?: RawMoney.ZERO
            Row(
                account = id,
                name = account.content.name,
                kind = kindText(account.content.kind),
                currency = currency?.content?.name ?: "-",
                rest = formatMoney(rest, currency),
                dailyBalance = dailyBalanceText(account, rest, reservedSums[id], today, currency),
            )
        })
    }

    private fun dailyBalanceText(
        account: AccountObservable,
        rest: RawMoney,
        reserved: RawMoney?,
        today: kotlinx.datetime.LocalDate,
        currency: CurrencyObservable?,
    ): String {
        if (account.content.kind != AccountKind.BUDGET) return ""
        val reservedItems = if (reserved != null && reserved.value != 0L)
            listOf(ReservedAmount(reserved, today.plus(1, DateTimeUnit.DAY))) else emptyList()
        return calculateDailyBalance(rest, today, account.content.budget, reservedItems)
            ?.let { formatMoney(it, currency) } ?: ""
    }

    private fun formatMoney(raw: RawMoney, currency: CurrencyObservable?): String =
        raw.format(currency?.content?.digitsAfterPoint ?: 2)

    private fun kindText(kind: AccountKind): String = when (kind) {
        AccountKind.MONEY -> "Деньги"
        AccountKind.BUDGET -> "Бюджет"
    }
}
