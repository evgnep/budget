package su.nepom.budget.desktop.ui.transaction

import jakarta.inject.Inject
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.collections.ListChangeListener
import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.DatePicker
import javafx.scene.control.Label
import javafx.scene.control.TableCell
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.TextField
import javafx.scene.control.cell.CheckBoxTableCell
import javafx.scene.control.cell.TextFieldTableCell
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.stage.Stage
import javafx.util.Duration
import su.nepom.budget.db.Db
import su.nepom.budget.db.dao.TransactionDao
import su.nepom.budget.desktop.model.AccountObservable
import su.nepom.budget.desktop.model.TransactionObservable
import su.nepom.budget.desktop.service.AccountService
import su.nepom.budget.desktop.service.CurrencyService
import su.nepom.budget.desktop.service.DbService
import su.nepom.budget.desktop.util.fx.Controller
import su.nepom.budget.desktop.util.fx.FormDriver
import su.nepom.budget.desktop.util.fx.FormState
import su.nepom.budget.desktop.util.fx.MasterDetailFormDriver
import su.nepom.budget.desktop.util.fx.StageAwareController
import su.nepom.budget.desktop.util.fx.WeakListeners
import su.nepom.budget.desktop.util.fx.runAndShowError
import su.nepom.budget.desktop.util.format
import su.nepom.budget.desktop.util.formatDateTime
import su.nepom.budget.desktop.util.toEndOfDayInstant
import su.nepom.budget.desktop.util.toLocalDate
import su.nepom.budget.desktop.util.toRawMoneyOrNull
import su.nepom.budget.desktop.util.toStartOfDayInstant
import su.nepom.budget.event.TransactionContent
import su.nepom.budget.event.TransactionContentItem
import su.nepom.budget.model.AccountId
import su.nepom.budget.model.AccountKind
import su.nepom.budget.model.CurrencyId
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.RawMoney
import su.nepom.budget.model.Uuid
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

    private class ItemRow(
        account: AccountObservable?,
        amount: String,
        description: String,
        flag: Boolean,
    ) {
        val account = SimpleObjectProperty<AccountObservable?>(this, "account", account)
        val amount = SimpleStringProperty(this, "amount", amount)
        val description = SimpleStringProperty(this, "description", description)
        val flag = SimpleBooleanProperty(this, "flag", flag)
    }

    private lateinit var stage: Stage

    private val weakListeners = WeakListeners()
    private val transactionFactory = TransactionObservable.Factory()
    private val rows = FXCollections.observableArrayList<TransactionObservable>()
    private val selectedAccounts = mutableListOf<AccountId>()

    private val itemRows = FXCollections.observableArrayList<ItemRow> { row ->
        arrayOf(row.account, row.amount, row.description, row.flag)
    }
    private val itemsProperty = SimpleObjectProperty<List<TransactionContentItem>>(this, "items", emptyList())
    private var rebuildingRows = false
    private var syncingFromRows = false

    // projected account balance keyed by account uuid: stored rest adjusted by the unsaved change of
    // this transaction's rows for that account (may be several rows per account)
    private val projectedRestByAccount = mutableMapOf<Uuid, RawMoney>()

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
    private lateinit var formDriver: FormDriver<*, TransactionObservable>

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
    @FXML private lateinit var newButton: Button

    // pager
    @FXML private lateinit var prevPageButton: Button
    @FXML private lateinit var nextPageButton: Button
    @FXML private lateinit var pageField: TextField
    @FXML private lateinit var pageLabel: Label

    // list
    @FXML private lateinit var transactionsTable: TableView<TransactionObservable>
    @FXML private lateinit var dateColumn: TableColumn<TransactionObservable, String>
    @FXML private lateinit var descriptionColumn: TableColumn<TransactionObservable, String>
    @FXML private lateinit var accountsColumn: TableColumn<TransactionObservable, String>
    @FXML private lateinit var amountColumn: TableColumn<TransactionObservable, String>
    @FXML private lateinit var flagColumn: TableColumn<TransactionObservable, Boolean>
    @FXML private lateinit var deletedColumn: TableColumn<TransactionObservable, Boolean>

    // detail form
    @FXML private lateinit var idTextField: TextField
    @FXML private lateinit var dateEditPicker: DatePicker
    @FXML private lateinit var descriptionEditField: TextField
    @FXML private lateinit var flagCheckbox: CheckBox
    @FXML private lateinit var deletedCheckbox: CheckBox
    @FXML private lateinit var itemsEditorBox: VBox
    @FXML private lateinit var itemsTable: TableView<ItemRow>
    @FXML private lateinit var itemAccountColumn: TableColumn<ItemRow, String>
    @FXML private lateinit var itemCurrencyColumn: TableColumn<ItemRow, String>
    @FXML private lateinit var itemKindColumn: TableColumn<ItemRow, String>
    @FXML private lateinit var itemAmountColumn: TableColumn<ItemRow, String>
    @FXML private lateinit var itemBalanceColumn: TableColumn<ItemRow, String>
    @FXML private lateinit var itemDescriptionColumn: TableColumn<ItemRow, String>
    @FXML private lateinit var itemFlagColumn: TableColumn<ItemRow, Boolean>
    @FXML private lateinit var changedAtField: TextField
    @FXML private lateinit var creatorField: TextField
    @FXML private lateinit var placeField: TextField
    @FXML private lateinit var addItemButton: Button
    @FXML private lateinit var removeItemButton: Button
    @FXML private lateinit var balanceLabel: Label
    @FXML private lateinit var okButton: Button
    @FXML private lateinit var cancelButton: Button
    @FXML private lateinit var copyButton: Button

    override fun initialize(stage: Stage) {
        this.stage = stage

        setupFilterPanel()
        setupListTable()
        setupItemsEditor()
        setupForm()

        weakListeners.addListenerAndCallNow(dbService.sessionProperty) { _, _, session ->
            if (session != null) {
                weakListeners.subscribe(session.db, Db.SubscribeKind.TRANSACTION) {
                    Platform.runLater { scheduleRefresh() }
                }
            }
            reload(resetPage = true)
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
        listOf(dateColumn, descriptionColumn, accountsColumn, amountColumn, flagColumn, deletedColumn)
            .forEach { it.isSortable = false }
        dateColumn.setCellValueFactory { SimpleStringProperty(it.value.content.date.formatDateTime()) }
        descriptionColumn.setCellValueFactory { SimpleStringProperty(it.value.content.description) }
        accountsColumn.setCellValueFactory { SimpleStringProperty(accountNames(it.value.content)) }
        amountColumn.setCellValueFactory { SimpleStringProperty(amountSummary(it.value.content)) }
        flagColumn.setCellValueFactory { it.value.flag as javafx.beans.value.ObservableValue<Boolean> }
        flagColumn.cellFactory = CheckBoxTableCell.forTableColumn(flagColumn)
        deletedColumn.setCellValueFactory { it.value.deleted as javafx.beans.value.ObservableValue<Boolean> }
        deletedColumn.cellFactory = CheckBoxTableCell.forTableColumn(deletedColumn)
    }

    private fun setupItemsEditor() {
        itemsTable.items = itemRows
        itemsTable.isEditable = true

        itemAccountColumn.setCellValueFactory {
            SimpleStringProperty(it.value.account.get()?.content?.name ?: "(не выбран)")
        }
        itemAccountColumn.setCellFactory {
            object : TableCell<ItemRow, String>() {
                init {
                    setOnMouseClicked { e ->
                        if (e.clickCount == 2) tableRow?.item?.let { pickAccountForRow(it) }
                    }
                }

                override fun updateItem(item: String?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty) null else item
                }
            }
        }
        itemCurrencyColumn.setCellValueFactory {
            SimpleStringProperty(it.value.account.get()?.let { acc -> currencyName(acc) } ?: "")
        }
        itemKindColumn.setCellValueFactory {
            SimpleStringProperty(it.value.account.get()?.let { acc -> kindText(acc.content.kind) } ?: "")
        }
        itemAmountColumn.setCellValueFactory { it.value.amount }
        itemAmountColumn.cellFactory = TextFieldTableCell.forTableColumn()
        itemBalanceColumn.setCellValueFactory {
            val acc = it.value.account.get()
            SimpleStringProperty(
                acc?.let { a -> projectedRestByAccount[a.uuid]?.format(digitsOf(a)) } ?: ""
            )
        }
        itemDescriptionColumn.setCellValueFactory { it.value.description }
        itemDescriptionColumn.cellFactory = TextFieldTableCell.forTableColumn()
        itemFlagColumn.setCellValueFactory { it.value.flag as javafx.beans.value.ObservableValue<Boolean> }
        itemFlagColumn.cellFactory = CheckBoxTableCell.forTableColumn(itemFlagColumn)

        addItemButton.setOnAction { addItemRow() }
        removeItemButton.setOnAction {
            itemsTable.selectionModel.selectedItem?.let { itemRows.remove(it) }
        }

        itemRows.addListener(ListChangeListener { if (!rebuildingRows) syncItemsFromRows() })
        itemsProperty.addListener { _, _, value ->
            if (!syncingFromRows) rebuildRows(value ?: emptyList())
            updateBalanceLabel()
            recomputeProjectedRests()
        }
    }

    private fun recomputeProjectedRests() {
        if (!::formDriver.isInitialized) return
        projectedRestByAccount.clear()
        val accounts = itemRows.mapNotNull { it.account.get() }
        val session = dbService.session
        if (accounts.isNotEmpty() && session != null) {
            val accountIds = accounts.map { AccountId(it.uuid) }.toSet()
            val stored = runAndShowError { session.transactionDao.accountRest(accountIds) }.getOrDefault(emptyMap())
            // money already reflected in stored rest by the last saved version of this transaction
            val savedByAccount = mutableMapOf<Uuid, Long>()
            formDriver.item?.content?.items?.forEach { savedByAccount.merge(it.account.uuid, it.money.value, Long::plus) }
            // money currently entered in the rows (best-effort parse)
            val currentByAccount = mutableMapOf<Uuid, Long>()
            itemRows.forEach { row ->
                val acc = row.account.get() ?: return@forEach
                val value = row.amount.get().toRawMoneyOrNull(digitsOf(acc))?.value ?: 0L
                currentByAccount.merge(acc.uuid, value, Long::plus)
            }
            accountIds.forEach { id ->
                val base = stored[id]?.value ?: 0L
                val delta = (currentByAccount[id.uuid] ?: 0L) - (savedByAccount[id.uuid] ?: 0L)
                projectedRestByAccount[id.uuid] = RawMoney(base + delta)
            }
        }
        itemsTable.refresh()
    }

    private fun setupForm() {
        formDriver = FormDriver.builder(
            okButton,
            cancelButton,
            transactionFactory,
            dbService.sessionProperty,
        )
            .idField(idTextField)
            .field(
                "date",
                dateEditPicker,
                dateEditPicker.valueProperty(),
                { it?.content?.date?.toLocalDate() ?: LocalDate.now() },
                { date = it },
            ) {
                withMethod { if (dateEditPicker.value == null) it.error("Укажите дату") }.immediateClear()
            }
            .field(
                "description",
                descriptionEditField,
                descriptionEditField.textProperty(),
                { it?.content?.description ?: "" },
                { description = it },
            )
            .field(
                "flag",
                flagCheckbox,
                flagCheckbox.selectedProperty(),
                { it?.content?.flag ?: false },
                { flag = it },
            )
            .field(
                "deleted",
                deletedCheckbox,
                deletedCheckbox.selectedProperty(),
                { it?.content?.deleted ?: false },
                { deleted = it },
            )
            .field(
                "items",
                itemsEditorBox,
                itemsProperty,
                { it?.content?.items ?: emptyList() },
                { items = it },
            ) {
                withMethod { ctx -> validateItems().forEach { ctx.error(it) } }.immediate()
            }
            .build()

        masterDetailFormDriver = MasterDetailFormDriver(
            transactionsTable.selectionModel,
            formDriver,
            newButton,
        )

        transactionsTable.selectionModel.selectedItemProperty().addListener { _, _, selected ->
            updateEventInfo(selected)
            // form state is set by MasterDetailFormDriver on the same selection event - defer so we read it settled
            Platform.runLater { updateCopyButton() }
        }
        // new transaction has no events yet - clear the info fields
        newButton.addEventHandler(ActionEvent.ACTION) { updateEventInfo(null) }
        okButton.disableProperty().addListener { _, _, _ -> updateCopyButton() }
        copyButton.setOnAction { saveAndCopy() }
        updateEventInfo(null)
        updateCopyButton()
    }

    private fun updateCopyButton() {
        val state = formDriver.state
        copyButton.isDisable = state == FormState.EMPTY
        val modified = state == FormState.EDIT || state == FormState.NEW
        copyButton.text = if (modified) "Сохранить и скопировать" else "Скопировать"
    }

    /**
     * Optionally saves the current transaction (if it was modified), then, if there were no errors,
     * starts a new unsaved transaction pre-filled as a copy of the current one.
     */
    private fun saveAndCopy() {
        val state = formDriver.state
        if (state == FormState.EMPTY) return
        if (state == FormState.EDIT || state == FormState.NEW) {
            okButton.fire()
            if (formDriver.state != FormState.VIEW) return // save failed (validation) - do not copy
        }
        val source = formDriver.item?.content ?: return
        if (!formDriver.newItem()) return
        dateEditPicker.value = source.date.toLocalDate()
        descriptionEditField.text = source.description
        flagCheckbox.isSelected = source.flag
        deletedCheckbox.isSelected = source.deleted
        itemsProperty.set(source.items.toList())
        updateEventInfo(null)
        updateCopyButton()
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
        if (transactionsTable.selectionModel.selectedItem == null) updateEventInfo(formDriver.item)
        updatePager()
    }

    private fun updatePager() {
        pageField.text = (pageIndex + 1).toString()
        pageLabel.text = "из $pageCount  (всего $totalCount)"
        prevPageButton.isDisable = pageIndex <= 0
        nextPageButton.isDisable = pageIndex >= pageCount - 1
    }

    private fun scheduleRefresh() {
        if (formDriver.state == FormState.EDIT || formDriver.state == FormState.NEW) return
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

    // --- items editor ---

    private fun rebuildRows(items: List<TransactionContentItem>) {
        rebuildingRows = true
        itemRows.setAll(items.map { it.toRow() })
        rebuildingRows = false
    }

    private fun syncItemsFromRows() {
        syncingFromRows = true
        itemsProperty.set(rowsToItems())
        syncingFromRows = false
        updateBalanceLabel()
    }

    private fun TransactionContentItem.toRow(): ItemRow {
        val acc = accountService.accounts[account.uuid]
        return ItemRow(acc, money.format(digitsOf(acc)), description, flag)
    }

    private fun rowsToItems(): List<TransactionContentItem> =
        itemRows.mapNotNull { row ->
            val acc = row.account.get() ?: return@mapNotNull null
            val money = row.amount.get().toRawMoneyOrNull(digitsOf(acc)) ?: RawMoney.ZERO
            TransactionContentItem(
                account = AccountId(acc.uuid),
                money = money,
                description = row.description.get(),
                flag = row.flag.get(),
            )
        }

    private fun addItemRow() {
        val picked = accountPicker.pick(stage, emptySet(), multi = false) ?: return
        val account = picked.firstOrNull()?.let { accountService.accounts[it.uuid] } ?: return
        val row = ItemRow(account, "", "", false)
        itemRows.add(row)
        Platform.runLater { itemsTable.selectionModel.select(row) }
    }

    private fun pickAccountForRow(row: ItemRow) {
        val pre = row.account.get()?.let { setOf(AccountId(it.uuid)) } ?: emptySet()
        val picked = accountPicker.pick(stage, pre, multi = false) ?: return
        val id = picked.firstOrNull() ?: return
        row.account.set(accountService.accounts[id.uuid])
        syncItemsFromRows()
        // TODO forced refresh: table does not repaint the row while we are still
        // inside the double-click handler that opened the modal picker
        Platform.runLater { itemsTable.refresh() }
    }

    private fun validateItems(): List<String> {
        val problems = mutableListOf<String>()
        if (itemRows.isEmpty()) {
            problems.add("Добавьте хотя бы одну строку")
            return problems
        }
        itemRows.forEachIndexed { i, row ->
            val acc = row.account.get()
            if (acc == null) problems.add("Строка ${i + 1}: выберите счёт")
            else if (row.amount.get().toRawMoneyOrNull(digitsOf(acc)) == null)
                problems.add("Строка ${i + 1}: некорректная сумма")
        }
        if (problems.isEmpty()) {
            computeBalance().forEach { (currencyId, sums) ->
                if (sums.first != sums.second) {
                    val name = currencyService.currencies[currencyId.uuid]?.content?.name ?: "?"
                    problems.add("Не сходится по валюте $name")
                }
            }
        }
        return problems
    }

    private fun computeBalance(): Map<CurrencyId, Pair<Long, Long>> {
        val sums = mutableMapOf<CurrencyId, LongArray>()
        itemRows.forEach { row ->
            val acc = row.account.get() ?: return@forEach
            val raw = row.amount.get().toRawMoneyOrNull(digitsOf(acc))?.value ?: return@forEach
            val arr = sums.getOrPut(acc.content.currency) { LongArray(2) }
            when (acc.content.kind) {
                AccountKind.MONEY -> arr[0] += raw
                AccountKind.BUDGET -> arr[1] += raw
            }
        }
        return sums.mapValues { it.value[0] to it.value[1] }
    }

    private fun updateBalanceLabel() {
        val balance = computeBalance()
        if (balance.isEmpty()) {
            balanceLabel.text = ""
            return
        }
        var allBalanced = true
        val text = balance.entries.joinToString("\n") { (currencyId, sums) ->
            val cur = currencyService.currencies[currencyId.uuid]
            val digits = cur?.content?.digitsAfterPoint ?: 2
            val name = cur?.content?.name ?: "?"
            val balanced = sums.first == sums.second
            if (!balanced) allBalanced = false
            val mark = if (balanced) "✓" else "✗"
            "$name: деньги ${RawMoney(sums.first).format(digits)} / бюджет ${RawMoney(sums.second).format(digits)} $mark"
        }
        balanceLabel.text = text
        balanceLabel.textFill = if (allBalanced) Color.GREEN else Color.RED
    }

    private fun digitsOf(account: AccountObservable?): Int =
        account?.let { currencyService.currencies[it.content.currency.uuid]?.content?.digitsAfterPoint } ?: 2

    private fun currencyName(account: AccountObservable): String =
        currencyService.currencies[account.content.currency.uuid]?.content?.name ?: "-"

    private fun kindText(kind: AccountKind): String = when (kind) {
        AccountKind.MONEY -> "Деньги"
        AccountKind.BUDGET -> "Бюджет"
    }

    private fun updateEventInfo(tx: TransactionObservable?) {
        val session = dbService.session
        val event = if (tx != null && session != null)
            runAndShowError { session.eventDao.getLastEventForObject(tx.uuid, ObjectKind.TRANSACTION) }.getOrNull()
        else null
        changedAtField.text = event?.created?.formatDateTime() ?: ""
        creatorField.text = event?.creator ?: ""
        placeField.text = event?.coords?.source?.code ?: ""
    }

    // --- list rendering helpers ---

    private fun accountNames(tx: TransactionContent): String =
        tx.items.mapNotNull { accountService.accounts[it.account.uuid]?.content?.name }
            .distinct()
            .joinToString(", ")

    private fun amountSummary(tx: TransactionContent): String {
        val byCurrency = mutableMapOf<CurrencyId, Long>()
        tx.items.forEach { item ->
            val acc = accountService.accounts[item.account.uuid] ?: return@forEach
            if (acc.content.kind == AccountKind.MONEY && item.money.value > 0) {
                byCurrency.merge(acc.content.currency, item.money.value) { a, b -> a + b }
            }
        }
        return byCurrency.entries.joinToString(", ") { (currencyId, raw) ->
            val cur = currencyService.currencies[currencyId.uuid]
            val digits = cur?.content?.digitsAfterPoint ?: 2
            "${RawMoney(raw).format(digits)} ${cur?.content?.name ?: ""}".trim()
        }
    }
}
