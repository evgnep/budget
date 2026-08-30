package su.nepom.budget.desktop.ui.transaction

import jakarta.inject.Inject
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.DatePicker
import javafx.scene.control.Label
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
import su.nepom.budget.desktop.util.fx.Controller
import su.nepom.budget.desktop.util.fx.FormState
import su.nepom.budget.desktop.util.fx.MasterDetailFormDriver
import su.nepom.budget.desktop.util.fx.StageAwareController
import su.nepom.budget.desktop.util.fx.WeakListeners
import su.nepom.budget.desktop.util.fx.runAndShowError
import su.nepom.budget.desktop.util.format
import su.nepom.budget.desktop.util.formatDateTime
import su.nepom.budget.desktop.util.toEndOfDayInstant
import su.nepom.budget.desktop.util.toStartOfDayInstant
import su.nepom.budget.desktop.ui.history.History
import su.nepom.budget.event.AccountContent
import su.nepom.budget.event.TransactionContent
import su.nepom.budget.event.TransactionContentItem
import su.nepom.budget.model.AccountId
import su.nepom.budget.model.ContentHolder
import su.nepom.budget.model.CurrencyId
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.OperationType
import su.nepom.budget.model.RawMoney
import su.nepom.budget.model.Uuid
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.ceil

@Suppress("unused", "UNCHECKED_CAST")
class TransactionController @Inject constructor(
    private val dbService: DbService,
    private val accountService: AccountService,
    private val currencyService: CurrencyService,
    private val accountPicker: AccountPicker,
    private val history: History,
) : Controller, StageAwareController {

    private companion object {
        const val PAGE_SIZE = 100
    }

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

    private val weakListeners = WeakListeners()
    private val rows = FXCollections.observableArrayList<TransactionObservable>()
    private val selectedAccounts = mutableListOf<AccountId>()

    private var pageIndex = 0
    private var pageCount = 1
    private var totalCount = 0

    private val refreshPause = PauseTransition(Duration.millis(200.0)).apply {
        setOnFinished { reload(resetPage = false) }
    }
    private val descriptionPause = PauseTransition(Duration.millis(300.0)).apply {
        setOnFinished { reload(resetPage = true) }
    }

    private lateinit var masterDetailFormDriver: MasterDetailFormDriver<TransactionObservable>

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
    @FXML private lateinit var newButton: Button

    // pager
    @FXML private lateinit var prevPageButton: Button
    @FXML private lateinit var nextPageButton: Button
    @FXML private lateinit var pageField: TextField
    @FXML private lateinit var pageLabel: Label

    // list
    @FXML private lateinit var transactionsTable: TableView<TransactionObservable>
    @FXML private lateinit var dateColumn: TableColumn<TransactionObservable, String>
    @FXML private lateinit var typeColumn: TableColumn<TransactionObservable, String>
    @FXML private lateinit var descriptionColumn: TableColumn<TransactionObservable, String>
    @FXML private lateinit var operationColumn: TableColumn<TransactionObservable, String>
    @FXML private lateinit var flagColumn: TableColumn<TransactionObservable, Boolean>
    @FXML private lateinit var deletedColumn: TableColumn<TransactionObservable, Boolean>

    override fun initialize(stage: Stage) {
        this.stage = stage

        setupFilterPanel()
        setupListTable()
        wireDetail()

        weakListeners.addListenerAndCallNow(dbService.sessionProperty) { _, _, session ->
            if (session != null) {
                weakListeners.subscribe(session.db, Db.SubscribeKind.TRANSACTION) {
                    Platform.runLater { scheduleRefresh() }
                }
            }
            reload(resetPage = true)
        }
    }

    private fun wireDetail() {
        transactionDetailController.setStage(stage)
        masterDetailFormDriver = MasterDetailFormDriver(
            transactionsTable.selectionModel,
            transactionDetailController.formDriver,
            newButton,
        )
        transactionsTable.selectionModel.selectedItemProperty().addListener { _, _, selected ->
            transactionDetailController.onMasterSelectionChanged(selected)
        }
        newButton.addEventHandler(ActionEvent.ACTION) { transactionDetailController.onNewStarted() }

        historyButton.disableProperty()
            .bind(transactionsTable.selectionModel.selectedItemProperty().isNull)
        historyButton.setOnAction {
            val selected = transactionsTable.selectionModel.selectedItem ?: return@setOnAction
            history.show(stage, selected.uuid, ObjectKind.TRANSACTION, "История операции")
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
            reload(resetPage = true)
        }
        fromDatePicker.valueProperty().addListener { _, _, _ -> reload(resetPage = true) }
        toDatePicker.valueProperty().addListener { _, _, _ -> reload(resetPage = true) }
        deletedComboBox.valueProperty().addListener { _, _, _ -> reload(resetPage = true) }
        flagComboBox.valueProperty().addListener { _, _, _ -> reload(resetPage = true) }
        sortComboBox.valueProperty().addListener { _, _, _ -> reload(resetPage = true) }
        descriptionFilterField.textProperty().addListener { _, _, _ -> descriptionPause.playFromStart() }

        pickAccountsButton.setOnAction { pickFilterAccounts() }
        resetFilterButton.setOnAction { resetFilter() }
        applyFilterButton.setOnAction { reload(resetPage = true) }
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
        listOf(dateColumn, typeColumn, descriptionColumn, operationColumn, flagColumn, deletedColumn)
            .forEach { it.isSortable = false }
        dateColumn.setCellValueFactory { SimpleStringProperty(it.value.content.date.formatDateTime()) }
        typeColumn.setCellValueFactory { SimpleStringProperty(operationTypeLabel(operationType(it.value.content))) }
        descriptionColumn.setCellValueFactory { SimpleStringProperty(it.value.content.description) }
        operationColumn.setCellValueFactory { SimpleStringProperty(operationText(it.value.content)) }
        flagColumn.setCellValueFactory { it.value.flag as javafx.beans.value.ObservableValue<Boolean> }
        flagColumn.cellFactory = CheckBoxTableCell.forTableColumn(flagColumn)
        deletedColumn.setCellValueFactory { it.value.deleted as javafx.beans.value.ObservableValue<Boolean> }
        deletedColumn.cellFactory = CheckBoxTableCell.forTableColumn(deletedColumn)
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

    private fun reload(resetPage: Boolean) {
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
        totalCount = runAndShowError { session.transactionDao.countByFilter(filter) }.getOrDefault(0)
        pageCount = maxOf(1, ceil(totalCount / PAGE_SIZE.toDouble()).toInt())
        if (resetPage) pageIndex = 0
        if (pageIndex >= pageCount) pageIndex = pageCount - 1
        loadPage(filter)
    }

    private fun goToPage(index: Int) {
        if (index < 0 || index >= pageCount || index == pageIndex) return
        pageIndex = index
        loadPage(currentFilter())
    }

    private fun loadPage(filter: TransactionDao.Filter) {
        val session = dbService.session ?: return
        val prevUuid = transactionsTable.selectionModel.selectedItem?.uuid
        val query = TransactionDao.Query(
            filter = filter,
            offset = pageIndex * PAGE_SIZE,
            limit = PAGE_SIZE,
            sortByDateAsc = sortComboBox.value?.ascending ?: false,
        )
        val loaded = runAndShowError { session.transactionDao.getByQuery(query) }.getOrDefault(emptyList())
        rows.setAll(loaded.map { TransactionObservable(it) })
        if (prevUuid != null) rows.firstOrNull { it.uuid == prevUuid }?.let(transactionsTable.selectionModel::select)
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

    private fun resetFilter() {
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

    private fun currencyOf(item: TransactionContentItem): CurrencyId? =
        accountService.accounts[item.account.uuid]?.content?.currency

    private fun formatMoney(raw: RawMoney, currencyId: CurrencyId?): String {
        val cur = currencyId?.let { currencyService.currencies[it.uuid] }?.content
        val digits = cur?.digitsAfterPoint ?: 2
        return "${raw.format(digits)} ${cur?.name ?: ""}".trim()
    }

    private fun operationText(tx: TransactionContent): String {
        val hidden = selectedAccounts.mapTo(mutableSetOf()) { it.uuid }
        fun accountsPart(items: List<TransactionContentItem>, separator: String) =
            items.map { it.account.uuid }
                .filter { it !in hidden }
                .distinct()
                .mapNotNull { accountService.accounts[it]?.content?.name }
                .joinToString(separator)

        fun withAccounts(head: String, accounts: String) = if (accounts.isEmpty()) head else "$head: $accounts"

        return when (operationType(tx)) {
            OperationType.INCOME, OperationType.EXPENSE, OperationType.TRANSFER -> {
                val sorted = tx.items.sortedBy { it.money.value }
                val amount = RawMoney(abs(sorted.first().money.value))
                withAccounts(formatMoney(amount, currencyOf(sorted.first())), accountsPart(sorted, " - "))
            }

            OperationType.CURRENCY_EXCHANGE -> {
                val currencies = tx.items.mapNotNull { currencyOf(it) }.distinct()
                val groups = currencies.map { c -> c to tx.items.filter { currencyOf(it) == c } }
                val source = groups.minByOrNull { (_, items) -> items.first().money.value } ?: groups.first()
                val target = groups.firstOrNull { it !== source } ?: groups.last()
                val srcSum = formatMoney(RawMoney(abs(source.second.first().money.value)), source.first)
                val tgtSum = formatMoney(RawMoney(abs(target.second.first().money.value)), target.first)
                withAccounts("$srcSum -> $tgtSum", accountsPart(tx.items, ", "))
            }

            OperationType.MIXED -> {
                val first = tx.items.first()
                withAccounts(formatMoney(first.money, currencyOf(first)), accountsPart(tx.items, ", "))
            }
        }
    }
}
