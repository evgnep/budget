package su.nepom.budget.desktop.ui.transaction

import jakarta.inject.Inject
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.DatePicker
import javafx.scene.control.Label
import javafx.scene.control.SelectionMode
import javafx.scene.control.TableCell
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.TextField
import javafx.scene.control.cell.CheckBoxTableCell
import javafx.scene.layout.HBox
import javafx.stage.Stage
import javafx.util.Duration
import su.nepom.budget.db.Db
import su.nepom.budget.db.dao.TransactionDao
import su.nepom.budget.desktop.model.TransactionObservable
import su.nepom.budget.desktop.service.AccountService
import su.nepom.budget.desktop.service.CurrencyService
import su.nepom.budget.desktop.service.DbService
import su.nepom.budget.desktop.ui.history.History
import su.nepom.budget.desktop.util.formatDateTime
import su.nepom.budget.desktop.util.fx.Controller
import su.nepom.budget.desktop.util.fx.Disposable
import su.nepom.budget.desktop.util.fx.FormState
import su.nepom.budget.desktop.util.fx.MasterDetailFormDriver
import su.nepom.budget.desktop.util.fx.StageAwareController
import su.nepom.budget.desktop.util.fx.WeakListeners
import su.nepom.budget.desktop.util.fx.enableCopySelectionToClipboard
import su.nepom.budget.desktop.util.fx.runAndShowError
import su.nepom.budget.desktop.util.fx.setClipboardValue
import su.nepom.budget.desktop.util.toEndOfDayInstant
import su.nepom.budget.desktop.util.toStartOfDayInstant
import su.nepom.budget.event.TransactionContent
import su.nepom.budget.event.TransactionContextItemAndTransaction
import su.nepom.budget.model.AccountId
import su.nepom.budget.model.CurrencyId
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.OperationType
import su.nepom.budget.model.RawMoney
import su.nepom.budget.model.Uuid
import su.nepom.budget.utils.format
import su.nepom.budget.utils.toBigDecimal
import java.text.DecimalFormatSymbols
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil

