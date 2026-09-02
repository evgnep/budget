package su.nepom.budget.desktop.ui.transaction

import jakarta.inject.Inject
import javafx.application.Platform
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.collections.ListChangeListener
import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.DatePicker
import javafx.scene.control.Label
import javafx.scene.control.Tab
import javafx.scene.control.TableCell
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.TabPane
import javafx.scene.control.TextField
import javafx.scene.control.cell.CheckBoxTableCell
import javafx.scene.control.cell.TextFieldTableCell
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.stage.Stage
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import su.nepom.budget.desktop.model.AccountObservable
import su.nepom.budget.desktop.model.TransactionObservable
import su.nepom.budget.desktop.service.AccountService
import su.nepom.budget.desktop.service.CurrencyService
import su.nepom.budget.desktop.service.DbService
import su.nepom.budget.desktop.util.fx.Controller
import su.nepom.budget.desktop.util.fx.FormDriver
import su.nepom.budget.desktop.util.fx.FormState
import su.nepom.budget.desktop.util.fx.runAndShowError
import su.nepom.budget.desktop.util.formatDateTime
import su.nepom.budget.desktop.util.toLocalDate
import su.nepom.budget.utils.evalMoneyFormula
import su.nepom.budget.utils.format
import su.nepom.budget.utils.toBigDecimal
import su.nepom.budget.utils.toRawMoney
import su.nepom.budget.utils.toRawMoneyOrNull
import su.nepom.budget.event.AccountContent
import su.nepom.budget.event.TransactionContent
import su.nepom.budget.event.TransactionContentItem
import su.nepom.budget.model.AccountId
import su.nepom.budget.model.AccountKind
import su.nepom.budget.model.ContentHolder
import su.nepom.budget.model.CurrencyId
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.OperationType
import su.nepom.budget.model.RawMoney
import su.nepom.budget.model.Uuid
import java.math.RoundingMode
import java.net.URL
import java.time.LocalDate
import java.util.*
import kotlin.math.abs

