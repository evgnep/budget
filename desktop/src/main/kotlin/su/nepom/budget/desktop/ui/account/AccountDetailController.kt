package su.nepom.budget.desktop.ui.account

import jakarta.inject.Inject
import javafx.beans.property.Property
import javafx.beans.property.SimpleObjectProperty
import javafx.collections.FXCollections
import javafx.collections.transformation.FilteredList
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ListView
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.TextField
import javafx.scene.control.cell.TextFieldTableCell
import javafx.scene.layout.VBox
import javafx.util.StringConverter
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import su.nepom.budget.desktop.model.AccountObservable
import su.nepom.budget.desktop.model.CurrencyObservable
import su.nepom.budget.desktop.service.AccountService
import su.nepom.budget.desktop.service.CurrencyService
import su.nepom.budget.desktop.service.DbService
import su.nepom.budget.desktop.util.fx.Controller
import su.nepom.budget.desktop.util.fx.FormDriver
import su.nepom.budget.event.AccountBudget
import su.nepom.budget.event.AccountContent
import su.nepom.budget.event.DailyAllowance
import su.nepom.budget.event.Reserve
import su.nepom.budget.model.AccountKind
import su.nepom.budget.model.CurrencyId
import su.nepom.budget.model.RawMoney
import su.nepom.budget.model.RestMark
import su.nepom.budget.model.Uuid
import su.nepom.budget.utils.format
import su.nepom.budget.utils.toRawMoneyOrNull
import java.net.URL
import java.time.LocalDate
import java.util.*
import kotlin.jvm.optionals.getOrNull

