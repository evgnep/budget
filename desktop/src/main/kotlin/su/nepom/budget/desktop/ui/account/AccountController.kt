package su.nepom.budget.desktop.ui.account

import jakarta.inject.Inject
import javafx.beans.binding.Bindings
import javafx.beans.property.Property
import javafx.beans.property.SimpleObjectProperty
import javafx.collections.FXCollections
import javafx.collections.transformation.FilteredList
import javafx.collections.transformation.SortedList
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.ListView
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.TextField
import javafx.scene.layout.VBox
import javafx.util.StringConverter
import su.nepom.budget.desktop.model.AccountObservable
import su.nepom.budget.desktop.model.CurrencyObservable
import su.nepom.budget.desktop.service.AccountService
import su.nepom.budget.desktop.service.CurrencyService
import su.nepom.budget.desktop.service.DbService
import su.nepom.budget.desktop.util.fx.Controller
import su.nepom.budget.desktop.util.fx.FormDriver
import su.nepom.budget.desktop.util.fx.MasterDetailFormDriver
import su.nepom.budget.desktop.util.fx.table.CheckBoxTableCell
import su.nepom.budget.model.AccountKind
import su.nepom.budget.model.CurrencyId
import su.nepom.budget.model.Uuid
import java.net.URL
import java.util.*
import kotlin.jvm.optionals.getOrNull