@Suppress("unused", "UNCHECKED_CAST")
class TransactionDetailController @Inject constructor(
    private val dbService: DbService,
    private val accountService: AccountService,
    private val currencyService: CurrencyService,
    private val accountPicker: AccountPicker,
) : Controller, Initializable {

    private class ItemRow(
        account: AccountObservable?,
        amount: String,
        description: String,
        flag: Boolean,
        reservedUntil: LocalDate? = null,
    ) {
        val account = SimpleObjectProperty<AccountObservable?>(this, "account", account)
        val amount = SimpleStringProperty(this, "amount", amount)
        val description = SimpleStringProperty(this, "description", description)
        val flag = SimpleBooleanProperty(this, "flag", flag)
        val reservedUntil = SimpleObjectProperty<LocalDate?>(this, "reservedUntil", reservedUntil)
    }

    // which operation tab is active; DETAILS is the raw item list, always available
    private enum class OperationTab { INCOME, EXPENSE, TRANSFER, EXCHANGE, DETAILS }

    // a "pick account" control: button + name label, with currency/kind limits for the picker
    private inner class AccountField(
        private val button: Button,
        private val label: Label,
        private val clearButton: Button,
        private val kindProvider: () -> AccountKind?,
        private val currencyProvider: () -> CurrencyId?,
        private val balanceLabel: Label,
    ) {
        val account = SimpleObjectProperty<AccountObservable?>(this, "account", null)
        val pickButton: Button get() = button
        val removeButton: Button get() = clearButton

        init {
            account.addListener { _, _, v ->
                label.text = v?.content?.name ?: "(не выбран)"
                balanceLabel.text = v?.let { accountBalanceText(it) } ?: ""
            }
            clearButton.disableProperty().bind(account.isNull)
            button.setOnAction {
                val pre = account.get()?.let { setOf(AccountId(it.uuid)) } ?: emptySet()
                val picked = accountPicker.pick(
                    stage, pre, multi = false,
                    currency = currencyProvider(), kind = kindProvider(),
                ) ?: return@setOnAction
                val id = picked.firstOrNull() ?: return@setOnAction
                account.set(accountService.accounts[id.uuid])
            }
            clearButton.setOnAction { account.set(null) }
        }

        val value: AccountObservable? get() = account.get()
        fun set(a: AccountObservable?) = account.set(a)
    }

    private lateinit var stage: Stage
    private var viewOnly = false

    // conflict-resolution dialog: edit in memory, no DB reads (a sync may hold the DB) and no DB write
    private var conflictMode = false

    private val transactionFactory = TransactionObservable.Factory()

    private val itemRows = FXCollections.observableArrayList<ItemRow> { row ->
        arrayOf(row.account, row.amount, row.description, row.flag, row.reservedUntil)
    }
    private val itemsProperty = SimpleObjectProperty<List<TransactionContentItem>>(this, "items", emptyList())
    private var rebuildingRows = false
    private var syncingFromRows = false

    // guards against feedback while we push tab fields / selection programmatically
    private var populating = false
    private var syncingTab = false

    // account from a single-account list filter, pre-selected as the first account of a new operation
    private var pendingFirstAccount: AccountObservable? = null

    // read-only mode: which operation-type tab to keep next to DETAILS (null = not read-only)
    private var viewOnlyTypeTab: OperationTab? = null

    private val operationTabProperty = SimpleObjectProperty(this, "operationTab", OperationTab.EXPENSE)

    // structured tab account pickers
    private lateinit var incomeMoney: AccountField
    private lateinit var incomeBudget: AccountField
    private lateinit var expenseMoney: AccountField
    private lateinit var expenseBudget: AccountField
    private lateinit var transferFrom: AccountField
    private lateinit var transferTo: AccountField
    private lateinit var exchangeMoney1: AccountField
    private lateinit var exchangeBudget1: AccountField
    private lateinit var exchangeMoney2: AccountField
    private lateinit var exchangeBudget2: AccountField
    private lateinit var accountFields: List<AccountField>

    // projected account balance keyed by account uuid: stored rest adjusted by the unsaved change of
    // this transaction's rows for that account (may be several rows per account)
    private val projectedRestByAccount = mutableMapOf<Uuid, RawMoney>()

    lateinit var formDriver: FormDriver<*, TransactionObservable>
        private set

    val formState: FormState get() = formDriver.state

    // detail form
    @FXML private lateinit var root: VBox
    @FXML private lateinit var idTextField: TextField
    @FXML private lateinit var dateEditPicker: DatePicker
    @FXML private lateinit var descriptionEditField: TextField
    @FXML private lateinit var flagCheckbox: CheckBox
    @FXML private lateinit var deletedCheckbox: CheckBox
    @FXML private lateinit var itemsEditorBox: VBox
    @FXML private lateinit var operationTabPane: TabPane
    @FXML private lateinit var incomeTab: Tab
    @FXML private lateinit var expenseTab: Tab
    @FXML private lateinit var transferTab: Tab
    @FXML private lateinit var exchangeTab: Tab
    @FXML private lateinit var detailsTab: Tab

    @FXML private lateinit var incomeMoneyButton: Button
    @FXML private lateinit var incomeMoneyClearButton: Button
    @FXML private lateinit var incomeMoneyLabel: Label
    @FXML private lateinit var incomeMoneyBalanceLabel: Label
    @FXML private lateinit var incomeBudgetButton: Button
    @FXML private lateinit var incomeBudgetClearButton: Button
    @FXML private lateinit var incomeBudgetLabel: Label
    @FXML private lateinit var incomeBudgetBalanceLabel: Label
    @FXML private lateinit var incomeAmountField: TextField
    @FXML private lateinit var incomeCurrencyLabel: Label

    @FXML private lateinit var expenseMoneyButton: Button
    @FXML private lateinit var expenseMoneyClearButton: Button
    @FXML private lateinit var expenseMoneyLabel: Label
    @FXML private lateinit var expenseMoneyBalanceLabel: Label
    @FXML private lateinit var expenseBudgetButton: Button
    @FXML private lateinit var expenseBudgetClearButton: Button
    @FXML private lateinit var expenseBudgetLabel: Label
    @FXML private lateinit var expenseBudgetBalanceLabel: Label
    @FXML private lateinit var expenseAmountField: TextField
    @FXML private lateinit var expenseCurrencyLabel: Label

    @FXML private lateinit var transferFromButton: Button
    @FXML private lateinit var transferFromClearButton: Button
    @FXML private lateinit var transferFromLabel: Label
    @FXML private lateinit var transferFromBalanceLabel: Label
    @FXML private lateinit var transferToButton: Button
    @FXML private lateinit var transferToClearButton: Button
    @FXML private lateinit var transferToLabel: Label
    @FXML private lateinit var transferToBalanceLabel: Label
    @FXML private lateinit var transferAmountField: TextField
    @FXML private lateinit var transferCurrencyLabel: Label

    @FXML private lateinit var exchangeMoney1Button: Button
    @FXML private lateinit var exchangeMoney1ClearButton: Button
    @FXML private lateinit var exchangeMoney1Label: Label
    @FXML private lateinit var exchangeMoney1BalanceLabel: Label
    @FXML private lateinit var exchangeBudget1Button: Button
    @FXML private lateinit var exchangeBudget1ClearButton: Button
    @FXML private lateinit var exchangeBudget1Label: Label
    @FXML private lateinit var exchangeBudget1BalanceLabel: Label
    @FXML private lateinit var exchangeAmount1Field: TextField
    @FXML private lateinit var exchangeCurrency1Label: Label
    @FXML private lateinit var exchangeMoney2Button: Button
    @FXML private lateinit var exchangeMoney2ClearButton: Button
    @FXML private lateinit var exchangeMoney2Label: Label
    @FXML private lateinit var exchangeMoney2BalanceLabel: Label
    @FXML private lateinit var exchangeBudget2Button: Button
    @FXML private lateinit var exchangeBudget2ClearButton: Button
    @FXML private lateinit var exchangeBudget2Label: Label
    @FXML private lateinit var exchangeBudget2BalanceLabel: Label
    @FXML private lateinit var exchangeAmount2Field: TextField
    @FXML private lateinit var exchangeCurrency2Label: Label
    @FXML private lateinit var exchangeRateLabel: Label

    @FXML private lateinit var itemsTable: TableView<ItemRow>
    @FXML private lateinit var itemAccountColumn: TableColumn<ItemRow, String>
    @FXML private lateinit var itemCurrencyColumn: TableColumn<ItemRow, String>
    @FXML private lateinit var itemKindColumn: TableColumn<ItemRow, String>
    @FXML private lateinit var itemAmountColumn: TableColumn<ItemRow, String>
    @FXML private lateinit var itemBalanceColumn: TableColumn<ItemRow, String>
    @FXML private lateinit var itemDescriptionColumn: TableColumn<ItemRow, String>
    @FXML private lateinit var itemFlagColumn: TableColumn<ItemRow, Boolean>
    @FXML private lateinit var itemReservedUntilColumn: TableColumn<ItemRow, String>
    @FXML private lateinit var eventInfoLabel: Label
    @FXML private lateinit var eventInfoBox: HBox
    @FXML private lateinit var changedAtField: TextField
    @FXML private lateinit var creatorField: TextField
    @FXML private lateinit var placeField: TextField
    @FXML private lateinit var addItemButton: Button
    @FXML private lateinit var removeItemButton: Button
    @FXML private lateinit var balanceLabel: Label
    @FXML private lateinit var okButton: Button
    @FXML private lateinit var cancelButton: Button
    @FXML private lateinit var copyButton: Button

    override fun initialize(location: URL?, resources: ResourceBundle?) {
        setupItemsEditor()
        setupOperationTabs()
        setupForm()
        setupShortcuts()
        updateEventInfo(null)
        updateCopyButton()
    }

    // Esc - cancel, Shift+Enter - save, Ctrl+Shift+Enter - save and copy.
    // fire() on a disabled button is a no-op, so no extra state checks are needed.
    private fun setupShortcuts() {
        root.addEventFilter(KeyEvent.KEY_PRESSED) { e ->
            when {
                e.code == KeyCode.ESCAPE -> cancelButton.fire()
                e.code == KeyCode.ENTER && e.isShiftDown && e.isShortcutDown -> copyButton.fire()
                e.code == KeyCode.ENTER && e.isShiftDown -> okButton.fire()
                else -> return@addEventFilter
            }
            e.consume()
        }
    }

    fun setStage(stage: Stage) {
        this.stage = stage
    }

    // when off, existing transactions open read-only; new transactions and copying stay available
    fun setEditingAllowed(allowed: Boolean) {
        formDriver.setEditingEnabled(allowed)
    }

    fun onMasterSelectionChanged(selected: TransactionObservable?) {
        updateEventInfo(selected)
        if (selected != null) pendingFirstAccount = null
        // form state is set by MasterDetailFormDriver on the same selection event - defer so we read it settled
        Platform.runLater {
            updateCopyButton()
            // a NEW operation (freshly started or the copy from "save and copy") keeps its own
            // pre-filled fields - a master reselection must not overwrite them
            if (formDriver.state == FormState.NEW) return@runLater
            // sync from the selected row, not formDriver.item: while a "save changes?" dialog is open
            // formDriver.item is still the previous row (setItem is blocked on the modal)
            syncTabFieldsFrom(selected?.content?.items ?: emptyList())
        }
    }

    // new transaction has no events yet - clear the info fields; firstAccount comes from a
    // single-account list filter and is pre-selected as the first account
    fun onNewStarted(firstAccount: AccountObservable? = null) {
        updateEventInfo(null)
        updateCopyButton()
        resetTabFieldValues()
        // this runs before FormDriver.newItem() resets the form, so the tab may still be on
        // DETAILS / EXCHANGE from a previous operation - force the default so applyPendingFirstAccount
        // has a simple tab to put the account into
        operationTabProperty.value = OperationTab.EXPENSE
        pendingFirstAccount = firstAccount
        applyPendingFirstAccount()
        updateCurrencyLabels()
        updateRateLabel()
    }

    // saved transaction may be outside the current page/filter - keep showing its event info
    fun showEventInfoForCurrentItem() = updateEventInfo(formDriver.item)

    // edit a version inside the conflict-resolution dialog: OK returns the content, no DB write
    fun editForConflict(
        content: TransactionContent,
        onAccept: (TransactionContent) -> Unit,
        onCancel: () -> Unit,
    ) {
        conflictMode = true
        formDriver.contentSink = { onAccept(it as TransactionContent) }
        formDriver.cancelSink = onCancel
        formDriver.editItem(TransactionObservable(content))
        syncTabFieldsFrom(content.items)
        applyTabChrome(operationTabProperty.value)
    }

    // show a past version of a transaction (from the history form), view only
    fun showReadOnly(content: TransactionContent) {
        viewOnly = true
        viewOnlyTypeTab = tabForType(operationType(content.items))
        formDriver.showReadOnly(TransactionObservable(content))
        // keep the tab area usable for selection/copy, just turn off editing and the buttons
        // (FormDriver.applyReadOnly disables the whole TabPane - re-enable it and gate per-tab instead)
        itemsEditorBox.isDisable = false
        operationTabPane.isDisable = false
        itemsTable.isEditable = false
        listOf(addItemButton, removeItemButton, copyButton, eventInfoLabel, eventInfoBox).forEach {
            it.isVisible = false
            it.isManaged = false
        }
        accountFields.forEach {
            it.pickButton.isVisible = false
            it.pickButton.isManaged = false
            it.removeButton.isVisible = false
            it.removeButton.isManaged = false
        }
        listOf(
            incomeAmountField, expenseAmountField, transferAmountField,
            exchangeAmount1Field, exchangeAmount2Field,
        ).forEach {
            it.isEditable = false
            it.isFocusTraversable = false
        }
        syncTabFieldsFrom(content.items)
        // syncTabFieldsFrom only re-runs the chrome if the active tab actually changed - force it
        applyTabChrome(operationTabProperty.value)
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
        // evaluate an arithmetic formula in the cell and store the rounded result
        itemAmountColumn.setOnEditCommit { e ->
            val row = e.rowValue ?: return@setOnEditCommit
            row.amount.set(evaluateAmountFormula(e.newValue.orEmpty(), digitsOf(row.account.get())))
            syncItemsFromRows()
        }
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

        // reserved-until date as ISO text, empty = not reserved (see docs/budget.md)
        itemReservedUntilColumn.setCellValueFactory {
            SimpleStringProperty(it.value.reservedUntil.get()?.toString() ?: "")
        }
        itemReservedUntilColumn.cellFactory = TextFieldTableCell.forTableColumn()
        itemReservedUntilColumn.setOnEditCommit { e ->
            val row = e.rowValue ?: return@setOnEditCommit
            val text = e.newValue.orEmpty().trim()
            row.reservedUntil.set(if (text.isEmpty()) null else runCatching { LocalDate.parse(text) }.getOrNull())
            syncItemsFromRows()
        }

        addItemButton.setOnAction { addItemRow() }
        removeItemButton.setOnAction {
            itemsTable.selectionModel.selectedItem?.let { itemRows.remove(it) }
        }

        itemRows.addListener(ListChangeListener { if (!rebuildingRows) syncItemsFromRows() })
        itemsProperty.addListener { _, _, value ->
            if (!syncingFromRows) rebuildRows(value ?: emptyList())
            updateBalanceLabel()
            recomputeProjectedRests()
            // rows may have changed the operation type - move the bold header
            if (::formDriver.isInitialized) applyTabChrome(operationTabProperty.value)
        }
    }

    // --- operation tabs ---

    private fun setupOperationTabs() {
        incomeMoney = AccountField(incomeMoneyButton, incomeMoneyLabel, incomeMoneyClearButton, { AccountKind.MONEY }, { incomeBudget.value?.content?.currency }, incomeMoneyBalanceLabel)
        incomeBudget = AccountField(incomeBudgetButton, incomeBudgetLabel, incomeBudgetClearButton, { AccountKind.BUDGET }, { incomeMoney.value?.content?.currency }, incomeBudgetBalanceLabel)
        expenseMoney = AccountField(expenseMoneyButton, expenseMoneyLabel, expenseMoneyClearButton, { AccountKind.MONEY }, { expenseBudget.value?.content?.currency }, expenseMoneyBalanceLabel)
        expenseBudget = AccountField(expenseBudgetButton, expenseBudgetLabel, expenseBudgetClearButton, { AccountKind.BUDGET }, { expenseMoney.value?.content?.currency }, expenseBudgetBalanceLabel)
        transferFrom = AccountField(transferFromButton, transferFromLabel, transferFromClearButton, { transferTo.value?.content?.kind }, { transferTo.value?.content?.currency }, transferFromBalanceLabel)
        transferTo = AccountField(transferToButton, transferToLabel, transferToClearButton, { transferFrom.value?.content?.kind }, { transferFrom.value?.content?.currency }, transferToBalanceLabel)
        exchangeMoney1 = AccountField(exchangeMoney1Button, exchangeMoney1Label, exchangeMoney1ClearButton, { AccountKind.MONEY }, { exchangeBudget1.value?.content?.currency }, exchangeMoney1BalanceLabel)
        exchangeBudget1 = AccountField(exchangeBudget1Button, exchangeBudget1Label, exchangeBudget1ClearButton, { AccountKind.BUDGET }, { exchangeMoney1.value?.content?.currency }, exchangeBudget1BalanceLabel)
        exchangeMoney2 = AccountField(exchangeMoney2Button, exchangeMoney2Label, exchangeMoney2ClearButton, { AccountKind.MONEY }, { exchangeBudget2.value?.content?.currency }, exchangeMoney2BalanceLabel)
        exchangeBudget2 = AccountField(exchangeBudget2Button, exchangeBudget2Label, exchangeBudget2ClearButton, { AccountKind.BUDGET }, { exchangeMoney2.value?.content?.currency }, exchangeBudget2BalanceLabel)
        accountFields = listOf(
            incomeMoney, incomeBudget, expenseMoney, expenseBudget, transferFrom, transferTo,
            exchangeMoney1, exchangeBudget1, exchangeMoney2, exchangeBudget2,
        )

        // grey header for tabs that do not match the current operation type
        listOf(incomeTab, expenseTab, transferTab, exchangeTab).forEach { tab ->
            tab.graphic = Label(tab.text)
            tab.text = ""
        }

        accountFields.forEach { it.account.addListener { _, _, _ -> onTabFieldChanged() } }
        listOf(
            incomeAmountField, expenseAmountField, transferAmountField,
            exchangeAmount1Field, exchangeAmount2Field,
        ).forEach { it.textProperty().addListener { _, _, _ -> onTabFieldChanged() } }

        installFormulaEvaluation(incomeAmountField) { digitsOf(incomeMoney.value) }
        installFormulaEvaluation(expenseAmountField) { digitsOf(expenseMoney.value) }
        installFormulaEvaluation(transferAmountField) { digitsOf(transferFrom.value) }
        installFormulaEvaluation(exchangeAmount1Field) { digitsOf(exchangeMoney1.value) }
        installFormulaEvaluation(exchangeAmount2Field) { digitsOf(exchangeMoney2.value) }

        operationTabProperty.addListener { _, _, tab ->
            tab ?: return@addListener
            syncingTab = true
            operationTabPane.selectionModel.select(tabNode(tab))
            syncingTab = false
            applyTabChrome(tab)
        }
        operationTabPane.selectionModel.selectedItemProperty().addListener { _, old, new ->
            if (syncingTab || new == null) return@addListener
            val from = old?.let(::tabEnum)
            val to = tabEnum(new)
            operationTabProperty.value = to
            if (!populating) onUserTabSwitch(from, to)
        }

        // the property starts on EXPENSE while the TabPane still shows its first tab - align them
        syncingTab = true
        operationTabPane.selectionModel.select(tabNode(operationTabProperty.value))
        syncingTab = false
        applyTabChrome(operationTabProperty.value)
    }

    private fun tabNode(tab: OperationTab): Tab = when (tab) {
        OperationTab.INCOME -> incomeTab
        OperationTab.EXPENSE -> expenseTab
        OperationTab.TRANSFER -> transferTab
        OperationTab.EXCHANGE -> exchangeTab
        OperationTab.DETAILS -> detailsTab
    }

    private fun tabEnum(tab: Tab): OperationTab = when (tab) {
        incomeTab -> OperationTab.INCOME
        expenseTab -> OperationTab.EXPENSE
        transferTab -> OperationTab.TRANSFER
        exchangeTab -> OperationTab.EXCHANGE
        else -> OperationTab.DETAILS
    }

    private fun tabForType(type: OperationType): OperationTab = when (type) {
        OperationType.INCOME -> OperationTab.INCOME
        OperationType.EXPENSE -> OperationTab.EXPENSE
        OperationType.TRANSFER -> OperationTab.TRANSFER
        OperationType.CURRENCY_EXCHANGE -> OperationTab.EXCHANGE
        OperationType.MIXED -> OperationTab.DETAILS
    }

    private fun operationType(items: List<TransactionContentItem>): OperationType =
        OperationType.calculate(items, accountService.accounts.observableEntitiesByKey)

    private fun accountOf(item: TransactionContentItem?): AccountObservable? =
        item?.let { accountService.accounts[it.account.uuid] }

    private fun applyTabChrome(activeTab: OperationTab) {
        // the bold header follows the operation type of the current rows, not the open tab -
        // so it stays put when the user visits DETAILS. Empty / mixed rows fall back to the open tab.
        val highlightTab = tabForType(operationType(itemsProperty.get() ?: emptyList()))
            .takeIf { it != OperationTab.DETAILS } ?: activeTab
        listOf(
            OperationTab.INCOME to incomeTab,
            OperationTab.EXPENSE to expenseTab,
            OperationTab.TRANSFER to transferTab,
            OperationTab.EXCHANGE to exchangeTab,
        ).forEach { (t, node) ->
            val highlighted = t == highlightTab
            (node.graphic as? Label)?.apply {
                textFill = if (highlighted) Color.BLACK else Color.GRAY
                style = if (highlighted) "-fx-font-weight: bold;" else ""
            }
        }
        val typeTab = viewOnlyTypeTab
        if (viewOnly && typeTab != null) {
            // history / read-only: keep only the tab matching the operation type plus DETAILS.
            // the kept set depends on the operation type, not on which tab is currently open.
            val keep = listOf(incomeTab, expenseTab, transferTab, exchangeTab, detailsTab)
                .filter { it === detailsTab || it === tabNode(typeTab) }
            if (operationTabPane.tabs != keep) {
                syncingTab = true
                val selected = operationTabPane.selectionModel.selectedItem
                operationTabPane.tabs.setAll(keep)
                operationTabPane.selectionModel.select(if (selected in keep) selected else tabNode(typeTab))
                syncingTab = false
            }
        }
    }

    private fun onUserTabSwitch(from: OperationTab?, to: OperationTab) {
        val src = from?.let(::simpleTabFields)
        when {
            src != null && to in listOf(OperationTab.INCOME, OperationTab.EXPENSE, OperationTab.TRANSFER) -> {
                val (srcAccount1, srcAccount2, srcAmount) = src
                val (dstAccount1, dstAccount2, dstAmount) = simpleTabFields(to)!!
                dstAccount1.set(srcAccount1.value)
                dstAccount2.set(srcAccount2.value)
                dstAmount.text = srcAmount.text
            }
            // keep only the first account when moving to the currency exchange form
            src != null && to == OperationTab.EXCHANGE -> exchangeMoney1.set(src.first.value)
            // and carry it back when leaving the currency exchange form
            from == OperationTab.EXCHANGE ->
                simpleTabFields(to)?.first?.set(exchangeMoney1.value)
        }
        updateCurrencyLabels()
        updateRateLabel()
        if (to != OperationTab.DETAILS) writeItemsFromTab(to)
    }

    // "account 1 / account 2 / amount" fields shared by the income, expense and transfer tabs
    private fun simpleTabFields(tab: OperationTab): Triple<AccountField, AccountField, TextField>? = when (tab) {
        OperationTab.INCOME -> Triple(incomeMoney, incomeBudget, incomeAmountField)
        OperationTab.EXPENSE -> Triple(expenseMoney, expenseBudget, expenseAmountField)
        OperationTab.TRANSFER -> Triple(transferFrom, transferTo, transferAmountField)
        else -> null
    }

    private fun applyPendingFirstAccount() {
        val account = pendingFirstAccount ?: return
        pendingFirstAccount = null
        val fields = simpleTabFields(operationTabProperty.value) ?: return
        val wasPopulating = populating
        populating = true
        try {
            fields.first.set(account)
        } finally {
            populating = wasPopulating
        }
    }

    private fun onTabFieldChanged() {
        updateCurrencyLabels()
        updateRateLabel()
        if (populating || syncingTab) return
        val tab = operationTabProperty.value
        if (tab != OperationTab.DETAILS) writeItemsFromTab(tab)
    }

    private fun writeItemsFromTab(tab: OperationTab) {
        val items = buildItemsForTab(tab) ?: return
        if (itemsProperty.value != items)
            itemsProperty.set(items)
    }

    private fun buildItemsForTab(tab: OperationTab): List<TransactionContentItem>? {
        fun item(account: AccountObservable, value: Long) =
            TransactionContentItem(AccountId(account.uuid), RawMoney(value), "", false)

        fun positive(field: TextField, digits: Int): Long? =
            field.text.toRawMoneyOrNull(digits)?.value?.takeIf { it > 0 }

        return when (tab) {
            OperationTab.INCOME, OperationTab.EXPENSE -> {
                val income = tab == OperationTab.INCOME
                val money = (if (income) incomeMoney else expenseMoney).value ?: return null
                val budget = (if (income) incomeBudget else expenseBudget).value ?: return null
                if (money.content.currency != budget.content.currency) return null
                val amount = positive(if (income) incomeAmountField else expenseAmountField, digitsOf(money)) ?: return null
                val signed = if (income) amount else -amount
                listOf(item(money, signed), item(budget, signed))
            }

            OperationTab.TRANSFER -> {
                val from = transferFrom.value ?: return null
                val to = transferTo.value ?: return null
                if (from.content.currency != to.content.currency) return null
                if (from.content.kind != to.content.kind) return null
                val amount = positive(transferAmountField, digitsOf(from)) ?: return null
                listOf(item(from, -amount), item(to, amount))
            }

            OperationTab.EXCHANGE -> {
                val m1 = exchangeMoney1.value ?: return null
                val b1 = exchangeBudget1.value ?: return null
                val m2 = exchangeMoney2.value ?: return null
                val b2 = exchangeBudget2.value ?: return null
                if (m1.content.currency != b1.content.currency) return null
                if (m2.content.currency != b2.content.currency) return null
                val s1 = positive(exchangeAmount1Field, digitsOf(m1)) ?: return null
                val s2 = positive(exchangeAmount2Field, digitsOf(m2)) ?: return null
                listOf(item(m1, -s1), item(b1, -s1), item(m2, s2), item(b2, s2))
            }

            OperationTab.DETAILS -> null
        }
    }

    private fun syncTabFieldsFrom(items: List<TransactionContentItem>) {
        populating = true
        try {
            resetTabFieldValues()
            val type = operationType(items)
            when (type) {
                OperationType.INCOME, OperationType.EXPENSE -> {
                    val moneyItem = items.firstOrNull { accountOf(it)?.content?.kind == AccountKind.MONEY }
                    val budgetItem = items.firstOrNull { accountOf(it)?.content?.kind == AccountKind.BUDGET }
                    val amountText = amountText(moneyItem)
                    listOf(
                        Triple(incomeMoney, incomeBudget, incomeAmountField),
                        Triple(expenseMoney, expenseBudget, expenseAmountField),
                    ).forEach { (money, budget, amountField) ->
                        money.set(accountOf(moneyItem))
                        budget.set(accountOf(budgetItem))
                        amountField.text = amountText
                    }
                }

                OperationType.TRANSFER -> {
                    val negItem = items.minByOrNull { it.money.value }
                    val posItem = items.maxByOrNull { it.money.value }
                    transferFrom.set(accountOf(negItem))
                    transferTo.set(accountOf(posItem))
                    transferAmountField.text = amountText(posItem)
                }

                OperationType.CURRENCY_EXCHANGE -> {
                    val groups = items.groupBy { accountOf(it)?.content?.currency }.entries.filter { it.key != null }
                    val source = groups.firstOrNull { g -> g.value.any { it.money.value < 0 } }
                    val target = groups.firstOrNull { it !== source }
                    fillExchangeSide(source, exchangeMoney1, exchangeBudget1, exchangeAmount1Field)
                    fillExchangeSide(target, exchangeMoney2, exchangeBudget2, exchangeAmount2Field)
                }

                OperationType.MIXED -> {
                    // no structured form fits - the DETAILS tab shows the raw rows
                }
            }
            updateCurrencyLabels()
            updateRateLabel()
            operationTabProperty.value = tabForType(type)
        } finally {
            populating = false
        }
    }

    private fun fillExchangeSide(
        group: Map.Entry<CurrencyId?, List<TransactionContentItem>>?,
        moneyField: AccountField,
        budgetField: AccountField,
        amountField: TextField,
    ) {
        val moneyItem = group?.value?.firstOrNull { accountOf(it)?.content?.kind == AccountKind.MONEY }
        val budgetItem = group?.value?.firstOrNull { accountOf(it)?.content?.kind == AccountKind.BUDGET }
        moneyField.set(accountOf(moneyItem))
        budgetField.set(accountOf(budgetItem))
        amountField.text = amountText(moneyItem)
    }

    private fun amountText(item: TransactionContentItem?): String =
        item?.let { RawMoney(abs(it.money.value)).format(digitsOf(accountOf(it))) } ?: ""

    private fun resetTabFieldValues() {
        val wasPopulating = populating
        populating = true
        try {
            accountFields.forEach { it.set(null) }
            listOf(
                incomeAmountField, expenseAmountField, transferAmountField,
                exchangeAmount1Field, exchangeAmount2Field,
            ).forEach { it.text = "" }
        } finally {
            populating = wasPopulating
        }
    }

    private fun updateCurrencyLabels() {
        incomeCurrencyLabel.text = incomeMoney.value?.let { currencyName(it) } ?: ""
        expenseCurrencyLabel.text = expenseMoney.value?.let { currencyName(it) } ?: ""
        transferCurrencyLabel.text = transferFrom.value?.let { currencyName(it) } ?: ""
        exchangeCurrency1Label.text = exchangeMoney1.value?.let { currencyName(it) } ?: ""
        exchangeCurrency2Label.text = exchangeMoney2.value?.let { currencyName(it) } ?: ""
    }

    private fun updateRateLabel() {
        val a1 = exchangeMoney1.value
        val a2 = exchangeMoney2.value
        val d1 = digitsOf(a1)
        val d2 = digitsOf(a2)
        val s1 = exchangeAmount1Field.text.toRawMoneyOrNull(d1)?.takeIf { it.value > 0 }?.toBigDecimal(d1)
        val s2 = exchangeAmount2Field.text.toRawMoneyOrNull(d2)?.takeIf { it.value > 0 }?.toBigDecimal(d2)
        if (a1 == null || a2 == null || s1 == null || s2 == null) {
            exchangeRateLabel.text = ""
            return
        }
        val c1 = currencyName(a1)
        val c2 = currencyName(a2)
        val r1 = s2.divide(s1, 6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        val r2 = s1.divide(s2, 6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        exchangeRateLabel.text = "1 $c1 = $r1 $c2\n1 $c2 = $r2 $c1"
    }

    private fun recomputeProjectedRests() {
        if (!::formDriver.isInitialized || conflictMode) return
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

        okButton.disableProperty().addListener { _, _, _ -> updateCopyButton() }
        copyButton.setOnAction { saveAndCopy() }

        // plain "Сохранить" on a freshly created operation: once it is saved, ask the master list to
        // select it. The filter runs before FormDriver's own handler, so we can see the NEW state.
        okButton.addEventFilter(ActionEvent.ACTION) {
            if (formDriver.state != FormState.NEW) return@addEventFilter
            Platform.runLater {
                if (formDriver.state == FormState.VIEW) formDriver.item?.uuid?.let { onSaved?.invoke(it) }
            }
        }

        // leaving a pending edit (row switch, filter / page change) asks the user instead of the
        // silent auto-save
        formDriver.confirmLeaveEdit = { askLeaveEdit() }

        // Cancel rebuilds the whole form from the master selection (or clears it), including the
        // structured operation tabs that FormDriver does not know about. Consume the event so
        // FormDriver's built-in "revert to VIEW/EMPTY" does not also run. Conflict mode keeps its
        // own cancelSink.
        cancelButton.addEventFilter(ActionEvent.ACTION) { e ->
            if (formDriver.cancelSink != null) return@addEventFilter
            if (formDriver.state != FormState.EDIT && formDriver.state != FormState.NEW) return@addEventFilter
            e.consume()
            revertFormToSelection()
        }
    }

    // callback to read the currently selected master row (null when nothing is selected)
    var masterSelection: (() -> TransactionObservable?)? = null

    private val leaveSaveButton = ButtonType("Сохранить", ButtonBar.ButtonData.YES)
    private val leaveDiscardButton = ButtonType("Отменить изменения", ButtonBar.ButtonData.NO)

    // asked when navigating away from an unsaved operation; dismissing the dialog counts as "save"
    // (safe: if it does not validate, the navigation is aborted and nothing is lost)
    private fun askLeaveEdit(): FormDriver.LeaveEditChoice {
        val alert = Alert(
            Alert.AlertType.CONFIRMATION,
            "В операции есть несохранённые изменения.",
            leaveSaveButton, leaveDiscardButton,
        )
        alert.title = "Несохранённые изменения"
        alert.headerText = null
        if (::stage.isInitialized) alert.initOwner(stage)
        return if (alert.showAndWait().orElse(null) == leaveDiscardButton) FormDriver.LeaveEditChoice.DISCARD
        else FormDriver.LeaveEditChoice.SAVE
    }

    private fun revertFormToSelection() {
        val selected = masterSelection?.invoke()
        formDriver.revertTo(selected)
        syncTabFieldsFrom(selected?.content?.items ?: emptyList())
        updateEventInfo(selected)
        updateCopyButton()
    }

    private fun updateCopyButton() {
        val state = formDriver.state
        copyButton.isDisable = state == FormState.EMPTY
        val modified = state == FormState.EDIT || state == FormState.NEW
        copyButton.text = if (modified) "Сохранить и скопировать" else "Скопировать"
    }

    // asks the master list to refresh and move its selection onto the saved transaction. Called
    // synchronously by "save and copy" (before the form switches to the NEW copy, when the async
    // DB-change refresh would be skipped) and, deferred, after a plain save of a new operation.
    var onSaved: ((savedUuid: Uuid) -> Unit)? = null

    /**
     * Optionally saves the current transaction (if it was modified), then, if there were no errors,
     * starts a new unsaved transaction pre-filled as a copy of the current one.
     */
    private fun saveAndCopy() {
        val state = formDriver.state
        if (state == FormState.EMPTY) return
        val saved = state == FormState.EDIT || state == FormState.NEW
        if (saved) {
            okButton.fire()
            if (formDriver.state != FormState.VIEW) return // save failed (validation) - do not copy
        }
        // capture the source before refreshing the master list: the refresh reselects a row there,
        // which would pull formDriver.item off the just-saved transaction
        val source = formDriver.item?.content ?: return
        if (saved) formDriver.item?.uuid?.let { onSaved?.invoke(it) }
        if (!formDriver.newItem()) return
        dateEditPicker.value = source.date.toLocalDate()
        descriptionEditField.text = source.description
        flagCheckbox.isSelected = source.flag
        deletedCheckbox.isSelected = source.deleted
        itemsProperty.set(source.items.toList())
        syncTabFieldsFrom(source.items.toList())
        updateEventInfo(null)
        updateCopyButton()
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
        return ItemRow(acc, money.format(digitsOf(acc)), description, flag, reservedUntil?.toJavaLocalDate())
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
                reservedUntil = row.reservedUntil.get()?.toKotlinLocalDate(),
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
        if (viewOnly) return
        val pre = row.account.get()?.let { setOf(AccountId(it.uuid)) } ?: emptySet()
        val picked = accountPicker.pick(stage, pre, multi = false) ?: return
        val id = picked.firstOrNull() ?: return
        row.account.set(accountService.accounts[id.uuid])
        syncItemsFromRows()
        // forced refresh: table does not repaint the row while we are still
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

    // on focus loss, replace the field text with the evaluated formula result
    private fun installFormulaEvaluation(field: TextField, digits: () -> Int) {
        field.focusedProperty().addListener { _, wasFocused, focused ->
            if (wasFocused && !focused && field.isEditable) {
                val evaluated = evaluateAmountFormula(field.text.orEmpty(), digits())
                if (evaluated != field.text) field.text = evaluated
            }
        }
    }

    // returns the formula result rounded to the currency scale, or the original text if it is empty
    // or not a valid formula
    private fun evaluateAmountFormula(text: String, digits: Int): String {
        if (text.isBlank()) return text
        val value = evalMoneyFormula(text) ?: return text
        return runCatching { value.toRawMoney(digits).format(digits) }.getOrDefault(text)
    }

    private fun accountBalanceText(account: AccountObservable): String {
        val session = dbService.session ?: return ""
        val id = AccountId(account.uuid)
        val rest = runAndShowError { session.transactionDao.accountRest(setOf(id)) }.getOrDefault(emptyMap())
        val value = rest[id] ?: return ""
        return "Остаток: ${value.format(digitsOf(account))}"
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
        if (conflictMode) return
        val session = dbService.session
        val event = if (tx != null && session != null)
            runAndShowError { session.eventDao.getLastEventForObject(tx.uuid, ObjectKind.TRANSACTION) }.getOrNull()
        else null
        changedAtField.text = event?.created?.formatDateTime() ?: ""
        creatorField.text = event?.creator ?: ""
        placeField.text = event?.coords?.source?.code ?: ""
    }
}