@Suppress("unused", "UNCHECKED_CAST")
class AccountDetailController @Inject constructor(
    private val dbService: DbService,
    private val accountService: AccountService,
    private val currencyService: CurrencyService,
) : Controller, Initializable {

    private val visibleCurrencies = FilteredList(currencyService.currencies) { !it.content.hidden }

    @FXML
    private lateinit var idTextField: TextField

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
    private lateinit var showOnMainCheckbox: CheckBox

    @FXML
    private lateinit var restMarkComboBox: ComboBox<RestMark>

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

    @FXML
    private lateinit var groupPathField: TextField

    @FXML
    private lateinit var budgetSectionLabel: Label

    @FXML
    private lateinit var budgetEditorBox: VBox

    @FXML
    private lateinit var replenishDayField: TextField

    @FXML
    private lateinit var allowancesTable: TableView<PeriodRow>

    @FXML
    private lateinit var allowanceAmountColumn: TableColumn<PeriodRow, String>

    @FXML
    private lateinit var allowanceFromColumn: TableColumn<PeriodRow, String>

    @FXML
    private lateinit var allowanceToColumn: TableColumn<PeriodRow, String>

    @FXML
    private lateinit var addAllowanceButton: Button

    @FXML
    private lateinit var removeAllowanceButton: Button

    @FXML
    private lateinit var reservesTable: TableView<PeriodRow>

    @FXML
    private lateinit var reserveAmountColumn: TableColumn<PeriodRow, String>

    @FXML
    private lateinit var reserveFromColumn: TableColumn<PeriodRow, String>

    @FXML
    private lateinit var reserveToColumn: TableColumn<PeriodRow, String>

    @FXML
    private lateinit var addReserveButton: Button

    @FXML
    private lateinit var removeReserveButton: Button

    private class PeriodRow(amount: String, from: LocalDate?, to: LocalDate?) {
        val amount = SimpleObjectProperty(this, "amount", amount)
        val from = SimpleObjectProperty(this, "from", from)
        val to = SimpleObjectProperty(this, "to", to)
    }

    private val tagsProperty = SimpleObjectProperty<Set<String>>(this, "tags", emptySet())

    private val budgetProperty = SimpleObjectProperty(this, "budget", AccountBudget.EMPTY)
    private val allowanceRows = FXCollections.observableArrayList<PeriodRow>()
    private val reserveRows = FXCollections.observableArrayList<PeriodRow>()
    private var populatingBudget = false
    private var rebuildingBudget = false

    lateinit var formDriver: FormDriver<*, AccountObservable>
        private set

    private val currencyConverter = object : StringConverter<CurrencyObservable>() {
        override fun toString(currency: CurrencyObservable?) = currency?.content?.name ?: ""
        override fun fromString(string: String?): CurrencyObservable? = null
    }

    private val kindConverter = object : StringConverter<AccountKind>() {
        override fun toString(kind: AccountKind?) = kindText(kind)
        override fun fromString(string: String?): AccountKind? = null
    }

    private val restMarkConverter = object : StringConverter<RestMark>() {
        override fun toString(mark: RestMark?) = restMarkText(mark)
        override fun fromString(string: String?): RestMark? = null
    }

    override fun initialize(location: URL?, resources: ResourceBundle?) {
        currencyComboBox.items = visibleCurrencies
        currencyComboBox.converter = currencyConverter
        kindComboBox.items = FXCollections.observableArrayList(*AccountKind.entries.toTypedArray())
        kindComboBox.converter = kindConverter
        restMarkComboBox.items = FXCollections.observableArrayList(*RestMark.entries.toTypedArray())
        restMarkComboBox.converter = restMarkConverter
        tagInputComboBox.items = accountService.tags

        setupTagsEditor()
        setupBudgetEditor()

        formDriver = FormDriver.builder(
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
                "showOnMain",
                showOnMainCheckbox,
                showOnMainCheckbox.selectedProperty(),
                { it?.content?.showOnMain ?: false },
                { showOnMain = it })
            .field(
                "restMark",
                restMarkComboBox,
                restMarkComboBox.valueProperty(),
                { it?.content?.restMark ?: RestMark.OFF },
                { restMark = it ?: RestMark.OFF })
            .field(
                "groupPath",
                groupPathField,
                groupPathField.textProperty(),
                { it?.content?.groupPath?.joinToString("/") ?: "" },
                { groupPath = parseGroupPath(it) })
            .field(
                "tags",
                tagsEditorBox,
                tagsProperty,
                { it?.content?.tags ?: emptySet() },
                { tags = it })
            .field(
                "budget",
                budgetEditorBox,
                budgetProperty,
                { it?.content?.budget ?: AccountBudget.EMPTY },
                { budget = it }) {
                withMethod { ctx -> validateBudget().forEach { ctx.error(it) } }.immediate()
            }
            .build()
    }

    // called by the host form to keep the currency list in sync with its "show hidden" filter
    fun setShowHiddenCurrencies(showHidden: Boolean) {
        visibleCurrencies.setPredicate { showHidden || !it.content.hidden }
    }

    // edit a version inside the conflict-resolution dialog: OK returns the content, no DB write
    fun editForConflict(
        content: AccountContent,
        onAccept: (AccountContent) -> Unit,
        onCancel: () -> Unit,
    ) {
        setShowHiddenCurrencies(true)
        formDriver.contentSink = { onAccept(it as AccountContent) }
        formDriver.cancelSink = onCancel
        formDriver.editItem(AccountObservable(content, RawMoney.ZERO, currencyService.currencies))
    }

    // show a past version of an account (from the history form), view only
    fun showReadOnly(content: AccountContent) {
        // make sure the account's currency is in the combo list even if it is hidden now
        setShowHiddenCurrencies(true)
        formDriver.showReadOnly(AccountObservable(content, RawMoney.ZERO, currencyService.currencies))
        // keep the tag list usable for selection/copy, just hide the editing controls
        tagsEditorBox.isDisable = false
        listOf(
            tagInputComboBox, addTagButton, removeTagButton,
            addAllowanceButton, removeAllowanceButton, addReserveButton, removeReserveButton,
        ).forEach {
            it.isVisible = false
            it.isManaged = false
        }
        replenishDayField.isEditable = false
        allowancesTable.isEditable = false
        reservesTable.isEditable = false
    }

    // --- budget editor (only for BUDGET accounts, see docs/budget.md) ---

    private fun setupBudgetEditor() {
        allowancesTable.items = allowanceRows
        reservesTable.items = reserveRows
        allowancesTable.isEditable = true
        reservesTable.isEditable = true
        setupPeriodColumns(allowanceAmountColumn, allowanceFromColumn, allowanceToColumn)
        setupPeriodColumns(reserveAmountColumn, reserveFromColumn, reserveToColumn)

        addAllowanceButton.setOnAction { allowanceRows.add(PeriodRow("", null, null)); rebuildBudget() }
        removeAllowanceButton.setOnAction {
            allowancesTable.selectionModel.selectedItem?.let { allowanceRows.remove(it) }
            rebuildBudget()
        }
        addReserveButton.setOnAction { reserveRows.add(PeriodRow("", null, null)); rebuildBudget() }
        removeReserveButton.setOnAction {
            reservesTable.selectionModel.selectedItem?.let { reserveRows.remove(it) }
            rebuildBudget()
        }
        replenishDayField.textProperty().addListener { _, _, _ -> rebuildBudget() }

        // repopulate widgets only on external sets (form load), not on our own rebuildBudget
        budgetProperty.addListener { _, _, value ->
            if (!populatingBudget && !rebuildingBudget) populateBudgetWidgets(value ?: AccountBudget.EMPTY)
        }
        kindComboBox.valueProperty().addListener { _, _, kind -> updateBudgetSectionVisibility(kind) }
        updateBudgetSectionVisibility(kindComboBox.value)
    }

    private fun setupPeriodColumns(
        amountCol: TableColumn<PeriodRow, String>,
        fromCol: TableColumn<PeriodRow, String>,
        toCol: TableColumn<PeriodRow, String>,
    ) {
        amountCol.setCellValueFactory { it.value.amount }
        amountCol.cellFactory = TextFieldTableCell.forTableColumn()
        amountCol.setOnEditCommit { e ->
            e.rowValue?.let { it.amount.set(e.newValue.orEmpty().trim()); rebuildBudget() }
        }
        fromCol.setCellValueFactory { SimpleObjectProperty(it.value.from.get()?.toString() ?: "") }
        fromCol.cellFactory = TextFieldTableCell.forTableColumn()
        fromCol.setOnEditCommit { e ->
            e.rowValue?.let { it.from.set(parseDateOrNull(e.newValue)); rebuildBudget() }
        }
        toCol.setCellValueFactory { SimpleObjectProperty(it.value.to.get()?.toString() ?: "") }
        toCol.cellFactory = TextFieldTableCell.forTableColumn()
        toCol.setOnEditCommit { e ->
            e.rowValue?.let { it.to.set(parseDateOrNull(e.newValue)); rebuildBudget() }
        }
    }

    private fun parseDateOrNull(text: String?): LocalDate? {
        val t = text?.trim().orEmpty()
        return if (t.isEmpty()) null else runCatching { LocalDate.parse(t) }.getOrNull()
    }

    private fun currencyDigits(): Int = currencyComboBox.value?.content?.digitsAfterPoint ?: 2

    private fun rebuildBudget() {
        if (populatingBudget) return
        val digits = currencyDigits()
        rebuildingBudget = true
        try {
            budgetProperty.set(budgetFromWidgets(digits))
        } finally {
            rebuildingBudget = false
        }
    }

    private fun budgetFromWidgets(digits: Int): AccountBudget =
        AccountBudget(
            replenishDay = replenishDayField.text?.trim()?.toIntOrNull(),
            dailyAllowances = allowanceRows.map {
                DailyAllowance(
                    it.amount.get().toRawMoneyOrNull(digits) ?: RawMoney.ZERO,
                    it.from.get()?.toKotlinLocalDate(),
                    it.to.get()?.toKotlinLocalDate(),
                )
            },
            reserves = reserveRows.map {
                Reserve(
                    it.amount.get().toRawMoneyOrNull(digits) ?: RawMoney.ZERO,
                    it.from.get()?.toKotlinLocalDate(),
                    it.to.get()?.toKotlinLocalDate(),
                )
            },
        )

    private fun populateBudgetWidgets(budget: AccountBudget) {
        populatingBudget = true
        try {
            val digits = currencyDigits()
            replenishDayField.text = budget.replenishDay?.toString() ?: ""
            allowanceRows.setAll(budget.dailyAllowances.map {
                PeriodRow(it.amount.format(digits), it.from?.toJavaLocalDate(), it.to?.toJavaLocalDate())
            })
            reserveRows.setAll(budget.reserves.map {
                PeriodRow(it.amount.format(digits), it.from?.toJavaLocalDate(), it.to?.toJavaLocalDate())
            })
        } finally {
            populatingBudget = false
        }
    }

    private fun updateBudgetSectionVisibility(kind: AccountKind?) {
        val show = kind == AccountKind.BUDGET
        listOf(budgetSectionLabel, budgetEditorBox).forEach {
            it.isVisible = show
            it.isManaged = show
        }
    }

    private fun validateBudget(): List<String> {
        if (kindComboBox.value != AccountKind.BUDGET) return emptyList()
        val problems = mutableListOf<String>()
        val dayText = replenishDayField.text?.trim().orEmpty()
        if (dayText.isNotEmpty() && dayText.toIntOrNull()?.let { it in 1..28 } != true) {
            problems.add("День пополнения должен быть числом 1-28")
        }
        val digits = currencyDigits()
        (allowanceRows + reserveRows).forEach { row ->
            val text = row.amount.get().trim()
            if (text.isNotEmpty() && text.toRawMoneyOrNull(digits) == null) {
                problems.add("Некорректная сумма в бюджете")
            }
            val from = row.from.get()
            val to = row.to.get()
            if (from != null && to != null && from > to) {
                problems.add("Начало периода позже конца")
            }
        }
        return problems.distinct()
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

    private fun parseGroupPath(text: String?): List<String> =
        text?.split("/")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    private fun kindText(kind: AccountKind?): String = when (kind) {
        AccountKind.MONEY -> "Деньги"
        AccountKind.BUDGET -> "Бюджет"
        null -> ""
    }

    private fun restMarkText(mark: RestMark?): String = when (mark) {
        RestMark.OFF, null -> "Выкл"
        RestMark.IF_ZERO -> "Если 0"
        RestMark.IF_NOT_ZERO -> "Если не 0"
        RestMark.IF_NEGATIVE -> "Если < 0"
        RestMark.IF_POSITIVE -> "Если > 0"
    }
}
