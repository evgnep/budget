package su.nepom.budget.desktop.ui.event

import jakarta.inject.Inject
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.collections.ListChangeListener
import javafx.fxml.FXML
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.DatePicker
import javafx.scene.control.Hyperlink
import javafx.scene.control.Label
import javafx.scene.control.SplitPane
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.stage.Stage
import javafx.util.Callback
import javafx.util.Duration
import javafx.util.StringConverter
import kotlinx.datetime.Instant
import su.nepom.budget.db.Db
import su.nepom.budget.db.dao.EventDao
import su.nepom.budget.desktop.service.DbService
import su.nepom.budget.desktop.service.WindowStateService
import su.nepom.budget.desktop.ui.account.AccountDetailController
import su.nepom.budget.desktop.ui.conflict.JsonDetailController
import su.nepom.budget.desktop.ui.currency.CurrencyDetailController
import su.nepom.budget.desktop.ui.transaction.TransactionDetailController
import su.nepom.budget.desktop.util.formatDateTime
import su.nepom.budget.desktop.util.fx.Controller
import su.nepom.budget.desktop.util.fx.Disposable
import su.nepom.budget.desktop.util.fx.StageAwareController
import su.nepom.budget.desktop.util.fx.WeakListeners
import su.nepom.budget.desktop.util.fx.enableCopySelectionToClipboard
import su.nepom.budget.desktop.util.fx.runAndShowError
import su.nepom.budget.desktop.util.fx.setupFlexibleDateFormat
import su.nepom.budget.desktop.util.toEndOfDayInstant
import su.nepom.budget.desktop.util.toStartOfDayInstant
import su.nepom.budget.event.AccountContent
import su.nepom.budget.event.ActualEvent
import su.nepom.budget.event.CurrencyContent
import su.nepom.budget.event.EventType
import su.nepom.budget.event.TransactionContent
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.Place
import su.nepom.budget.model.Uuid
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil

