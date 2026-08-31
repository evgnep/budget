package su.nepom.budget.desktop.ui.transaction

import jakarta.inject.Inject
import javafx.application.Platform
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.collections.transformation.FilteredList
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.SelectionMode
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.TextField
import javafx.scene.control.cell.CheckBoxTableCell
import javafx.scene.input.KeyCode
import javafx.util.StringConverter
import su.nepom.budget.desktop.model.AccountObservable
import su.nepom.budget.desktop.model.CurrencyObservable
import su.nepom.budget.desktop.service.AccountService
import su.nepom.budget.desktop.service.CurrencyService
import su.nepom.budget.desktop.util.fx.Controller
import su.nepom.budget.model.AccountId
import su.nepom.budget.model.AccountKind
import su.nepom.budget.model.CurrencyId
import java.net.URL
import java.util.ResourceBundle

@Suppress("unused")
class AccountPickerController @Inject constructor(
    private val accountService: AccountService,
    private val currencyService: CurrencyService,
) : Controller, Initializable {

    class Row(val account: AccountObservable, val currencyName: String) {
        val selected = SimpleBooleanProperty(false)
        val name: String get() = account.content.name
        val description: String get() = account.content.description
        val tags: String get() = account.content.tags.sorted().joinToString(", ")
        val kind: String
            get() = when (account.content.kind) {
                AccountKind.MONEY -> "Деньги"
                AccountKind.BUDGET -> "Бюджет"
            }
    }

    private val allRows = FXCollections.observableArrayList<Row>()
    private val filtered = FilteredList(allRows)

    private var multi = true
    private var onDone: ((Set<AccountId>?) -> Unit)? = null
    private var preselectedIds: Set<String> = emptySet()
    private var lockedCurrency: CurrencyId? = null
    private var lockedKind: AccountKind? = null

    @FXML
    private lateinit var nameFilterTextField: TextField

    @FXML
    private lateinit var currencyFilterLabel: Label

    @FXML
    private lateinit var currencyFilterComboBox: ComboBox<CurrencyObservable>

    @FXML
    private lateinit var tagFilterComboBox: ComboBox<String>

    @FXML
    private lateinit var showHiddenCheckbox: CheckBox

    @FXML
    private lateinit var resetFilterButton: Button

    @FXML
    private lateinit var selectAllButton: Button

    @FXML
    private lateinit var clearAllButton: Button

    @FXML
    private lateinit var accountsTableView: TableView<Row>

    @FXML
    private lateinit var selectedColumn: TableColumn<Row, Boolean>

    @FXML
    private lateinit var nameColumn: TableColumn<Row, String>

    @FXML
    private lateinit var currencyColumn: TableColumn<Row, String>

    @FXML
    private lateinit var kindColumn: TableColumn<Row, String>

    @FXML
    private lateinit var tagsColumn: TableColumn<Row, String>

    @FXML
    private lateinit var descriptionColumn: TableColumn<Row, String>

    @FXML
    private lateinit var countLabel: Label

    @FXML
    private lateinit var okButton: Button

    @FXML
    private lateinit var cancelButton: Button

    private val currencyConverter = object : StringConverter<CurrencyObservable>() {
        override fun toString(currency: CurrencyObservable?) = currency?.content?.name ?: ""
        override fun fromString(string: String?): CurrencyObservable? = null
    }

    fun configure(
        preselected: Set<AccountId>,
        multi: Boolean,
        currency: CurrencyId? = null,
        kind: AccountKind? = null,
        onDone: (Set<AccountId>?) -> Unit,
    ) {
        this.multi = multi
        this.onDone = onDone
        this.preselectedIds = preselected.map { it.uuid.id }.toSet()
        this.lockedCurrency = currency
        this.lockedKind = kind
    }

    override fun initialize(location: URL?, resources: ResourceBundle?) {
        buildRows()

        accountsTableView.items = filtered
        accountsTableView.isEditable = true
        accountsTableView.selectionModel.selectionMode = if (multi) SelectionMode.MULTIPLE else SelectionMode.SINGLE

        selectedColumn.isVisible = multi
        selectedColumn.setCellValueFactory { it.value.selected }
        selectedColumn.cellFactory = CheckBoxTableCell.forTableColumn(selectedColumn)
        selectedColumn.isEditable = true
        nameColumn.setCellValueFactory { SimpleStringProperty(it.value.name) }
        currencyColumn.setCellValueFactory { SimpleStringProperty(it.value.currencyName) }
        kindColumn.setCellValueFactory { SimpleStringProperty(it.value.kind) }
        tagsColumn.setCellValueFactory { SimpleStringProperty(it.value.tags) }
        descriptionColumn.setCellValueFactory { SimpleStringProperty(it.value.description) }

        currencyFilterComboBox.items = currencyService.currencies
        currencyFilterComboBox.converter = currencyConverter
        tagFilterComboBox.items = accountService.tags

        // currency is fixed from the caller - the user filter is pointless, hide it
        if (lockedCurrency != null) {
            listOf(currencyFilterLabel, currencyFilterComboBox).forEach {
                it.isVisible = false
                it.isManaged = false
            }
        }

        nameFilterTextField.textProperty().addListener { _, _, _ -> updateFilter() }
        currencyFilterComboBox.valueProperty().addListener { _, _, _ -> updateFilter() }
        tagFilterComboBox.valueProperty().addListener { _, _, _ -> updateFilter() }
        showHiddenCheckbox.selectedProperty().addListener { _, _, _ -> updateFilter() }
        allRows.forEach { row -> row.selected.addListener { _, _, _ -> updateCountLabel() } }
        updateFilter()

        resetFilterButton.setOnAction {
            nameFilterTextField.clear()
            currencyFilterComboBox.value = null
            tagFilterComboBox.value = null
            showHiddenCheckbox.isSelected = false
        }
        selectAllButton.setOnAction { filtered.forEach { it.selected.set(true) } }
        clearAllButton.setOnAction { allRows.forEach { it.selected.set(false) } }
        selectAllButton.isVisible = multi
        clearAllButton.isVisible = multi

        accountsTableView.setRowFactory {
            javafx.scene.control.TableRow<Row>().apply {
                setOnMouseClicked { e ->
                    if (e.clickCount == 2 && !isEmpty && !multi) finish(setOf(AccountId(item.account.uuid)))
                }
            }
        }

        okButton.setOnAction { onOk() }
        cancelButton.setOnAction { finish(null) }
        updateCountLabel()

        // Tab from the filter field jumps straight to the table, so the user can type
        // a filter, press Tab and pick an account with the arrow keys + Enter.
        nameFilterTextField.setOnKeyPressed { e ->
            if (e.code == KeyCode.TAB && !e.isShiftDown) {
                focusTable()
                e.consume()
            }
        }
        Platform.runLater { nameFilterTextField.requestFocus() }
    }

    private fun focusTable() {
        if (accountsTableView.selectionModel.selectedItem == null && filtered.isNotEmpty()) {
            accountsTableView.selectionModel.select(0)
        }
        accountsTableView.requestFocus()
    }

    private fun buildRows() {
        val rows = accountService.accounts.map { account ->
            val currencyName = currencyService.currencies[account.content.currency.uuid]?.content?.name ?: "-"
            Row(account, currencyName).apply { selected.set(account.uuid.id in preselectedIds) }
        }
        allRows.setAll(rows)
    }

    private fun updateFilter() {
        val nameFilter = nameFilterTextField.text.trim().lowercase()
        val currencyFilter = currencyFilterComboBox.value?.uuid
        val tagFilter = tagFilterComboBox.value
        val showHidden = showHiddenCheckbox.isSelected
        val lockedCurrency = lockedCurrency
        val lockedKind = lockedKind
        filtered.setPredicate { row ->
            val content = row.account.content
            (lockedCurrency == null || content.currency == lockedCurrency) &&
                (lockedKind == null || content.kind == lockedKind) &&
                (showHidden || !content.hidden) &&
                (nameFilter.isEmpty() ||
                    content.name.lowercase().contains(nameFilter) ||
                    content.description.lowercase().contains(nameFilter)) &&
                (currencyFilter == null || content.currency.uuid == currencyFilter) &&
                (tagFilter == null || tagFilter in content.tags)
        }
    }

    private fun onOk() {
        if (multi) {
            finish(allRows.filter { it.selected.get() }.map { AccountId(it.account.uuid) }.toSet())
        } else {
            val selected = accountsTableView.selectionModel.selectedItem
            finish(if (selected == null) emptySet() else setOf(AccountId(selected.account.uuid)))
        }
    }

    private fun finish(result: Set<AccountId>?) {
        onDone?.invoke(result)
    }

    private fun updateCountLabel() {
        if (multi) {
            val n = allRows.count { it.selected.get() }
            countLabel.text = "Выбрано: $n"
        } else {
            countLabel.text = ""
        }
    }
}
