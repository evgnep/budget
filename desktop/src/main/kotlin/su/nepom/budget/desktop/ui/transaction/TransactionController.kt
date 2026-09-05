package su.nepom.budget.desktop.ui.transaction

import jakarta.inject.Inject
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.beans.binding.DoubleBinding
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.collections.ListChangeListener
import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.DatePicker
import javafx.scene.control.Hyperlink
import javafx.scene.control.Label
import javafx.scene.control.SelectionMode
import javafx.scene.control.TableCell
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.TextField
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.HBox
import javafx.scene.paint.Color
import javafx.stage.Stage
import javafx.util.Callback
import javafx.util.Duration
import su.nepom.budget.db.Db
import su.nepom.budget.db.dao.TransactionDao
import su.nepom.budget.desktop.model.TransactionObservable
import su.nepom.budget.desktop.service.AccountService
import su.nepom.budget.desktop.service.CurrencyService
import su.nepom.budget.desktop.service.DbService
import su.nepom.budget.desktop.util.formatDateForClipboard
import su.nepom.budget.desktop.util.formatTime
import su.nepom.budget.desktop.util.fx.Controller
import su.nepom.budget.desktop.util.fx.Disposable
import su.nepom.budget.desktop.util.fx.FormState
import su.nepom.budget.desktop.util.fx.MasterDetailFormDriver
import su.nepom.budget.desktop.util.fx.StageAwareController
import su.nepom.budget.desktop.util.fx.WeakListeners
import su.nepom.budget.desktop.util.fx.enableCopySelectionToClipboard
import su.nepom.budget.desktop.util.fx.runAndShowError
import su.nepom.budget.desktop.util.fx.setClipboardValue
import su.nepom.budget.desktop.util.fx.setupFlexibleDateFormat
import su.nepom.budget.desktop.util.toEndOfDayInstant
import su.nepom.budget.desktop.util.toStartOfDayInstant
import su.nepom.budget.event.TransactionContent
import su.nepom.budget.event.TransactionContextItemAndTransaction
import su.nepom.budget.model.AccountId
import su.nepom.budget.model.CurrencyId
import su.nepom.budget.model.OperationType
import su.nepom.budget.model.RawMoney
import su.nepom.budget.model.Uuid
import su.nepom.budget.utils.format
import su.nepom.budget.utils.toBigDecimal
import su.nepom.budget.desktop.util.toLocalDate
import java.text.DecimalFormatSymbols
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.ceil

