package su.nepom.budget.desktop.ui.currency

import jakarta.inject.Inject
import javafx.collections.transformation.FilteredList
import javafx.collections.transformation.SortedList
import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import su.nepom.budget.desktop.model.CurrencyObservable
import su.nepom.budget.desktop.service.CurrencyService
import su.nepom.budget.desktop.ui.history.History
import su.nepom.budget.desktop.util.fx.Controller
import su.nepom.budget.desktop.util.fx.MasterDetailFormDriver
import su.nepom.budget.desktop.util.fx.table.CheckBoxTableCell
import su.nepom.budget.model.ObjectKind
import java.net.URL
import java.util.*

@Suppress("unused")
class CurrencyController @Inject constructor(
    private val currencyService: CurrencyService,
    private val history: History,
) : Controller, Initializable {
    private val currencies = FilteredList(currencyService.currencies) { !it.content.hidden }
    private val currenciesSorted = SortedList(currencies)
    private lateinit var masterDetailFormDriver: MasterDetailFormDriver<CurrencyObservable, CurrencyObservable>

    @FXML
    private lateinit var currencyDetailController: CurrencyDetailController

    @FXML
    private lateinit var createNewButton: Button

    @FXML
    private lateinit var historyButton: Button

    @FXML
    private lateinit var hiddenColumn: TableColumn<CurrencyObservable, Boolean>

    @FXML
    private lateinit var digitsAfterPointColumn: TableColumn<CurrencyObservable, Int>

    @FXML
    private lateinit var codeColumn: TableColumn<CurrencyObservable, String>

    @FXML
    private lateinit var symbolColumn: TableColumn<CurrencyObservable, String>

    @FXML
    private lateinit var nameColumn: TableColumn<CurrencyObservable, String>

    @FXML
    private lateinit var currenciesTableView: TableView<CurrencyObservable>

    @FXML
    private lateinit var showHiddenCheckbox: CheckBox

    @FXML
    private fun onShowHiddenAction(actionEvent: ActionEvent) {
        currencies.setPredicate { if (showHiddenCheckbox.isSelected) true else !it.content.hidden }
    }

    override fun initialize(location: URL?, resources: ResourceBundle?) {
        currenciesTableView.items = currenciesSorted
        currenciesSorted.comparatorProperty().bind(currenciesTableView.comparatorProperty())
        nameColumn.sortType = TableColumn.SortType.ASCENDING
        nameColumn.setCellValueFactory { it.value.name }
        codeColumn.setCellValueFactory { it.value.officialCode }
        symbolColumn.setCellValueFactory { it.value.symbol }
        digitsAfterPointColumn.setCellValueFactory { it.value.digitsAfterPoint }
        hiddenColumn.visibleProperty().bind(showHiddenCheckbox.selectedProperty())
        hiddenColumn.setCellValueFactory { it.value.hidden }
        hiddenColumn.cellFactory = CheckBoxTableCell.forTableColumn(hiddenColumn)
        masterDetailFormDriver = MasterDetailFormDriver(
            currenciesTableView.selectionModel,
            currencyDetailController.formDriver,
            createNewButton
        )

        historyButton.disableProperty()
            .bind(currenciesTableView.selectionModel.selectedItemProperty().isNull)
        historyButton.setOnAction {
            val selected = currenciesTableView.selectionModel.selectedItem ?: return@setOnAction
            history.show(
                selected.uuid,
                ObjectKind.CURRENCY,
                "История валюты: ${selected.content.name}",
            )
        }
    }
}