@Suppress("unused", "UNCHECKED_CAST")
class TransactionController @Inject constructor(
    private val dbService: DbService,
    private val accountService: AccountService,
    private val currencyService: CurrencyService,
    private val accountPicker: AccountPicker,
    private val history: History,
) : Controller, StageAwareController, Disposable {

    private companion object {
        const val PAGE_SIZE = 100
    }

    /** Filter to apply once when the window opens (e.g. from the balances window). */
    class InitialFilter(val accounts: Set<AccountId>, val from: LocalDate?, val to: LocalDate?)

    private class TriState(val label: String, val value: Boolean?) {
        override fun toString() = label
    }

    private class SortOption(val label: String, val ascending: Boolean) {
        override fun toString() = label
    }

    private enum class DateRangePreset(val label: String) {
        ALL("За все время"),
        TODAY("Сегодня"),
        LAST_7_DAYS("За 7 дней"),
        THIS_WEEK("За неделю"),
        THIS_MONTH("За месяц"),
        CUSTOM("По выбору");

        override fun toString() = label
    }

    private lateinit var stage: Stage

    private var initialFilter: InitialFilter? = null

    private val weakListeners = WeakListeners()
    private val rows = FXCollections.observableArrayList<TransactionContextItemAndTransaction>()
    private val selectedAccounts = mutableListOf<AccountId>()

    private var pageIndex = 0
    private var pageCount = 1
    private var totalCount = 0

    private val refreshPause = PauseTransition(Duration.millis(200.0)).apply {
        setOnFinished { reload(resetPage = false) }
    }
    private val descriptionPause = PauseTransition(Duration.millis(300.0)).apply {
        setOnFinished { userReload(resetPage = true) }
    }

    private lateinit var masterDetailFormDriver: MasterDetailFormDriver<TransactionContextItemAndTransaction, TransactionObservable>

    @FXML private lateinit var transactionDetailController: TransactionDetailController

    // filter panel
    @FXML private lateinit var dateRangeComboBox: ComboBox<DateRangePreset>
    @FXML private lateinit var customDateBox: HBox
    @FXML private lateinit var fromDatePicker: DatePicker
    @FXML private lateinit var toDatePicker: DatePicker
    @FXML private lateinit var pickAccountsButton: Button
    @FXML private lateinit var accountsSummaryLabel: Label
    @FXML private lateinit var deletedComboBox: ComboBox<TriState>
    @FXML private lateinit var descriptionFilterField: TextField
    @FXML private lateinit var flagComboBox: ComboBox<TriState>
    @FXML private lateinit var sortComboBox: ComboBox<SortOption>
    @FXML private lateinit var resetFilterButton: Button
    @FXML private lateinit var applyFilterButton: Button
    @FXML private lateinit var historyButton: Button
    @FXML private lateinit var allowEditCheckbox: CheckBox
    @FXML private lateinit var newButton: Button

    // pager
    @FXML private lateinit var prevPageButton: Button
    @FXML private lateinit var nextPageButton: Button
    @FXML private lateinit var pageField: TextField
    @FXML private lateinit var pageLabel: Label

    // list
    @FXML private lateinit var transactionsTable: TableView<TransactionContextItemAndTransaction>
    @FXML private lateinit var dateColumn: TableColumn<TransactionContextItemAndTransaction, String>
    @FXML private lateinit var typeColumn: TableColumn<TransactionContextItemAndTransaction, String>
    @FXML private lateinit var accountColumn: TableColumn<TransactionContextItemAndTransaction, String>
    @FXML private lateinit var amountColumn: TableColumn<TransactionContextItemAndTransaction, String>
    @FXML private lateinit var currencyColumn: TableColumn<TransactionContextItemAndTransaction, String>
    @FXML private lateinit var descriptionColumn: TableColumn<TransactionContextItemAndTransaction, String>
    @FXML private lateinit var flagColumn: TableColumn<TransactionContextItemAndTransaction, Boolean>
    @FXML private lateinit var deletedColumn: TableColumn<TransactionContextItemAndTransaction, Boolean>

    override fun initialize(stage: Stage) {
        this.stage = stage

        setupFilterPanel()
        setupListTable()
        wireDetail()
        applyInitialFilter()

        weakListeners.addListenerAndCallNow(dbService.sessionProperty) { _, _, session ->
            if (session != null) {
                weakListeners.subscribe(session.db, Db.SubscribeKind.TRANSACTION) {
                    Platform.runLater { scheduleRefresh() }
                }
            }
            reload(resetPage = true)
        }
    }

    override fun dispose() {
        weakListeners.dispose()
        refreshPause.stop()
        descriptionPause.stop()
    }

    private fun wireDetail() {
        transactionDetailController.setStage(stage)
        transactionDetailController.onSaved = { savedUuid -> reload(resetPage = false, preferUuid = savedUuid) }
        transactionDetailController.masterSelection =
            { transactionsTable.selectionModel.selectedItem?.let { TransactionObservable(it.transaction) } }
        masterDetailFormDriver = MasterDetailFormDriver(
            transactionsTable.selectionModel,
            transactionDetailController.formDriver,
            newButton,
            toDetail = { row -> TransactionObservable(row.transaction) },
            sameDetail = { a, b -> a.transaction.id == b.transaction.id },
            onDetailChanged = { detail -> transactionDetailController.onMasterSelectionChanged(detail) },
        )
        newButton.addEventHandler(ActionEvent.ACTION) {
            val single = selectedAccounts.singleOrNull()?.let { accountService.accounts[it.uuid] }
            transactionDetailController.onNewStarted(single)
        }

        allowEditCheckbox.selectedProperty().addListener { _, _, on ->
            transactionDetailController.setEditingAllowed(on)
        }
        transactionDetailController.setEditingAllowed(allowEditCheckbox.isSelected)

        historyButton.disableProperty()
            .bind(transactionsTable.selectionModel.selectedItemProperty().isNull)
        historyButton.setOnAction {
            val selected = transactionsTable.selectionModel.selectedItem ?: return@setOnAction
            history.show(selected.transaction.id, ObjectKind.TRANSACTION, "История операции")
        }
    }

    private fun setupFilterPanel() {
        deletedComboBox.items.setAll(
            TriState("Активные", false),
            TriState("Удалённые", true),
            TriState("Все", null),
        )
        deletedComboBox.selectionModel.select(0)
        flagComboBox.items.setAll(
            TriState("Все", null),
            TriState("С флагом", true),
            TriState("Без флага", false),
        )
        flagComboBox.selectionModel.select(0)
        sortComboBox.items.setAll(
            SortOption("Сначала новые", false),
            SortOption("Сначала старые", true),
        )
        sortComboBox.selectionModel.select(0)
        dateRangeComboBox.items.setAll(*DateRangePreset.entries.toTypedArray())
        dateRangeComboBox.selectionModel.select(DateRangePreset.CUSTOM)
        updateCustomDateVisibility()

        dateRangeComboBox.valueProperty().addListener { _, _, _ ->
            updateCustomDateVisibility()
            userReload(resetPage = true)
        }
        fromDatePicker.valueProperty().addListener { _, _, _ -> userReload(resetPage = true) }
        toDatePicker.valueProperty().addListener { _, _, _ -> userReload(resetPage = true) }
        deletedComboBox.valueProperty().addListener { _, _, _ -> userReload(resetPage = true) }
        flagComboBox.valueProperty().addListener { _, _, _ -> userReload(resetPage = true) }
        sortComboBox.valueProperty().addListener { _, _, _ -> userReload(resetPage = true) }
        descriptionFilterField.textProperty().addListener { _, _, _ -> descriptionPause.playFromStart() }

        pickAccountsButton.setOnAction { pickFilterAccounts() }
        resetFilterButton.setOnAction { resetFilter() }
        applyFilterButton.setOnAction { userReload(resetPage = true) }
        updateAccountsSummary()

        prevPageButton.setOnAction { goToPage(pageIndex - 1) }
        nextPageButton.setOnAction { goToPage(pageIndex + 1) }
        pageField.setOnAction { jumpToTypedPage() }
        pageField.focusedProperty().addListener { _, _, focused -> if (!focused) jumpToTypedPage() }
    }

    private fun jumpToTypedPage() {
        val typed = pageField.text.trim().toIntOrNull()
        if (typed != null) goToPage(typed.coerceIn(1, pageCount) - 1)
        pageField.text = (pageIndex + 1).toString()
    }

    private fun setupListTable() {
        transactionsTable.items = rows
        transactionsTable.selectionModel.selectionMode = SelectionMode.MULTIPLE
        transactionsTable.enableCopySelectionToClipboard()
        listOf(dateColumn, typeColumn, accountColumn, amountColumn, currencyColumn, descriptionColumn, flagColumn, deletedColumn)
            .forEach { it.isSortable = false }
        currencyColumn.text = "Валюта"
        // date / type / description are transaction-level - shown only on the first row of a group,
        // so a multi-leg operation doesn't repeat them on every row
        dateColumn.setCellValueFactory {
            SimpleStringProperty(if (it.value.isFirstInGroup) it.value.transaction.date.formatDateTime() else "")
        }
        typeColumn.setCellValueFactory {
            SimpleStringProperty(if (it.value.isFirstInGroup) operationTypeLabel(operationType(it.value.transaction)) else "")
        }
        typeColumn.setCellFactory {
            object : TableCell<TransactionContextItemAndTransaction, String>() {
                override fun updateItem(item: String?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty) null else item
                    val row = tableRow?.item
                    val color = if (empty || row == null) null else operationTypeColor(operationType(row.transaction))
                    style = if (color == null) "" else "-fx-background-color: $color;"
                }
            }
        }
        accountColumn.setCellValueFactory {
            SimpleStringProperty(accountValue(it.value))
        }
        amountColumn.setCellValueFactory {
            SimpleStringProperty(formatMoney(it.value.item.money, accountService.accounts[it.value.item.account.uuid]?.content?.currency))
        }
        amountColumn.setCellFactory {
            object : TableCell<TransactionContextItemAndTransaction, String>() {
                init { alignment = Pos.CENTER_RIGHT }
                override fun updateItem(item: String?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty) null else item
                }
            }
        }
        amountColumn.setClipboardValue { row ->
            formatMoneyForClipboard(row.item.money, accountService.accounts[row.item.account.uuid]?.content?.currency)
        }
        currencyColumn.setCellValueFactory {
            SimpleStringProperty(currencyValue(accountService.accounts[it.value.item.account.uuid]?.content?.currency))
        }
        descriptionColumn.setCellValueFactory {
            SimpleStringProperty(descriptionValue(it.value))
        }
        flagColumn.setCellValueFactory { SimpleBooleanProperty(it.value.transaction.flag) }
        flagColumn.cellFactory = CheckBoxTableCell.forTableColumn(flagColumn)
        flagColumn.setClipboardValue { row -> if (row.transaction.flag) "Да" else "Нет" }
        deletedColumn.setCellValueFactory { SimpleBooleanProperty(it.value.transaction.deleted) }
        deletedColumn.cellFactory = CheckBoxTableCell.forTableColumn(deletedColumn)
        deletedColumn.setClipboardValue { row -> if (row.transaction.deleted) "Да" else "Нет" }
    }

    // no grouping separator and the system decimal separator, so Excel / Google Sheets parse the
    // pasted value as a number instead of text
    private fun formatMoneyForClipboard(raw: RawMoney, currencyId: CurrencyId?): String {
        val cur = currencyId?.let { currencyService.currencies[it.uuid] }?.content
        val digits = cur?.digitsAfterPoint ?: 2
        val decimalSeparator = DecimalFormatSymbols.getInstance().decimalSeparator
        return raw.toBigDecimal(digits).toPlainString().replace('.', decimalSeparator)
    }

    // --- filter / paging ---

    private fun currentFilter(): TransactionDao.Filter {
        val (fromDate, toDate) = currentDateRange()
        return TransactionDao.Filter(
            from = fromDate?.toStartOfDayInstant(),
            to = toDate?.toEndOfDayInstant(),
            accounts = selectedAccounts.toSet(),
            deleted = deletedComboBox.value?.value,
            descriptionLike = descriptionFilterField.text.trim().takeIf { it.isNotEmpty() }?.let { "%$it%" },
            flag = flagComboBox.value?.value,
        )
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

    private fun updateCustomDateVisibility() {
        val custom = dateRangeComboBox.value == DateRangePreset.CUSTOM
        customDateBox.isVisible = custom
        customDateBox.isManaged = custom
    }

    // a filter / paging change started by the user: first let the detail form resolve any pending
    // edit through a Save / Discard prompt. If a requested save fails validation, keep the user on
    // the form and skip the reload.
    private fun userReload(resetPage: Boolean) {
        if (transactionDetailController.formDriver.requestLeaveEdit()) reload(resetPage)
    }

    // preferUuid: after "save and copy" the master selection is still on the original row, but we
    // want the reload to land on the just-saved transaction instead (if it matches the filter/page)
    private fun reload(resetPage: Boolean, preferUuid: Uuid? = null) {
        val session = dbService.session
        if (session == null) {
            rows.clear()
            totalCount = 0
            pageCount = 1
            pageIndex = 0
            updatePager()
            return
        }
        val filter = currentFilter()
        totalCount = runAndShowError { session.transactionDao.countItemsByFilter(filter) }.getOrDefault(0)
        pageCount = maxOf(1, ceil(totalCount / PAGE_SIZE.toDouble()).toInt())
        if (resetPage) pageIndex = 0
        if (pageIndex >= pageCount) pageIndex = pageCount - 1
        loadPage(filter, preferUuid)
    }

    private fun goToPage(index: Int) {
        if (index < 0 || index >= pageCount || index == pageIndex) return
        if (!transactionDetailController.formDriver.requestLeaveEdit()) return
        pageIndex = index
        loadPage(currentFilter())
    }

    private fun loadPage(filter: TransactionDao.Filter, preferUuid: Uuid? = null) {
        val session = dbService.session ?: return
        val prevSelected = transactionsTable.selectionModel.selectedItem
        // preferUuid comes from a just-saved transaction, whose item count/order may have changed -
        // land on any of its rows; otherwise try to keep the exact same leg, falling back to the
        // first surviving leg of the same transaction
        val prevTransactionUuid = preferUuid ?: prevSelected?.transaction?.id
        val prevNo = if (preferUuid == null) prevSelected?.itemNoInTransaction else null
        val query = TransactionDao.Query(
            filter = filter,
            offset = pageIndex * PAGE_SIZE,
            limit = PAGE_SIZE,
            sortByDateAsc = sortComboBox.value?.ascending ?: false,
        )
        val loaded = runAndShowError { session.transactionDao.getItemsByQuery(query) }.getOrDefault(emptyList())
        rows.setAll(loaded)
        if (prevTransactionUuid != null) {
            val sameTransactionRows = rows.filter { it.transaction.id == prevTransactionUuid }
            val toSelect = sameTransactionRows.firstOrNull { it.itemNoInTransaction == prevNo } ?: sameTransactionRows.firstOrNull()
            toSelect?.let(transactionsTable.selectionModel::select)
        }
        // saved transaction may be outside the current page/filter - keep showing its event info
        if (transactionsTable.selectionModel.selectedItem == null) transactionDetailController.showEventInfoForCurrentItem()
        updatePager()
    }

    private fun updatePager() {
        pageField.text = (pageIndex + 1).toString()
        pageLabel.text = "из $pageCount  (всего $totalCount)"
        prevPageButton.isDisable = pageIndex <= 0
        nextPageButton.isDisable = pageIndex >= pageCount - 1
    }

    private fun scheduleRefresh() {
        val state = transactionDetailController.formState
        if (state == FormState.EDIT || state == FormState.NEW) return
        refreshPause.playFromStart()
    }

    fun setInitialFilter(filter: InitialFilter) {
        initialFilter = filter
    }

    private fun applyInitialFilter() {
        val filter = initialFilter ?: return
        dateRangeComboBox.selectionModel.select(DateRangePreset.CUSTOM)
        updateCustomDateVisibility()
        fromDatePicker.value = filter.from
        toDatePicker.value = filter.to
        selectedAccounts.clear()
        selectedAccounts.addAll(filter.accounts)
        updateAccountsSummary()
    }

    private fun resetFilter() {
        if (!transactionDetailController.formDriver.requestLeaveEdit()) return
        dateRangeComboBox.selectionModel.select(DateRangePreset.CUSTOM)
        fromDatePicker.value = null
        toDatePicker.value = null
        selectedAccounts.clear()
        updateAccountsSummary()
        deletedComboBox.selectionModel.select(0)
        flagComboBox.selectionModel.select(0)
        sortComboBox.selectionModel.select(0)
        descriptionFilterField.clear()
        reload(resetPage = true)
    }

    private fun pickFilterAccounts() {
        if (!transactionDetailController.formDriver.requestLeaveEdit()) return
        val picked = accountPicker.pick(stage, selectedAccounts.toSet(), multi = true) ?: return
        selectedAccounts.clear()
        selectedAccounts.addAll(picked)
        updateAccountsSummary()
        reload(resetPage = true)
    }

    private fun updateAccountsSummary() {
        if (selectedAccounts.isEmpty()) {
            accountsSummaryLabel.text = "Все счета"
            return
        }
        val names = selectedAccounts.mapNotNull { accountService.accounts[it.uuid]?.content?.name }
        val shown = names.take(5).joinToString(", ")
        val tail = if (names.size > 5) ", ..." else ""
        accountsSummaryLabel.text = "Счетов: ${selectedAccounts.size}: $shown$tail"
    }

    // --- list rendering helpers ---

    private fun operationType(tx: TransactionContent): OperationType =
        OperationType.calculate(tx.items, accountService.accounts.observableEntitiesByKey)

    private fun operationTypeLabel(type: OperationType) = when (type) {
        OperationType.INCOME -> "Приход"
        OperationType.EXPENSE -> "Расход"
        OperationType.TRANSFER -> "Перевод"
        OperationType.CURRENCY_EXCHANGE -> "Обмен"
        OperationType.MIXED -> "Сложная"
    }

    private fun operationTypeColor(type: OperationType): String? = when (type) {
        OperationType.INCOME -> "#d9f2d9"
        OperationType.EXPENSE -> "#f8d9d9"
        OperationType.TRANSFER -> "#f8f2cc"
        OperationType.CURRENCY_EXCHANGE -> "#d4ebf7"
        OperationType.MIXED -> null
    }

    private fun accountValue(item: TransactionContextItemAndTransaction): String {
        val thisAccount = item.item.account.uuid
        val otherAccounts = (item.transaction.items.mapTo(mutableSetOf()) { it.account.uuid } - thisAccount)
            .map { accountService.accounts[it]?.content?.name ?: "?" }
            .sorted()
        return buildString {
            if (selectedAccounts.size != 1) {
                append(accountService.accounts[thisAccount]?.content?.name ?: "?")
                append(" → ")
            }
            otherAccounts.forEachIndexed { index, acc ->
                append(acc)
                if (index != otherAccounts.size - 1) append(", ")
            }
        }
    }

    private fun descriptionValue(item: TransactionContextItemAndTransaction) = buildString {
        if (item.isFirstInGroup) append(item.transaction.description)
        item.item.description.takeIf { it.isNotBlank() }?.let {
            if (isNotEmpty()) append(": ")
            append(it)
        }
    }

    private fun formatMoney(raw: RawMoney, currencyId: CurrencyId?): String {
        val cur = currencyId?.let { currencyService.currencies[it.uuid] }?.content
        val digits = cur?.digitsAfterPoint ?: 2
        return raw.format(digits)
    }

    private fun currencyValue(currencyId: CurrencyId?): String {
        val cur = currencyId?.let { currencyService.currencies[it.uuid] }?.content ?: return ""
        return cur.symbol.takeIf { it.isNotEmpty() } ?: cur.name
    }
}
