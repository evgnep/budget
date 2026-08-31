package su.nepom.budget.desktop.ui.balance

import jakarta.inject.Inject
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.collections.transformation.FilteredList
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.DatePicker
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.TextField
import javafx.scene.input.MouseButton
import javafx.util.Duration
import javafx.util.StringConverter
import kotlinx.datetime.Instant
import su.nepom.budget.db.Db
import su.nepom.budget.desktop.model.AccountObservable
import su.nepom.budget.desktop.model.CurrencyObservable
import su.nepom.budget.desktop.service.AccountService
import su.nepom.budget.desktop.service.CurrencyService
import su.nepom.budget.desktop.service.DbService
import su.nepom.budget.utils.format
import su.nepom.budget.desktop.util.fx.Controller
import su.nepom.budget.desktop.util.fx.Disposable
import su.nepom.budget.desktop.util.fx.WeakListeners
import su.nepom.budget.desktop.util.fx.runAndShowError
import su.nepom.budget.desktop.ui.WindowManager
import su.nepom.budget.desktop.ui.transaction.TransactionController
import su.nepom.budget.desktop.util.toEndOfDayInstant
import su.nepom.budget.desktop.util.toStartOfDayInstant
import su.nepom.budget.model.AccountId
import su.nepom.budget.model.AccountKind
import su.nepom.budget.model.RawMoney
import su.nepom.budget.model.RawTurnover
import java.net.URL
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.ResourceBundle