@Suppress("unused", "UNCHECKED_CAST")
class AccountController @Inject constructor(
    private val dbService: DbService,
    private val accountService: AccountService,
    private val currencyService: CurrencyService,
) : Controller, Initializable {
    private val accounts = FilteredList(accountService.accounts) { !it.content.hidden }
    private val accountsSorted = SortedList(accounts)
    private val visibleCurrencies = FilteredList(currencyService.currencies) { !it.content.hidden }
    private lateinit var masterDetailFormDriver: MasterDetailFormDriver<AccountObservable>

    @FXML
    private lateinit var idTextField: TextField

    @FXML
    private lateinit var createNewButton: Button

    @FXML
    private lateinit var cancelButton: Button

    @FXML
    private lateinit var okButton: Button

    @FXML
    private lateinit var nameTextField: TextField

    @FXML
    private lateinit var descriptionTextField: TextField

    @FXML
    private lateinit var currencyComboBox: ComboBox<CurrencyObservable>

    @FXML
    private lateinit var kindComboBox: ComboBox<AccountKind>

    @FXML
    private lateinit var hiddenCheckbox: CheckBox

    @FXML
    private lateinit var accountsTableView: TableView<AccountObservable>

    @FXML
    private lateinit var nameColumn: TableColumn<AccountObservable, String>

    @FXML
    private lateinit var currencyColumn: TableColumn<AccountObservable, String>

    @FXML
    private lateinit var kindColumn: TableColumn<AccountObservable, String>

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

    @FXML
    private lateinit var tagsEditorBox: VBox

    @FXML
    private lateinit var tagsListView: ListView<String>

    @FXML
    private lateinit var tagInputComboBox: ComboBox<String>

    @FXML
    private lateinit var addTagButton: Button

    @FXML
    private lateinit var removeTagButton: Button

    private val tagsProperty = SimpleObjectProperty<Set<String>>(this, "tags", emptySet())

    private val currencyConverter = object : StringConverter<CurrencyObservable>() {
        override fun toString(currency: CurrencyObservable?) = currency?.content?.name ?: ""
        override fun fromString(string: String?): CurrencyObservable? = null
    }

    private val kindConverter = object : StringConverter<AccountKind>() {
        override fun toString(kind: AccountKind?) = kindText(kind)
        override fun fromString(string: String?): AccountKind? = null
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
        currencyComboBox.items = visibleCurrencies
        currencyComboBox.converter = currencyConverter
        kindComboBox.items = FXCollections.observableArrayList(*AccountKind.entries.toTypedArray())
        kindComboBox.converter = kindConverter
        tagFilterComboBox.items = accountService.tags
        tagInputComboBox.items = accountService.tags

        setupTagsEditor()

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
            FormDriver.builder(
                okButton,
                cancelButton,
                accountService.accountFactory,
                dbService.sessionProperty)
                .idField(idTextField)
                .field(
                    "name",
                    nameTextField,
                    nameTextField.textProperty(),
                    { it?.content?.name ?: "" },
                    { name = it }) {
                    withMethod {
                        if (nameTextField.text.isEmpty()) it.error("Название не должно быть пустым")
                    }.immediateClear()
                }
                .field(
                    "description",
                    descriptionTextField,
                    descriptionTextField.textProperty(),
                    { it?.content?.description ?: "" },
                    { description = it })
                .field(
                    "currency",
                    currencyComboBox,
                    currencyComboBox.valueProperty() as Property<CurrencyObservable?>,
                    { account -> account?.content?.currency?.let { currencyService.currencies[it.uuid] } },
                    { currency = it?.content?.id ?: CurrencyId(Uuid.NULL) },
                    disabledInEditMode = true) {
                    withMethod {
                        if (currencyComboBox.value == null) it.error("Выберите валюту")
                    }.immediateClear()
                }
                .field(
                    "kind",
                    kindComboBox,
                    kindComboBox.valueProperty(),
                    { it?.content?.kind ?: AccountKind.MONEY },
                    { kind = it },
                    disabledInEditMode = true)
                .field(
                    "hidden",
                    hiddenCheckbox,
                    hiddenCheckbox.selectedProperty(),
                    { it?.content?.hidden ?: false },
                    { hidden = it })
                .field(
                    "tags",
                    tagsEditorBox,
                    tagsProperty,
                    { it?.content?.tags ?: emptySet() },
                    { tags = it })
                .build(),
            createNewButton
        )
    }

    private fun updateFilter() {
        val nameFilter = nameFilterTextField.text.trim().lowercase()
        val currencyFilter = currencyFilterComboBox.value?.uuid
        val tagFilter = tagFilterComboBox.value
        val showHidden = showHiddenCheckbox.isSelected
        accounts.setPredicate { account ->
            (showHidden || !account.content.hidden) &&
                (nameFilter.isEmpty() || account.content.name.lowercase().contains(nameFilter)) &&
                (currencyFilter == null || account.content.currency.uuid == currencyFilter) &&
                (tagFilter == null || tagFilter in account.content.tags)
        }
    }

    private fun setupTagsEditor() {
        tagsProperty.addListener { _, _, value ->
            val wanted = value?.sorted() ?: emptyList()
            if (tagsListView.items.toList() != wanted) tagsListView.items.setAll(wanted)
        }
        addTagButton.setOnAction { addTag() }
        removeTagButton.setOnAction { removeTag() }
        tagInputComboBox.editor.setOnAction { addTag() }
    }

    private fun addTag() {
        val tag = (tagInputComboBox.value ?: tagInputComboBox.editor.text).trim()
        if (tag.isEmpty()) return
        if (tag !in accountService.tags && !confirmNewTag()) return
        if (tag !in tagsListView.items) tagsListView.items.add(tag)
        tagInputComboBox.editor.clear()
        tagInputComboBox.value = null
        tagsProperty.set(tagsListView.items.toSortedSet())
    }

    private fun confirmNewTag(): Boolean =
        Alert(Alert.AlertType.CONFIRMATION, "Это новый тег. Добавить?", ButtonType.YES, ButtonType.NO)
            .showAndWait().getOrNull() == ButtonType.YES

    private fun removeTag() {
        val selected = tagsListView.selectionModel.selectedItem ?: return
        tagsListView.items.remove(selected)
        tagsProperty.set(tagsListView.items.toSortedSet())
    }

    private fun updateCurrenciesFilter() {
        val showHidden = showHiddenCheckbox.isSelected
        visibleCurrencies.setPredicate { showHidden || !it.content.hidden }
    }

    private fun kindText(kind: AccountKind?): String = when (kind) {
        AccountKind.MONEY -> "Деньги"
        AccountKind.BUDGET -> "Бюджет"
        null -> ""
    }
}
