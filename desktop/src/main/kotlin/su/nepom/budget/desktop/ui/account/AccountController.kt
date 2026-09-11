package su.nepom.budget.desktop.ui.account

import jakarta.inject.Inject
import javafx.beans.binding.Bindings
import javafx.collections.transformation.FilteredList
import javafx.collections.transformation.SortedList
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.SplitPane
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.TextField
import javafx.stage.Stage
import javafx.util.StringConverter
import su.nepom.budget.desktop.model.AccountObservable
import su.nepom.budget.desktop.model.CurrencyObservable
import su.nepom.budget.desktop.service.AccountService
import su.nepom.budget.desktop.service.CurrencyService
import su.nepom.budget.desktop.service.WindowStateService
import su.nepom.budget.desktop.ui.history.History
import su.nepom.budget.desktop.util.fx.Controller
import su.nepom.budget.desktop.util.fx.MasterDetailFormDriver
import su.nepom.budget.desktop.util.fx.StageAwareController
import su.nepom.budget.desktop.util.fx.table.CheckBoxTableCell
import su.nepom.budget.model.AccountKind
import su.nepom.budget.model.ObjectKind
import java.net.URL
import java.util.*

@Suppress("unused", "UNCHECKED_CAST")
class AccountController @Inject constructor(
    private val accountService: AccountService,
    private val currencyService: CurrencyService,
    private val history: History,
    private val windowStateService: WindowStateService,
) : Controller, Initializable, StageAwareController {

    private companion object {
        const val NAME = "main"
    }

    private val accounts = FilteredList(accountService.accounts) { !it.content.hidden }
    private val accountsSorted = SortedList(accounts)
    private val visibleCurrencies = FilteredList(currencyService.currencies) { !it.content.hidden }
    private lateinit var masterDetailFormDriver: MasterDetailFormDriver<AccountObservable, AccountObservable>

    @FXML
    private lateinit var accountDetailController: AccountDetailController

    @FXML
    private lateinit var accountsSplitter: SplitPane

    @FXML
    private lateinit var createNewButton: Button

    @FXML
    private lateinit var historyButton: Button

    @FXML
    private lateinit var accountsTableView: TableView<AccountObservable>

    @FXML
    private lateinit var nameColumn: TableColumn<AccountObservable, String>

    @FXML
    private lateinit var currencyColumn: TableColumn<AccountObservable, String>

    @FXML
    private lateinit var kindColumn: TableColumn<AccountObservable, String>

    @FXML
    private lateinit var groupColumn: TableColumn<AccountObservable, String>

    @FXML
    private lateinit var hiddenColumn: TableColumn<AccountObservable, Boolean>

    @FXML
    private lateinit var orderNoColumn: TableColumn<AccountObservable, Int>

    @FXML
    private lateinit var tagsColumn: TableColumn<AccountObservable, String>

    @FXML
    private lateinit var nameFilterTextField: TextField

    @FXML
    private lateinit var currencyFilterComboBox: ComboBox<CurrencyObservable>

    @FXML
    private lateinit var tagFilterComboBox: ComboBox<String>

    @FXML
    private lateinit var showHiddenCheckbox: CheckBox

    @FXML
    private lateinit var resetFilterButton: Button

    private val currencyConverter = object : StringConverter<CurrencyObservable>() {
        override fun toString(currency: CurrencyObservable?) = currency?.content?.name ?: ""
        override fun fromString(string: String?): CurrencyObservable? = null
    }

    override fun initialize(location: URL?, resources: ResourceBundle?) {
        accountsTableView.items = accountsSorted
        accountsSorted.comparatorProperty().bind(accountsTableView.comparatorProperty())
        nameColumn.sortType = TableColumn.SortType.ASCENDING
        nameColumn.setCellValueFactory { it.value.name }
        currencyColumn.setCellValueFactory { it.value.currencyName }
        kindColumn.setCellValueFactory {
            Bindings.createStringBinding({ kindText(it.value.content.kind) }, it.value.contentProperty)
        }
        groupColumn.setCellValueFactory {
            Bindings.createStringBinding({ groupText(it.value.content.groupPath) }, it.value.contentProperty)
        }
        hiddenColumn.setCellValueFactory { it.value.hidden }
        hiddenColumn.cellFactory = CheckBoxTableCell.forTableColumn(hiddenColumn)
        orderNoColumn.setCellValueFactory { it.value.orderNo }
        tagsColumn.setCellValueFactory {
            Bindings.createStringBinding(
                { it.value.content.tags.sorted().joinToString(", ") },
                it.value.contentProperty
            )
        }

        currencyFilterComboBox.items = visibleCurrencies
        currencyFilterComboBox.converter = currencyConverter
        tagFilterComboBox.items = accountService.tags

        nameFilterTextField.textProperty().addListener { _, _, _ -> updateFilter() }
        currencyFilterComboBox.valueProperty().addListener { _, _, _ -> updateFilter() }
        tagFilterComboBox.valueProperty().addListener { _, _, _ -> updateFilter() }
        showHiddenCheckbox.selectedProperty().addListener { _, _, _ ->
            updateCurrenciesFilter()
            updateFilter()
        }
        updateCurrenciesFilter()
        resetFilterButton.setOnAction {
            nameFilterTextField.clear()
            currencyFilterComboBox.value = null
            tagFilterComboBox.value = null
        }

        masterDetailFormDriver = MasterDetailFormDriver(
            accountsTableView.selectionModel,
            accountDetailController.formDriver,
            createNewButton
        )

        historyButton.disableProperty()
            .bind(accountsTableView.selectionModel.selectedItemProperty().isNull)
        historyButton.setOnAction {
            val selected = accountsTableView.selectionModel.selectedItem ?: return@setOnAction
            history.show(
                selected.uuid,
                ObjectKind.ACCOUNT,
                "История счёта: ${selected.content.name}",
            )
        }
    }

    override fun initialize(stage: Stage) {
        windowStateService.bindSplitPane(stage, NAME, accountsSplitter)
        windowStateService.bindTableColumns(NAME, accountsTableView)
    }

    private fun updateFilter() {
        val nameFilter = nameFilterTextField.text.trim().lowercase()
        val currencyFilter = currencyFilterComboBox.value?.uuid
        val tagFilter = tagFilterComboBox.value
        val showHidden = showHiddenCheckbox.isSelected
        accounts.setPredicate { account ->
            (showHidden || !account.content.hidden) &&
                (nameFilter.isEmpty() ||
                    account.content.name.lowercase().contains(nameFilter) ||
                    groupText(account.content.groupPath).lowercase().contains(nameFilter)) &&
                (currencyFilter == null || account.content.currency.uuid == currencyFilter) &&
                (tagFilter == null || tagFilter in account.content.tags)
        }
    }

    private fun groupText(groupPath: List<String>): String = groupPath.joinToString("/")

    private fun updateCurrenciesFilter() {
        val showHidden = showHiddenCheckbox.isSelected
        visibleCurrencies.setPredicate { showHidden || !it.content.hidden }
        accountDetailController.setShowHiddenCurrencies(showHidden)
    }

    private fun kindText(kind: AccountKind?): String = when (kind) {
        AccountKind.MONEY -> "Деньги"
        AccountKind.BUDGET -> "Бюджет"
        null -> ""
    }
}