@Suppress("unused", "UNCHECKED_CAST")
class TransactionController @Inject constructor(
    private val dbService: DbService,
    private val accountService: AccountService,
    private val currencyService: CurrencyService,
    private val accountPicker: AccountPicker,
) : Controller, StageAwareController, Disposable {

    private companion object {
        const val PAGE_SIZE = 100
        val DELETED_ROW_PSEUDO_CLASS: javafx.css.PseudoClass = javafx.css.PseudoClass.getPseudoClass("deleted-row")
        val GROUP_HEADER_ROW_PSEUDO_CLASS: javafx.css.PseudoClass = javafx.css.PseudoClass.getPseudoClass("group-header-row")
        val GROUP_HEADER_WEEKDAY_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEE", Locale.forLanguageTag("ru"))
        val GROUP_HEADER_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d-MM-yyyy")
    }

    /** Filter to apply once when the window opens (e.g. from the balances window). */
    class InitialFilter(val accounts: Set<AccountId>, val from: LocalDate?, val to: LocalDate?)

    // one calendar day's worth of table rows is a synthetic header (date + count + per-currency
    // subtotal) followed by its data rows - built client-side in buildDisplayRows(), no DB/SQL
    // changes involved
    // income and expense turnover for one currency within a day - never both zero (a currency
    // with no flow at all in either direction is simply left out of the group header)
    private data class CurrencyTurnover(val currency: CurrencyId, val income: RawMoney, val expense: RawMoney)

    private sealed class TxRow {
        data class Data(val row: TransactionContextItemAndTransaction) : TxRow()
        data class GroupHeader(val date: LocalDate, val count: Int, val turnovers: List<CurrencyTurnover>) : TxRow()
    }

    private fun TxRow.dataOrNull(): TransactionContextItemAndTransaction? = (this as? TxRow.Data)?.row

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
    private val rows = FXCollections.observableArrayList<TxRow>()
    private val selectedAccounts = mutableListOf<AccountId>()

    private var pageIndex = 0
    private var pageCount = 1
    private var totalCount = 0
    private var pageItemCount = 0
    // default matches the removed "Сначала новые" sort option; toggled by clicking dateColumn's
    // header instead of a filter combo box now
    private var sortByDateAsc = false

    private val refreshPause = PauseTransition(Duration.millis(200.0)).apply {
        setOnFinished { reload(resetPage = false) }
    }
    private val descriptionPause = PauseTransition(Duration.millis(300.0)).apply {
        setOnFinished { userReload(resetPage = true) }
    }

    private lateinit var masterDetailFormDriver: MasterDetailFormDriver<TxRow, TransactionObservable>
    // amountColumn..flagColumn combined width/x-offset, kept live as columns resize - see setupListTable()
    private lateinit var groupHeaderSpanWidth: DoubleBinding
    private lateinit var groupHeaderSpanX: DoubleBinding

    @FXML private lateinit var transactionDetailController: TransactionDetailController

    // filter panel
    @FXML private lateinit var dateRangeComboBox: ComboBox<DateRangePreset>
    @FXML private lateinit var periodHintLabel: Label
    @FXML private lateinit var customDateBox: HBox
    @FXML private lateinit var fromDatePicker: DatePicker
    @FXML private lateinit var toDatePicker: DatePicker
    @FXML private lateinit var pickAccountsButton: Button
    @FXML private lateinit var accountsSummaryLabel: Label
    @FXML private lateinit var deletedToggle: ToggleButton
    @FXML private lateinit var descriptionFilterField: TextField
    @FXML private lateinit var flagAllToggle: ToggleButton
    @FXML private lateinit var flagOnToggle: ToggleButton
    @FXML private lateinit var flagOffToggle: ToggleButton
    @FXML private lateinit var resetFilterButton: Hyperlink
    @FXML private lateinit var newButton: Button

    // pager
    @FXML private lateinit var rangeLabel: Label
    @FXML private lateinit var firstPageButton: Button
    @FXML private lateinit var prevPageButton: Button
    @FXML private lateinit var nextPageButton: Button
    @FXML private lateinit var lastPageButton: Button
    @FXML private lateinit var pageField: TextField
    @FXML private lateinit var pageCountLabel: Label

    // list
    @FXML private lateinit var transactionsTable: TableView<TxRow>
    @FXML private lateinit var markerColumn: TableColumn<TxRow, String>
    @FXML private lateinit var dateColumn: TableColumn<TxRow, String>
    @FXML private lateinit var typeColumn: TableColumn<TxRow, String>
    @FXML private lateinit var accountColumn: TableColumn<TxRow, String>
    @FXML private lateinit var amountColumn: TableColumn<TxRow, String>
    @FXML private lateinit var currencyColumn: TableColumn<TxRow, String>
    @FXML private lateinit var descriptionColumn: TableColumn<TxRow, String>
    @FXML private lateinit var flagColumn: TableColumn<TxRow, String>

    override fun initialize(stage: Stage) {
        this.stage = stage

        // Window-level filter (not scene-level) so it survives the scene being (re)assigned by
        // WindowManager after this callback runs
        stage.addEventFilter(KeyEvent.KEY_PRESSED) { e ->
            if (e.code == KeyCode.N && e.isShortcutDown) {
                newButton.fire()
                e.consume()
            }
        }

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
            { transactionsTable.selectionModel.selectedItem?.dataOrNull()?.let { TransactionObservable(it.transaction) } }
        masterDetailFormDriver = MasterDetailFormDriver(
            transactionsTable.selectionModel,
            transactionDetailController.formDriver,
            newButton,
            toDetail = { row -> row.dataOrNull()?.let { TransactionObservable(it.transaction) } },
            sameDetail = { a, b -> a.dataOrNull() != null && a.dataOrNull()?.transaction?.id == b.dataOrNull()?.transaction?.id },
            onDetailChanged = { detail -> transactionDetailController.onMasterSelectionChanged(detail) },
        )
        newButton.addEventHandler(ActionEvent.ACTION) {
            val single = selectedAccounts.singleOrNull()?.let { accountService.accounts[it.uuid] }
            transactionDetailController.onNewStarted(single)
        }
    }

    private fun setupFilterPanel() {
        val flagGroup = ToggleGroup()
        flagAllToggle.toggleGroup = flagGroup
        flagOnToggle.toggleGroup = flagGroup
        flagOffToggle.toggleGroup = flagGroup
        flagAllToggle.isSelected = true
        // a segmented tri-state control must not allow deselecting down to "nothing chosen" -
        // clicking the already-selected segment is a no-op instead of leaving the group empty
        flagGroup.selectedToggleProperty().addListener { _, old, new -> if (new == null) flagGroup.selectToggle(old) }
        dateRangeComboBox.items.setAll(*DateRangePreset.entries.toTypedArray())
        dateRangeComboBox.selectionModel.select(DateRangePreset.ALL)
        updateCustomDateVisibility()

        dateRangeComboBox.valueProperty().addListener { _, _, _ ->
            updateCustomDateVisibility()
            userReload(resetPage = true)
        }
        fromDatePicker.setupFlexibleDateFormat()
        toDatePicker.setupFlexibleDateFormat()
        fromDatePicker.valueProperty().addListener { _, _, _ -> userReload(resetPage = true) }
        toDatePicker.valueProperty().addListener { _, _, _ -> userReload(resetPage = true) }
        deletedToggle.selectedProperty().addListener { _, _, _ -> userReload(resetPage = true) }
        flagGroup.selectedToggleProperty().addListener { _, _, _ -> userReload(resetPage = true) }
        descriptionFilterField.textProperty().addListener { _, _, _ -> descriptionPause.playFromStart() }

        pickAccountsButton.setOnAction { pickFilterAccounts() }
        resetFilterButton.setOnAction { resetFilter() }
        updateAccountsSummary()

        firstPageButton.setOnAction { goToPage(0) }
        prevPageButton.setOnAction { goToPage(pageIndex - 1) }
        nextPageButton.setOnAction { goToPage(pageIndex + 1) }
        lastPageButton.setOnAction { goToPage(pageCount - 1) }
        pageField.setOnAction { jumpToTypedPage() }
        pageField.focusedProperty().addListener { _, _, focused -> if (!focused) jumpToTypedPage() }
    }

    private fun jumpToTypedPage() {
        val typed = pageField.text.trim().toIntOrNull()
        if (typed != null) goToPage(typed.coerceIn(1, pageCount) - 1)
        pageField.text = (pageIndex + 1).toString()
    }

    // clicking the "Дата" header toggles sort direction, showing the usual native triangle marker -
    // the actual ordering happens server-side (see loadPage/sortByDateAsc), so the table itself
    // must not reorder rows on its own; a no-op sort policy keeps just the header's arrow indicator
    private fun setupDateSort() {
        transactionsTable.sortPolicy = Callback { true }
        dateColumn.isSortable = true
        dateColumn.sortType = TableColumn.SortType.DESCENDING
        transactionsTable.sortOrder.setAll(dateColumn)
        // the header's native click handling only ever changes sortType between ASCENDING and
        // DESCENDING - a third click instead removes the column from sortOrder outright, leaving
        // sortType untouched, so that (not sortType turning null) is what "unsorted" looks like here
        dateColumn.sortTypeProperty().addListener { _, _, sortType ->
            when (sortType) {
                TableColumn.SortType.ASCENDING -> { sortByDateAsc = true; userReload(resetPage = true) }
                TableColumn.SortType.DESCENDING -> { sortByDateAsc = false; userReload(resetPage = true) }
                null -> {}
            }
        }
        // there are only two real options here, never "unsorted" - put the column right back with
        // the direction flipped whenever a third click drops it out of sortOrder. Deferred: mutating
        // sortOrder from inside its own change notification is asking for trouble.
        transactionsTable.sortOrder.addListener(ListChangeListener<TableColumn<*, *>> {
            if (dateColumn !in transactionsTable.sortOrder) {
                Platform.runLater {
                    dateColumn.sortType = if (sortByDateAsc) TableColumn.SortType.DESCENDING else TableColumn.SortType.ASCENDING
                    if (dateColumn !in transactionsTable.sortOrder) transactionsTable.sortOrder.add(dateColumn)
                }
            }
        })
    }

    private fun setupListTable() {
        transactionsTable.items = rows
        transactionsTable.selectionModel.selectionMode = SelectionMode.MULTIPLE
        transactionsTable.enableCopySelectionToClipboard()
        listOf(markerColumn, typeColumn, accountColumn, amountColumn, currencyColumn, descriptionColumn, flagColumn)
            .forEach { it.isSortable = false }
        setupDateSort()
        currencyColumn.text = "Валюта"
        // the group header's turnover line is drawn as an overlay on the row, sized to cover
        // accountColumn..flagColumn, giving the look of merged cells without JavaFX's TableView
        // actually supporting colspan
        groupHeaderSpanWidth = accountColumn.widthProperty()
            .add(amountColumn.widthProperty())
            .add(currencyColumn.widthProperty())
            .add(descriptionColumn.widthProperty())
            .add(flagColumn.widthProperty())
        groupHeaderSpanX = markerColumn.widthProperty()
            .add(dateColumn.widthProperty())
            .add(typeColumn.widthProperty())
        // colored stripe showing the operation type of the whole row's transaction - applies to
        // every leg of a multi-row operation, not just the first
        markerColumn.setCellValueFactory { SimpleStringProperty("") }
        markerColumn.setCellFactory {
            object : TableCell<TxRow, String>() {
                override fun updateItem(item: String?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = null
                    val data = (tableRow?.item as? TxRow.Data)?.row
                    val color = if (empty || data == null) null else operationTypeMarkerColor(operationType(data.transaction))
                    style = if (color == null) "" else "-fx-background-color: $color;"
                }
            }
        }
        // date / type / description are transaction-level - shown only on the first row of a group,
        // so a multi-leg operation doesn't repeat them on every row
        dateColumn.setCellValueFactory {
            SimpleStringProperty(
                when (val v = it.value) {
                    is TxRow.GroupHeader -> groupHeaderDateLabel(v.date)
                    is TxRow.Data -> if (v.row.isFirstInGroup) v.row.transaction.date.formatTime() else ""
                }
            )
        }
        dateColumn.setCellFactory {
            object : TableCell<TxRow, String>() {
                override fun updateItem(item: String?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty) null else item
                    style = if (!empty && tableRow?.item is TxRow.GroupHeader) "-fx-font-weight: bold;" else ""
                }
            }
        }
        // the cell itself shows only the time (the date is already visible in the day's group
        // header) - Excel still needs the actual date, so the clipboard copy carries both
        dateColumn.setClipboardValue { row ->
            row.dataOrNull()?.let { "${it.transaction.date.formatDateForClipboard()} ${it.transaction.date.formatTime()}" } ?: ""
        }
        typeColumn.setCellValueFactory {
            SimpleStringProperty(
                when (val v = it.value) {
                    is TxRow.GroupHeader -> groupHeaderCountLabel(v.count)
                    is TxRow.Data -> if (v.row.isFirstInGroup) operationTypeLabel(operationType(v.row.transaction)) else ""
                }
            )
        }
        typeColumn.setCellFactory {
            object : TableCell<TxRow, String>() {
                override fun updateItem(item: String?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty) null else item
                    val row = tableRow?.item
                    textFill = when {
                        empty || row == null -> Color.BLACK
                        row is TxRow.GroupHeader -> Color.web("#6b7480")
                        row is TxRow.Data -> operationTypeTextColor(operationType(row.row.transaction))
                        else -> Color.BLACK
                    }
                }
            }
        }
        accountColumn.setCellValueFactory {
            SimpleStringProperty((it.value as? TxRow.Data)?.let { d -> accountValue(d.row) } ?: "")
        }
        accountColumn.setCellFactory {
            object : TableCell<TxRow, String>() {
                override fun updateItem(item: String?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty) null else item
                    val row = tableRow?.item
                    textFill = when {
                        empty || row == null -> Color.BLACK
                        row is TxRow.Data && row.row.isFirstInGroup -> Color.BLACK
                        else -> Color.web("#6b7480")
                    }
                }
            }
        }
        amountColumn.setCellValueFactory {
            SimpleStringProperty(
                (it.value as? TxRow.Data)?.let { d -> formatMoney(d.row.item.money, accountService.accounts[d.row.item.account.uuid]?.content?.currency) } ?: ""
            )
        }
        amountColumn.setCellFactory {
            object : TableCell<TxRow, String>() {
                init { alignment = Pos.CENTER_RIGHT }
                override fun updateItem(item: String?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty) null else item
                    val data = (tableRow?.item as? TxRow.Data)?.row
                    textFill = if (empty || data == null) Color.BLACK else amountTextColor(data.item.money)
                }
            }
        }
        amountColumn.setClipboardValue { row ->
            row.dataOrNull()?.let { formatMoneyForClipboard(it.item.money, accountService.accounts[it.item.account.uuid]?.content?.currency) } ?: ""
        }
        currencyColumn.setCellValueFactory {
            SimpleStringProperty((it.value as? TxRow.Data)?.let { d -> currencyValue(accountService.accounts[d.row.item.account.uuid]?.content?.currency) } ?: "")
        }
        currencyColumn.setCellFactory {
            object : TableCell<TxRow, String>() {
                override fun updateItem(item: String?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty) null else item
                    textFill = Color.web("#8b95a2")
                }
            }
        }
        descriptionColumn.setCellValueFactory {
            SimpleStringProperty(
                when (val v = it.value) {
                    is TxRow.GroupHeader -> ""
                    is TxRow.Data -> descriptionValue(v.row)
                }
            )
        }
        descriptionColumn.setCellFactory {
            object : TableCell<TxRow, String>() {
                override fun updateItem(item: String?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty) null else item
                    val row = tableRow?.item
                    textFill = when {
                        empty || row == null -> Color.BLACK
                        row is TxRow.Data && row.row.isFirstInGroup -> Color.BLACK
                        else -> Color.web("#6b7480")
                    }
                }
            }
        }
        flagColumn.setCellValueFactory { SimpleStringProperty(if ((it.value as? TxRow.Data)?.row?.transaction?.flag == true) "⚑" else "") }
        flagColumn.setCellFactory {
            object : TableCell<TxRow, String>() {
                init { alignment = Pos.CENTER }
                override fun updateItem(item: String?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty) null else item
                    textFill = Color.web("#c23b32")
                }
            }
        }
        flagColumn.setClipboardValue { row -> if (row.dataOrNull()?.transaction?.flag == true) "Да" else "Нет" }
        // deleted rows are only ever shown while the "Удалённые" toggle is on - strike them
        // through instead of a separate boolean column (see transactions.css .deleted-row).
        // Group header rows get their own shaded pseudo-class and ignore mouse clicks, so they
        // can't become the table selection (see transactions.css .group-header-row)
        transactionsTable.setRowFactory {
            object : javafx.scene.control.TableRow<TxRow>() {
                // TableCellSkinBase clips each cell to its own column's bounds, so a turnover
                // line drawn as a cell's graphic gets cut at that column's edge no matter how it's
                // sized. Adding this node directly as an *unmanaged* child of the row instead
                // (rather than as a TableCell's graphic) keeps it outside that per-cell clipping -
                // it is manually positioned/sized in layoutChildren() below to cover
                // amountColumn..flagColumn, on top of those columns' (blank) cells.
                private var headerOverlay: HBox? = null

                override fun updateItem(item: TxRow?, empty: Boolean) {
                    super.updateItem(item, empty)
                    pseudoClassStateChanged(DELETED_ROW_PSEUDO_CLASS, !empty && item?.dataOrNull()?.transaction?.deleted == true)
                    val header = (if (empty) null else item) as? TxRow.GroupHeader
                    pseudoClassStateChanged(GROUP_HEADER_ROW_PSEUDO_CLASS, header != null)
                    isMouseTransparent = header != null
                    headerOverlay?.let { children.remove(it); headerOverlay = null }
                    if (header != null) {
                        headerOverlay = turnoversNode(header.turnovers).also {
                            it.isManaged = false
                            children.add(it)
                        }
                        requestLayout()
                    }
                }

                override fun layoutChildren() {
                    super.layoutChildren()
                    // never shrink below the content's own preferred width - HBox would otherwise
                    // squeeze its Labels down toward their min width, which lets JavaFX ellipsize
                    // ("...") the money text. If there are more currencies than the span fits, the
                    // line simply overflows past flagColumn and gets clipped by the table itself.
                    headerOverlay?.let {
                        val width = maxOf(groupHeaderSpanWidth.get(), it.prefWidth(-1.0))
                        it.resizeRelocate(groupHeaderSpanX.get(), 0.0, width, height)
                    }
                }
            }
        }
    }

    // groups the current page's rows by calendar day, inserting a synthetic header row (date,
    // operation count, per-currency income/expense turnover) before each day's data rows. A day
    // split across a page boundary only sees the rows that landed on this page - no extra DB
    // query for the rest.
    private fun buildDisplayRows(items: List<TransactionContextItemAndTransaction>): List<TxRow> {
        val result = ArrayList<TxRow>(items.size + items.size / 3 + 1)
        var i = 0
        while (i < items.size) {
            val day = items[i].transaction.date.toLocalDate()
            var count = 0
            val income = LinkedHashMap<CurrencyId, Long>()
            val expense = LinkedHashMap<CurrencyId, Long>()
            var j = i
            while (j < items.size && items[j].transaction.date.toLocalDate() == day) {
                val row = items[j]
                if (row.isFirstInGroup) count++
                val value = row.item.money.value
                accountService.accounts[row.item.account.uuid]?.content?.currency?.let { currency ->
                    when {
                        value > 0 -> income[currency] = (income[currency] ?: 0L) + value
                        value < 0 -> expense[currency] = (expense[currency] ?: 0L) + value
                    }
                }
                j++
            }
            val currencies = LinkedHashSet<CurrencyId>().apply { addAll(income.keys); addAll(expense.keys) }
            val turnovers = currencies.map { currency ->
                CurrencyTurnover(currency, RawMoney(income[currency] ?: 0L), RawMoney(expense[currency] ?: 0L))
            }
            result += TxRow.GroupHeader(day, count, turnovers)
            for (k in i until j) result += TxRow.Data(items[k])
            i = j
        }
        return result
    }

    private fun groupHeaderDateLabel(date: LocalDate): String {
        val weekday = GROUP_HEADER_WEEKDAY_FORMAT.format(date).replaceFirstChar { it.titlecase(Locale.forLanguageTag("ru")) }
        return "$weekday, ${GROUP_HEADER_DATE_FORMAT.format(date)}"
    }

    private fun groupHeaderCountLabel(count: Int): String = when {
        count % 10 == 1 && count % 100 != 11 -> "$count операция"
        count % 10 in 2..4 && count % 100 !in 12..14 -> "$count операции"
        else -> "$count операций"
    }

    // income shown with a leading "+" in green, expense with its already-present leading "-" in
    // red; a currency with no flow in one of the two directions just omits that label. Sizing is
    // done by the row's layoutChildren() override (see setRowFactory), not by this node itself.
    private fun turnoversNode(turnovers: List<CurrencyTurnover>): HBox = HBox(12.0).apply {
        alignment = Pos.CENTER_LEFT
        turnovers.forEach { t ->
            val symbol = currencyValue(t.currency)
            if (t.income.value != 0L) {
                children += Label("+${formatMoney(t.income, t.currency)} $symbol").apply { textFill = Color.web("#2e7d46") }
            }
            if (t.expense.value != 0L) {
                children += Label("${formatMoney(t.expense, t.currency)} $symbol").apply { textFill = Color.web("#c23b32") }
            }
        }
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
            deleted = deletedToggle.isSelected,
            descriptionLike = descriptionFilterField.text.trim().takeIf { it.isNotEmpty() }?.let { "%$it%" },
            flag = currentFlagFilter(),
        )
    }

    private fun currentFlagFilter(): Boolean? = when {
        flagOnToggle.isSelected -> true
        flagOffToggle.isSelected -> false
        else -> null
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
        updatePeriodHint()
    }

    // shows the resolved date range next to the preset combo (e.g. "29.08.2026 — 04.09.2026"),
    // except for ALL (no range) and CUSTOM (the range is already visible as date pickers)
    private fun updatePeriodHint() {
        val preset = dateRangeComboBox.value
        if (preset == null || preset == DateRangePreset.ALL || preset == DateRangePreset.CUSTOM) {
            periodHintLabel.text = ""
            return
        }
        val (from, to) = currentDateRange()
        val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        periodHintLabel.text = when {
            from == null || to == null -> ""
            from == to -> from.format(fmt)
            else -> "${from.format(fmt)} — ${to.format(fmt)}"
        }
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
            pageItemCount = 0
            updatePager()
            return
        }
        val filter = currentFilter()
        totalCount = runAndShowError { session.transactionDao.countItemsByFilter(filter) }.getOrDefault(0)
        pageCount = maxOf(1, ceil(totalCount / PAGE_SIZE.toDouble()).toInt())
        if (resetPage) pageIndex = 0
        if (pageIndex >= pageCount) pageIndex = pageCount - 1
        loadPage(filter, preferUuid)
        resetFilterButton.isDisable = !isFilterDirty()
    }

    private fun isFilterDirty(): Boolean =
        dateRangeComboBox.value != DateRangePreset.ALL ||
            fromDatePicker.value != null ||
            toDatePicker.value != null ||
            selectedAccounts.isNotEmpty() ||
            deletedToggle.isSelected ||
            !flagAllToggle.isSelected ||
            descriptionFilterField.text.isNotBlank()

    private fun goToPage(index: Int) {
        if (index < 0 || index >= pageCount || index == pageIndex) return
        if (!transactionDetailController.formDriver.requestLeaveEdit()) return
        pageIndex = index
        loadPage(currentFilter())
    }

    private fun loadPage(filter: TransactionDao.Filter, preferUuid: Uuid? = null) {
        val session = dbService.session ?: return
        val prevSelected = transactionsTable.selectionModel.selectedItem?.dataOrNull()
        // preferUuid comes from a just-saved transaction, whose item count/order may have changed -
        // land on any of its rows; otherwise try to keep the exact same leg, falling back to the
        // first surviving leg of the same transaction
        val prevTransactionUuid = preferUuid ?: prevSelected?.transaction?.id
        val prevNo = if (preferUuid == null) prevSelected?.itemNoInTransaction else null
        val query = TransactionDao.Query(
            filter = filter,
            offset = pageIndex * PAGE_SIZE,
            limit = PAGE_SIZE,
            sortByDateAsc = sortByDateAsc,
        )
        val loaded = runAndShowError { session.transactionDao.getItemsByQuery(query) }.getOrDefault(emptyList())
        pageItemCount = loaded.size
        rows.setAll(buildDisplayRows(loaded))
        if (prevTransactionUuid != null) {
            val sameTransactionRows = rows.mapNotNull { it.dataOrNull() }.filter { it.transaction.id == prevTransactionUuid }
            val toSelect = sameTransactionRows.firstOrNull { it.itemNoInTransaction == prevNo } ?: sameTransactionRows.firstOrNull()
            toSelect?.let { data -> rows.firstOrNull { it.dataOrNull() === data }?.let(transactionsTable.selectionModel::select) }
        }
        // saved transaction may be outside the current page/filter - keep showing its event info
        if (transactionsTable.selectionModel.selectedItem == null) transactionDetailController.showEventInfoForCurrentItem()
        updatePager()
    }

    private fun updatePager() {
        pageField.text = (pageIndex + 1).toString()
        pageCountLabel.text = "/ $pageCount"
        val from = if (totalCount == 0) 0 else pageIndex * PAGE_SIZE + 1
        val to = minOf(totalCount, pageIndex * PAGE_SIZE + pageItemCount)
        rangeLabel.text = "$from–$to из $totalCount"
        firstPageButton.isDisable = pageIndex <= 0
        prevPageButton.isDisable = pageIndex <= 0
        nextPageButton.isDisable = pageIndex >= pageCount - 1
        lastPageButton.isDisable = pageIndex >= pageCount - 1
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
        dateRangeComboBox.selectionModel.select(DateRangePreset.ALL)
        fromDatePicker.value = null
        toDatePicker.value = null
        selectedAccounts.clear()
        updateAccountsSummary()
        deletedToggle.isSelected = false
        flagAllToggle.isSelected = true
        sortByDateAsc = false
        dateColumn.sortType = TableColumn.SortType.DESCENDING
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

    // 3px stripe in the leftmost column, one color per operation type (same for every leg of a
    // multi-row operation)
    private fun operationTypeMarkerColor(type: OperationType): String = when (type) {
        OperationType.INCOME -> "#8fcfa8"
        OperationType.EXPENSE -> "#eab3ad"
        OperationType.TRANSFER -> "#c7ccd4"
        OperationType.CURRENCY_EXCHANGE -> "#cdb8e6"
        OperationType.MIXED -> "#c7ccd4"
    }

    private fun operationTypeTextColor(type: OperationType): Color = when (type) {
        OperationType.INCOME -> Color.web("#2e7d46")
        OperationType.EXPENSE -> Color.web("#c23b32")
        OperationType.TRANSFER -> Color.web("#5b6472")
        OperationType.CURRENCY_EXCHANGE -> Color.web("#7a4fae")
        OperationType.MIXED -> Color.web("#5b6472")
    }

    // each leg's own signed amount, independent of the transaction's overall operation type
    private fun amountTextColor(money: RawMoney): Color = when {
        money.value > 0 -> Color.web("#2e7d46")
        money.value < 0 -> Color.web("#c23b32")
        else -> Color.web("#5b6472")
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
