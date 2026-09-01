package su.nepom.budget.desktop.ui.balance

import jakarta.inject.Inject
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.collections.transformation.FilteredList
import javafx.css.PseudoClass
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.DatePicker
import javafx.scene.control.TextField
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeTableColumn
import javafx.scene.control.TreeTableRow
import javafx.scene.control.TreeTableView
import javafx.scene.input.MouseButton
import javafx.util.Duration
import javafx.util.StringConverter
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.plus
import kotlinx.datetime.toKotlinLocalDate
import su.nepom.budget.db.Db
import su.nepom.budget.desktop.model.AccountObservable
import su.nepom.budget.desktop.model.CurrencyObservable
import su.nepom.budget.desktop.service.AccountService
import su.nepom.budget.desktop.service.CurrencyService
import su.nepom.budget.desktop.service.DbService
import su.nepom.budget.utils.ReservedAmount
import su.nepom.budget.utils.calculateDailyBalance
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
        val dailyBalance: String,
        val isGroup: Boolean = false,
    )

    private val weakListeners = WeakListeners()
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
    @FXML private lateinit var balancesTable: TreeTableView<BalanceRow>
    @FXML private lateinit var nameColumn: TreeTableColumn<BalanceRow, String>
    @FXML private lateinit var kindColumn: TreeTableColumn<BalanceRow, String>
    @FXML private lateinit var currencyColumn: TreeTableColumn<BalanceRow, String>
    @FXML private lateinit var startColumn: TreeTableColumn<BalanceRow, String>
    @FXML private lateinit var incomeColumn: TreeTableColumn<BalanceRow, String>
    @FXML private lateinit var expenseColumn: TreeTableColumn<BalanceRow, String>
    @FXML private lateinit var endColumn: TreeTableColumn<BalanceRow, String>
    @FXML private lateinit var dailyBalanceColumn: TreeTableColumn<BalanceRow, String>

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
        balancesTable.isShowRoot = false
        balancesTable.root = TreeItem(groupRow("", emptySet()))
        listOf(nameColumn, kindColumn, currencyColumn, startColumn, incomeColumn, expenseColumn, endColumn,
            dailyBalanceColumn)
            .forEach { it.isSortable = false }
        nameColumn.setCellValueFactory { SimpleStringProperty(it.value.value.name) }
        kindColumn.setCellValueFactory { SimpleStringProperty(it.value.value.kind) }
        currencyColumn.setCellValueFactory { SimpleStringProperty(it.value.value.currency) }
        startColumn.setCellValueFactory { SimpleStringProperty(it.value.value.start) }
        incomeColumn.setCellValueFactory { SimpleStringProperty(it.value.value.income) }
        expenseColumn.setCellValueFactory { SimpleStringProperty(it.value.value.expense) }
        endColumn.setCellValueFactory { SimpleStringProperty(it.value.value.end) }
        dailyBalanceColumn.setCellValueFactory { SimpleStringProperty(it.value.value.dailyBalance) }

        balancesTable.setRowFactory {
            object : TreeTableRow<BalanceRow>() {
                override fun updateItem(item: BalanceRow?, empty: Boolean) {
                    super.updateItem(item, empty)
                    pseudoClassStateChanged(GROUP_ROW, !empty && item?.isGroup == true)
                }
            }
        }

        balancesTable.setOnMouseClicked { event ->
            if (event.button == MouseButton.PRIMARY && event.clickCount == 2) {
                balancesTable.selectionModel.selectedItem?.value?.let(::openTransactionsFor)
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
        balancesTable.root.children.clear()
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

        val today = LocalDate.now().toKotlinLocalDate()
        val budgetIds = accounts.filter { it.content.kind == AccountKind.BUDGET }
            .mapTo(mutableSetOf()) { it.content.id }
        val reservedSums = if (budgetIds.isEmpty()) emptyMap()
        else runAndShowError {
            session.transactionDao.sumReservedByAccount(budgetIds, today)
        }.getOrDefault(emptyMap())

        val accountRows = accounts.map { account ->
            val id = account.content.id
            val currency = currencyService.currencies[account.content.currency.uuid]
            val end = endRest[id] ?: RawMoney.ZERO
            account to makeRow(
                accounts = setOf(id),
                name = account.content.name,
                kind = kindText(account.content.kind),
                currencyName = currency?.content?.name ?: "-",
                currency = currency,
                end = end,
                turnover = turnover[id] ?: RawTurnover(RawMoney.ZERO, RawMoney.ZERO),
                dailyBalance = dailyBalanceText(account, end, reservedSums[id], today, currency),
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

        val root = balancesTable.root
        currencyRows.forEach { root.children.add(TreeItem(it)) }
        buildGroupTree(accountRows).forEach { root.children.add(it) }
    }

    // build the account rows into a tree of groups (see the "Остатки" grouping rules)
    private fun buildGroupTree(accountRows: List<Pair<AccountObservable, BalanceRow>>): List<TreeItem<BalanceRow>> {
        val root = GroupNode("")
        for ((account, row) in accountRows) {
            val path = account.content.groupPath.ifEmpty { listOf(OTHER_GROUP) }
            var node = root
            for (name in path) node = node.children.getOrPut(name) { GroupNode(name) }
            node.accounts += account.content.orderNo to row
        }
        return root.sortedChildren().map { it.toTreeItem() }
    }

    private class GroupNode(val name: String) {
        val children = linkedMapOf<String, GroupNode>()
        val accounts = mutableListOf<Pair<Int, BalanceRow>>() // account orderNo to its row

        // "Прочие" always last; then groups by lowest account orderNo, then by name
        fun sortedChildren(): List<GroupNode> = children.values.sortedWith(
            compareBy({ it.name == OTHER_GROUP }, { it.minOrderNo() }, { it.name.lowercase() })
        )

        private fun minOrderNo(): Int =
            (accounts.map { it.first } + children.values.map { it.minOrderNo() }).minOrNull() ?: Int.MAX_VALUE

        private fun allAccounts(): Set<AccountId> =
            accounts.flatMapTo(mutableSetOf()) { it.second.accounts } +
                children.values.flatMap { it.allAccounts() }

        fun toTreeItem(): TreeItem<BalanceRow> {
            val item = TreeItem(groupRow(name, allAccounts()))
            sortedChildren().forEach { item.children.add(it.toTreeItem()) }
            accounts.forEach { item.children.add(TreeItem(it.second)) }
            item.isExpanded = true
            return item
        }
    }

    private fun makeRow(
        accounts: Set<AccountId>,
        name: String,
        kind: String,
        currencyName: String,
        currency: CurrencyObservable?,
        end: RawMoney,
        turnover: RawTurnover,
        dailyBalance: String = "",
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
            dailyBalance = dailyBalance,
        )
    }

    // "Остаток на день" - only for BUDGET accounts that have a replenish day set
    private fun dailyBalanceText(
        account: AccountObservable,
        end: RawMoney,
        reserved: RawMoney?,
        today: kotlinx.datetime.LocalDate,
        currency: CurrencyObservable?,
    ): String {
        if (account.content.kind != AccountKind.BUDGET) return ""
        val reservedItems = if (reserved != null && reserved.value != 0L)
            listOf(ReservedAmount(reserved, today.plus(1, DateTimeUnit.DAY))) else emptyList()
        return calculateDailyBalance(end, today, account.content.budget, reservedItems)
            ?.let { formatMoney(it, currency) } ?: ""
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

    companion object {
        private const val OTHER_GROUP = "Прочие"
        private val GROUP_ROW: PseudoClass = PseudoClass.getPseudoClass("group-row")

        private fun groupRow(name: String, accounts: Set<AccountId>) = BalanceRow(
            accounts = accounts,
            name = name,
            kind = "",
            currency = "",
            start = "",
            income = "",
            expense = "",
            end = "",
            dailyBalance = "",
            isGroup = true,
        )
    }
}
