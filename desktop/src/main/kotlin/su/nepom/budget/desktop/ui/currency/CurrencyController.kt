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
import javafx.scene.control.TextField
import su.nepom.budget.desktop.model.CurrencyObservable
import su.nepom.budget.desktop.service.CurrencyService
import su.nepom.budget.desktop.service.DbService
import su.nepom.budget.desktop.util.fx.Controller
import su.nepom.budget.desktop.util.fx.FormDriver
import su.nepom.budget.desktop.util.fx.MasterDetailFormDriver
import su.nepom.budget.desktop.util.fx.table.CheckBoxTableCell
import java.net.URL
import java.util.*

@Suppress("unused")
class CurrencyController @Inject constructor(
    private val dbService: DbService,
    private val currencyService: CurrencyService,
) : Controller, Initializable {
    private val currencies = FilteredList(currencyService.currencies) { !it.content.hidden }
    private val currenciesSorted = SortedList(currencies)
    private lateinit var masterDetailFormDriver: MasterDetailFormDriver<CurrencyObservable>

    @FXML
    private lateinit var idTextField: TextField

    @FXML
    private lateinit var createNewButton: Button

    @FXML
    private lateinit var hiddenCheckbox: CheckBox

    @FXML
    private lateinit var cancelButton: Button

    @FXML
    private lateinit var okButton: Button

    @FXML
    private lateinit var digitsAfterPointTextField: TextField

    @FXML
    private lateinit var codeTextField: TextField

    @FXML
    private lateinit var nameTextField: TextField

    @FXML
    private lateinit var hiddenColumn: TableColumn<CurrencyObservable, Boolean>

    @FXML
    private lateinit var digitsAfterPointColumn: TableColumn<CurrencyObservable, Int>

    @FXML
    private lateinit var codeColumn: TableColumn<CurrencyObservable, String>

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
        digitsAfterPointColumn.setCellValueFactory { it.value.digitsAfterPoint }
        hiddenColumn.visibleProperty().bind(showHiddenCheckbox.selectedProperty())
        hiddenColumn.setCellValueFactory { it.value.hidden }
        hiddenColumn.cellFactory = CheckBoxTableCell.forTableColumn(hiddenColumn)
        masterDetailFormDriver = MasterDetailFormDriver(
            currenciesTableView.selectionModel,
            FormDriver.builder(
                okButton,
                cancelButton,
                currencyService.currencyFactory,
                dbService.sessionProperty)
                .idField(idTextField)
                .field(
                    "name",
                    nameTextField,
                    nameTextField.textProperty(),
                    { it?.content?.name ?: "" },
                    { name = it }) {
                    withMethod {
                        if (nameTextField.text.isEmpty()) it.error(" Название не должно быть пустым")
                    }.immediateClear()
                }
                .field(
                    "officialCode",
                    codeTextField,
                    codeTextField.textProperty(),
                    { it?.content?.officialCode ?: "" },
                    { officialCode = it }) {
                    withMethod {
                        if (codeTextField.text.isEmpty()) it.error("Код не должен быть пустым")
                    }.immediateClear()
                }
                .field(
                    "digitsAfterPoint",
                    digitsAfterPointTextField,
                    digitsAfterPointTextField.textProperty(),
                    { it?.content?.digitsAfterPoint?.toString() ?: "2" },
                    { digitsAfterPoint = it.toInt() },
                    disabledInEditMode = true) {
                    withMethod {
                        val value = digitsAfterPointTextField.text.toIntOrNull()
                        if (value == null || value < 0 || value > 9)
                            it.error("Число знаков после запятой должно быть от 0 до 9")
                    }.immediateClear()
                }
                .field(
                    "hidden",
                    hiddenCheckbox,
                    hiddenCheckbox.selectedProperty(),
                    { it?.content?.hidden ?: false },
                    { hidden = it })
                .build(),
            createNewButton
        )
    }
}