@Suppress("unused")
class BalanceController @Inject constructor(
    private val dbService: DbService,
    private val accountService: AccountService,
    private val currencyService: CurrencyService,
    private val windowManager: WindowManager,
) : Controller, Initializable, Disposable {

    private enum class DateRangePreset(val label: String) {
        ALL("За все время"),
        TODAY("Сегодня"),
        LAST_7_DAYS("За 7 дней"),
        THIS_WEEK("За неделю"),
        THIS_MONTH("За месяц"),
        CUSTOM("По выбору");

        override fun toString() = label
    }

    private class BalanceRow(
        val accounts: Set<AccountId>,
        val name: String,
        val kind: String,
        val currency: String,
        val start: String,
        val income: String,
        val expense: String,
        val end: String,
    )

    private val weakListeners = WeakListeners()
    private val rows = FXCollections.observableArrayList<BalanceRow>()
    private val visibleCurrencies = FilteredList(currencyService.currencies) { !it.content.hidden }

    private val refreshPause = PauseTransition(Duration.millis(200.0)).apply { setOnFinished { reload() } }

    // period
    @FXML private lateinit var dateRangeComboBox: ComboBox<DateRangePreset>
    @FXML private lateinit var customDateBox: javafx.scene.layout.HBox
    @FXML private lateinit var fromDatePicker: DatePicker
    @FXML private lateinit var toDatePicker: DatePicker

    // filters
    @FXML private lateinit var nameFilterTextField: TextField
    @FXML private lateinit var currencyFilterComboBox: ComboBox<CurrencyObservable>
    @FXML private lateinit var tagFilterComboBox: ComboBox<String>
    @FXML private lateinit var showHiddenCheckbox: CheckBox
    @FXML private lateinit var resetFilterButton: Button

    // list
    @FXML private lateinit var balancesTable: TableView<BalanceRow>
    @FXML private lateinit var nameColumn: TableColumn<BalanceRow, String>
    @FXML private lateinit var kindColumn: TableColumn<BalanceRow, String>
    @FXML private lateinit var currencyColumn: TableColumn<BalanceRow, String>
    @FXML private lateinit var startColumn: TableColumn<BalanceRow, String>
    @FXML private lateinit var incomeColumn: TableColumn<BalanceRow, String>
    @FXML private lateinit var expenseColumn: TableColumn<BalanceRow, String>
    @FXML private lateinit var endColumn: TableColumn<BalanceRow, String>

    private val currencyConverter = object : StringConverter<CurrencyObservable>() {
        override fun toString(currency: CurrencyObservable?) = currency?.content?.name ?: ""
        override fun fromString(string: String?): CurrencyObservable? = null
    }

    override fun initialize(location: URL?, resources: ResourceBundle?) {
        setupPeriodPanel()
        setupFilterPanel()
        setupTable()

        weakListeners.addListenerAndCallNow(dbService.sessionProperty) { _, _, session ->
            if (session != null) {
                weakListeners.subscribe(session.db, Db.SubscribeKind.TRANSACTION, Db.SubscribeKind.ACCOUNT) {
                    Platform.runLater { refreshPause.playFromStart() }
                }
            }
            reload()
        }
    }

    override fun dispose() {
        weakListeners.dispose()
        refreshPause.stop()
    }

    private fun setupPeriodPanel() {
        dateRangeComboBox.items.setAll(*DateRangePreset.entries.toTypedArray())
        dateRangeComboBox.selectionModel.select(DateRangePreset.CUSTOM)
        updateCustomDateVisibility()
        dateRangeComboBox.valueProperty().addListener { _, _, _ ->
            updateCustomDateVisibility()
            reload()
        }
        fromDatePicker.valueProperty().addListener { _, _, _ -> reload() }
        toDatePicker.valueProperty().addListener { _, _, _ -> reload() }
    }

    private fun setupFilterPanel() {
        currencyFilterComboBox.items = visibleCurrencies
        currencyFilterComboBox.converter = currencyConverter
        tagFilterComboBox.items = accountService.tags

        nameFilterTextField.textProperty().addListener { _, _, _ -> refreshPause.playFromStart() }
        currencyFilterComboBox.valueProperty().addListener { _, _, _ -> reload() }
        tagFilterComboBox.valueProperty().addListener { _, _, _ -> reload() }
        showHiddenCheckbox.selectedProperty().addListener { _, _, _ ->
            visibleCurrencies.setPredicate { showHiddenCheckbox.isSelected || !it.content.hidden }
            reload()
        }
        resetFilterButton.setOnAction {
            nameFilterTextField.clear()
            currencyFilterComboBox.value = null
            tagFilterComboBox.value = null
        }
    }

    private fun setupTable() {
        balancesTable.items = rows
        listOf(nameColumn, kindColumn, currencyColumn, startColumn, incomeColumn, expenseColumn, endColumn)
            .forEach { it.isSortable = false }
        nameColumn.setCellValueFactory { SimpleStringProperty(it.value.name) }
        kindColumn.setCellValueFactory { SimpleStringProperty(it.value.kind) }
        currencyColumn.setCellValueFactory { SimpleStringProperty(it.value.currency) }
        startColumn.setCellValueFactory { SimpleStringProperty(it.value.start) }
        incomeColumn.setCellValueFactory { SimpleStringProperty(it.value.income) }
        expenseColumn.setCellValueFactory { SimpleStringProperty(it.value.expense) }
        endColumn.setCellValueFactory { SimpleStringProperty(it.value.end) }

        balancesTable.setOnMouseClicked { event ->
            if (event.button == MouseButton.PRIMARY && event.clickCount == 2) {
                balancesTable.selectionModel.selectedItem?.let(::openTransactionsFor)
            }
        }
    }

    private fun openTransactionsFor(row: BalanceRow) {
        if (row.accounts.isEmpty()) return
        val (from, to) = currentDateRange()
        windowManager.openTransactions(
            TransactionController.InitialFilter(row.accounts, from, to)
        )
    }

    private fun updateCustomDateVisibility() {
        val custom = dateRangeComboBox.value == DateRangePreset.CUSTOM
        customDateBox.isVisible = custom
        customDateBox.isManaged = custom
    }

    private fun currentDateRange(): Pair<LocalDate?, LocalDate?> {
        val today = LocalDate.now()
        return when (dateRangeComboBox.value) {
            DateRangePreset.ALL -> null to null
            DateRangePreset.TODAY -> today to today
            DateRangePreset.LAST_7_DAYS -> today.minusDays(6) to today
            DateRangePreset.THIS_WEEK ->
                today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) to
                    today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
            DateRangePreset.THIS_MONTH ->
                today.withDayOfMonth(1) to today.withDayOfMonth(today.lengthOfMonth())
            DateRangePreset.CUSTOM, null -> fromDatePicker.value to toDatePicker.value
        }
    }

    private fun visibleAccounts(): List<AccountObservable> {
        val nameFilter = nameFilterTextField.text.trim().lowercase()
        val currencyFilter = currencyFilterComboBox.value?.uuid
        val tagFilter = tagFilterComboBox.value
        val showHidden = showHiddenCheckbox.isSelected
        return accountService.accounts
            .filter { account ->
                (showHidden || !account.content.hidden) &&
                    (nameFilter.isEmpty() || account.content.name.lowercase().contains(nameFilter)) &&
                    (currencyFilter == null || account.content.currency.uuid == currencyFilter) &&
                    (tagFilter == null || tagFilter in account.content.tags)
            }
            .sortedBy { it.content.name.lowercase() }
    }

    private fun reload() {
        val session = dbService.session
        rows.clear()
        if (session == null) return

        val accounts = visibleAccounts()
        if (accounts.isEmpty()) return

        val ids = accounts.mapTo(mutableSetOf()) { it.content.id }
        val (fromDate, toDate) = currentDateRange()
        val range = turnoverRange(fromDate, toDate)
        val forDate = toDate?.toEndOfDayInstant()

        val endRest = runAndShowError {
            session.transactionDao.accountRest(ids, forDate)
        }.getOrDefault(emptyMap())
        val turnover = runAndShowError {
            session.transactionDao.accountTurnover(ids, range)
        }.getOrDefault(emptyMap())

        val accountRows = accounts.map { account ->
            val id = account.content.id
            val currency = currencyService.currencies[account.content.currency.uuid]
            makeRow(
                accounts = setOf(id),
                name = account.content.name,
                kind = kindText(account.content.kind),
                currencyName = currency?.content?.name ?: "-",
                currency = currency,
                end = endRest[id] ?: RawMoney.ZERO,
                turnover = turnover[id] ?: RawTurnover(RawMoney.ZERO, RawMoney.ZERO),
            )
        }

        // one row per currency used by the filtered accounts, added before the account rows
        val currencyIds = accounts.mapTo(mutableSetOf()) { it.content.currency }
        val accountsByCurrency = accounts.groupBy { it.content.currency }
        val currencyEndRest = runAndShowError {
            session.transactionDao.currencyRest(currencyIds, forDate)
        }.getOrDefault(emptyMap())
        val currencyTurnover = runAndShowError {
            session.transactionDao.currencyTurnover(currencyIds, range)
        }.getOrDefault(emptyMap())

        val currencyRows = currencyIds
            .map { currencyService.currencies[it.uuid] to it }
            .sortedBy { (currency, _) -> currency?.content?.name?.lowercase() ?: "" }
            .map { (currency, currencyId) ->
                makeRow(
                    accounts = accountsByCurrency[currencyId].orEmpty().mapTo(mutableSetOf()) { it.content.id },
                    name = currency?.content?.name ?: "-",
                    kind = "Валюта",
                    currencyName = "",
                    currency = currency,
                    end = currencyEndRest[currencyId] ?: RawMoney.ZERO,
                    turnover = currencyTurnover[currencyId] ?: RawTurnover(RawMoney.ZERO, RawMoney.ZERO),
                )
            }

        rows.setAll(currencyRows + accountRows)
    }

    private fun makeRow(
        accounts: Set<AccountId>,
        name: String,
        kind: String,
        currencyName: String,
        currency: CurrencyObservable?,
        end: RawMoney,
        turnover: RawTurnover,
    ): BalanceRow {
        // turnover.income is positive, turnover.expenditure is negative
        val start = RawMoney(end.value - turnover.income.value - turnover.expenditure.value)
        return BalanceRow(
            accounts = accounts,
            name = name,
            kind = kind,
            currency = currencyName,
            start = formatMoney(start, currency),
            income = formatMoney(turnover.income, currency),
            expense = formatMoney(RawMoney(-turnover.expenditure.value), currency),
            end = formatMoney(end, currency),
        )
    }

    private fun turnoverRange(from: LocalDate?, to: LocalDate?): ClosedRange<Instant>? {
        if (from == null && to == null) return null
        val start = from?.toStartOfDayInstant() ?: Instant.DISTANT_PAST
        val end = to?.toEndOfDayInstant() ?: Instant.DISTANT_FUTURE
        return start..end
    }

    private fun formatMoney(raw: RawMoney, currency: CurrencyObservable?): String =
        raw.format(currency?.content?.digitsAfterPoint ?: 2)

    private fun kindText(kind: AccountKind): String = when (kind) {
        AccountKind.MONEY -> "Деньги"
        AccountKind.BUDGET -> "Бюджет"
    }
}