@Suppress("unused")
class EventsController @Inject constructor(
    private val dbService: DbService,
    private val windowStateService: WindowStateService,
) : Controller, StageAwareController, Disposable {

    private companion object {
        const val PAGE_SIZE = 100
        const val NAME = "events"
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

    private val weakListeners = WeakListeners()
    private val rows = FXCollections.observableArrayList<ActualEvent>()

    private var pageIndex = 0
    private var pageCount = 1
    private var totalCount = 0
    private var pageItemCount = 0
    // default matches the removed "Сначала новые" sort option; toggled by clicking dateColumn's
    // header instead of a filter combo box, same as in Операции
    private var sortByDateAsc = false

    private val textFilterPause = PauseTransition(Duration.millis(300.0)).apply {
        setOnFinished { reload(resetPage = true) }
    }

    @FXML private lateinit var placeholderLabel: Label
    @FXML private lateinit var currencyDetail: Node
    @FXML private lateinit var currencyDetailController: CurrencyDetailController
    @FXML private lateinit var accountDetail: Node
    @FXML private lateinit var accountDetailController: AccountDetailController
    @FXML private lateinit var transactionDetail: Node
    @FXML private lateinit var transactionDetailController: TransactionDetailController
    @FXML private lateinit var jsonDetail: Node
    @FXML private lateinit var jsonDetailController: JsonDetailController

    // filter panel
    @FXML private lateinit var placeFilterField: TextField
    @FXML private lateinit var noFromField: TextField
    @FXML private lateinit var noToField: TextField
    @FXML private lateinit var dateRangeComboBox: ComboBox<DateRangePreset>
    @FXML private lateinit var periodHintLabel: Label
    @FXML private lateinit var customDateBox: HBox
    @FXML private lateinit var fromDatePicker: DatePicker
    @FXML private lateinit var toDatePicker: DatePicker
    @FXML private lateinit var creatorFilterField: TextField
    @FXML private lateinit var typeFilterComboBox: ComboBox<EventType?>
    @FXML private lateinit var kindFilterComboBox: ComboBox<ObjectKind?>
    @FXML private lateinit var contentPartFilterField: TextField
    @FXML private lateinit var uidFilterField: TextField
    @FXML private lateinit var resetFilterButton: Hyperlink

    // pager
    @FXML private lateinit var rangeLabel: Label
    @FXML private lateinit var firstPageButton: Button
    @FXML private lateinit var prevPageButton: Button
    @FXML private lateinit var nextPageButton: Button
    @FXML private lateinit var lastPageButton: Button
    @FXML private lateinit var pageField: TextField
    @FXML private lateinit var pageCountLabel: Label

    // list
    @FXML private lateinit var eventsSplitter: SplitPane
    @FXML private lateinit var eventsTable: TableView<ActualEvent>
    @FXML private lateinit var dateColumn: TableColumn<ActualEvent, String>
    @FXML private lateinit var creatorColumn: TableColumn<ActualEvent, String>
    @FXML private lateinit var placeColumn: TableColumn<ActualEvent, String>
    @FXML private lateinit var noColumn: TableColumn<ActualEvent, String>
    @FXML private lateinit var typeColumn: TableColumn<ActualEvent, String>
    @FXML private lateinit var kindColumn: TableColumn<ActualEvent, String>
    @FXML private lateinit var uidColumn: TableColumn<ActualEvent, String>

    override fun initialize(stage: Stage) {
        setupFilterPanel()
        setupListTable()
        showDetail(null)
        windowStateService.bindSplitPane(stage, NAME, eventsSplitter)
        windowStateService.bindTableColumns(NAME, eventsTable)

        weakListeners.addListenerAndCallNow(dbService.sessionProperty) { _, _, session ->
            if (session != null) {
                weakListeners.subscribe(session.db, Db.SubscribeKind.ALL) {
                    Platform.runLater { reload(resetPage = false) }
                }
            }
            reload(resetPage = true)
        }
    }

    override fun dispose() {
        weakListeners.dispose()
        textFilterPause.stop()
    }

    private fun setupFilterPanel() {
        dateRangeComboBox.items.setAll(*DateRangePreset.entries.toTypedArray())
        dateRangeComboBox.selectionModel.select(DateRangePreset.ALL)
        updateCustomDateVisibility()

        dateRangeComboBox.valueProperty().addListener { _, _, _ ->
            updateCustomDateVisibility()
            reload(resetPage = true)
        }
        fromDatePicker.setupFlexibleDateFormat()
        toDatePicker.setupFlexibleDateFormat()
        fromDatePicker.valueProperty().addListener { _, _, _ -> reload(resetPage = true) }
        toDatePicker.valueProperty().addListener { _, _, _ -> reload(resetPage = true) }

        placeFilterField.textProperty().addListener { _, _, _ -> textFilterPause.playFromStart() }
        noFromField.textProperty().addListener { _, _, _ -> textFilterPause.playFromStart() }
        noToField.textProperty().addListener { _, _, _ -> textFilterPause.playFromStart() }
        creatorFilterField.textProperty().addListener { _, _, _ -> textFilterPause.playFromStart() }
        contentPartFilterField.textProperty().addListener { _, _, _ -> textFilterPause.playFromStart() }
        uidFilterField.textProperty().addListener { _, _, _ -> textFilterPause.playFromStart() }

        typeFilterComboBox.items.setAll(listOf<EventType?>(null) + EventType.entries)
        typeFilterComboBox.converter = object : StringConverter<EventType?>() {
            override fun toString(type: EventType?) = type?.let { eventTypeLabel(it) } ?: "Все"
            override fun fromString(string: String?): EventType? = null
        }
        typeFilterComboBox.selectionModel.select(null as EventType?)
        typeFilterComboBox.valueProperty().addListener { _, _, _ -> reload(resetPage = true) }

        kindFilterComboBox.items.setAll(listOf<ObjectKind?>(null) + ObjectKind.entries)
        kindFilterComboBox.converter = object : StringConverter<ObjectKind?>() {
            override fun toString(kind: ObjectKind?) = kind?.let { objectKindLabel(it) } ?: "Все"
            override fun fromString(string: String?): ObjectKind? = null
        }
        kindFilterComboBox.selectionModel.select(null as ObjectKind?)
        kindFilterComboBox.valueProperty().addListener { _, _, _ -> reload(resetPage = true) }

        resetFilterButton.setOnAction { resetFilter() }

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

    // clicking the "Дата" header toggles sort direction, same two-state (no "unsorted") behavior
    // as the "Операции" table - see TransactionController.setupDateSort
    private fun setupDateSort() {
        eventsTable.sortPolicy = Callback { true }
        dateColumn.isSortable = true
        dateColumn.sortType = TableColumn.SortType.DESCENDING
        eventsTable.sortOrder.setAll(dateColumn)
        dateColumn.sortTypeProperty().addListener { _, _, sortType ->
            when (sortType) {
                TableColumn.SortType.ASCENDING -> { sortByDateAsc = true; reload(resetPage = true) }
                TableColumn.SortType.DESCENDING -> { sortByDateAsc = false; reload(resetPage = true) }
                null -> {}
            }
        }
        eventsTable.sortOrder.addListener(ListChangeListener<TableColumn<*, *>> {
            if (dateColumn !in eventsTable.sortOrder) {
                Platform.runLater {
                    dateColumn.sortType = if (sortByDateAsc) TableColumn.SortType.DESCENDING else TableColumn.SortType.ASCENDING
                    if (dateColumn !in eventsTable.sortOrder) eventsTable.sortOrder.add(dateColumn)
                }
            }
        })
    }

    private fun setupListTable() {
        eventsTable.items = rows
        eventsTable.enableCopySelectionToClipboard()
        listOf(creatorColumn, placeColumn, noColumn, typeColumn, kindColumn, uidColumn).forEach { it.isSortable = false }
        setupDateSort()
        dateColumn.setCellValueFactory { SimpleStringProperty(it.value.created.formatDateTime()) }
        creatorColumn.setCellValueFactory { SimpleStringProperty(it.value.creator) }
        placeColumn.setCellValueFactory { SimpleStringProperty(it.value.coords.source.code) }
        noColumn.setCellValueFactory { SimpleStringProperty(it.value.coords.no.toString()) }
        typeColumn.setCellValueFactory { SimpleStringProperty(eventTypeLabel(it.value.type)) }
        kindColumn.setCellValueFactory { SimpleStringProperty(objectKindLabel(it.value.content.objectKind)) }
        uidColumn.setCellValueFactory { SimpleStringProperty(it.value.uuid.id) }
        eventsTable.selectionModel.selectedItemProperty().addListener { _, _, event -> showDetail(event) }
    }

    private fun eventTypeLabel(type: EventType): String = when (type) {
        EventType.NEW -> "Новое"
        EventType.UPDATE -> "Изменение"
    }

    private fun objectKindLabel(kind: ObjectKind): String = when (kind) {
        ObjectKind.CURRENCY -> "Валюта"
        ObjectKind.ACCOUNT -> "Счёт"
        ObjectKind.TRANSACTION -> "Операция"
        ObjectKind.SUBACCOUNT -> "Субсчёт"
        ObjectKind.CURRENCY_EXCHANGE_RATE -> "Курс валют"
    }

    private fun showDetail(event: ActualEvent?) {
        val content = event?.content
        val isJson = content != null && content !is CurrencyContent && content !is AccountContent && content !is TransactionContent
        setVisible(placeholderLabel, content == null)
        setVisible(currencyDetail, content is CurrencyContent)
        setVisible(accountDetail, content is AccountContent)
        setVisible(transactionDetail, content is TransactionContent)
        setVisible(jsonDetail, isJson)
        when (content) {
            is CurrencyContent -> currencyDetailController.showReadOnly(content)
            is AccountContent -> accountDetailController.showReadOnly(content)
            is TransactionContent -> transactionDetailController.showReadOnly(content)
            null -> {}
            else -> jsonDetailController.showReadOnly(content)
        }
    }

    private fun setVisible(node: Node, visible: Boolean) {
        node.isVisible = visible
        node.isManaged = visible
    }

    // --- filter / paging ---

    private fun currentFilter(): EventDao.Filter = EventDao.Filter(
        place = placeFilterField.text.trim().takeIf { it.isNotEmpty() }?.let { Place(it) },
        no = currentNoRange(),
        created = currentCreatedRange(),
        creator = creatorFilterField.text.trim().takeIf { it.isNotEmpty() },
        type = typeFilterComboBox.value,
        contentPart = contentPartFilterField.text.trim().takeIf { it.isNotEmpty() },
        objectKind = kindFilterComboBox.value,
        objectUuid = uidFilterField.text.trim().takeIf { it.isNotEmpty() }?.let { Uuid(it) },
    )

    // ClosedRange<Int> needs both bounds - an unset side is widened to the full possible range
    // instead of leaving that side unfiltered
    private fun currentNoRange(): ClosedRange<Int>? {
        val from = noFromField.text.trim().toIntOrNull()
        val to = noToField.text.trim().toIntOrNull()
        if (from == null && to == null) return null
        return (from ?: 0)..(to ?: Int.MAX_VALUE)
    }

    // same reasoning as currentNoRange, for the created-date range
    private fun currentCreatedRange(): ClosedRange<Instant>? {
        val (from, to) = currentDateRange()
        if (from == null && to == null) return null
        val start = from?.toStartOfDayInstant() ?: Instant.DISTANT_PAST
        val end = to?.toEndOfDayInstant() ?: Instant.DISTANT_FUTURE
        return start..end
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

    private fun reload(resetPage: Boolean) {
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
        totalCount = runAndShowError { session.eventDao.countByFilter(filter) }.getOrDefault(0)
        pageCount = maxOf(1, ceil(totalCount / PAGE_SIZE.toDouble()).toInt())
        if (resetPage) pageIndex = 0
        if (pageIndex >= pageCount) pageIndex = pageCount - 1
        loadPage(filter)
        resetFilterButton.isDisable = !isFilterDirty()
    }

    private fun isFilterDirty(): Boolean =
        dateRangeComboBox.value != DateRangePreset.ALL ||
            fromDatePicker.value != null ||
            toDatePicker.value != null ||
            placeFilterField.text.isNotBlank() ||
            noFromField.text.isNotBlank() ||
            noToField.text.isNotBlank() ||
            creatorFilterField.text.isNotBlank() ||
            typeFilterComboBox.value != null ||
            kindFilterComboBox.value != null ||
            contentPartFilterField.text.isNotBlank() ||
            uidFilterField.text.isNotBlank()

    private fun goToPage(index: Int) {
        if (index < 0 || index >= pageCount || index == pageIndex) return
        pageIndex = index
        loadPage(currentFilter())
    }

    private fun loadPage(filter: EventDao.Filter) {
        val session = dbService.session ?: return
        val prevSelected = eventsTable.selectionModel.selectedItem
        val query = EventDao.Query(
            filter = filter,
            offset = pageIndex * PAGE_SIZE,
            limit = PAGE_SIZE,
            sortByDateAsc = sortByDateAsc,
        )
        val loaded = runAndShowError { session.eventDao.getByQuery(query) }.getOrDefault(emptyList())
        pageItemCount = loaded.size
        rows.setAll(loaded)
        if (prevSelected != null) {
            rows.firstOrNull { it.coords == prevSelected.coords }?.let(eventsTable.selectionModel::select)
        }
        if (eventsTable.selectionModel.selectedItem == null) showDetail(null)
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

    private fun resetFilter() {
        dateRangeComboBox.selectionModel.select(DateRangePreset.ALL)
        fromDatePicker.value = null
        toDatePicker.value = null
        placeFilterField.clear()
        noFromField.clear()
        noToField.clear()
        creatorFilterField.clear()
        typeFilterComboBox.selectionModel.select(null as EventType?)
        kindFilterComboBox.selectionModel.select(null as ObjectKind?)
        contentPartFilterField.clear()
        uidFilterField.clear()
        sortByDateAsc = false
        dateColumn.sortType = TableColumn.SortType.DESCENDING
        reload(resetPage = true)
    }
}
