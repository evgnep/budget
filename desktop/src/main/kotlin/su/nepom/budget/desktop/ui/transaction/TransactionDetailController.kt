package su.nepom.budget.desktop.ui.transaction

import jakarta.inject.Inject
import javafx.application.Platform
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.collections.ListChangeListener
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.DatePicker
import javafx.scene.control.Label
import javafx.scene.control.TableCell
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.TextField
import javafx.scene.control.cell.CheckBoxTableCell
import javafx.scene.control.cell.TextFieldTableCell
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.stage.Stage
import su.nepom.budget.desktop.model.AccountObservable
import su.nepom.budget.desktop.model.TransactionObservable
import su.nepom.budget.desktop.service.AccountService
import su.nepom.budget.desktop.service.CurrencyService
import su.nepom.budget.desktop.service.DbService
import su.nepom.budget.desktop.util.fx.Controller
import su.nepom.budget.desktop.util.fx.FormDriver
import su.nepom.budget.desktop.util.fx.FormState
import su.nepom.budget.desktop.util.fx.runAndShowError
import su.nepom.budget.desktop.util.format
import su.nepom.budget.desktop.util.formatDateTime
import su.nepom.budget.desktop.util.toLocalDate
import su.nepom.budget.desktop.util.toRawMoneyOrNull
import su.nepom.budget.event.TransactionContent
import su.nepom.budget.event.TransactionContentItem
import su.nepom.budget.model.AccountId
import su.nepom.budget.model.AccountKind
import su.nepom.budget.model.CurrencyId
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.RawMoney
import su.nepom.budget.model.Uuid
import java.net.URL
import java.time.LocalDate
import java.util.*

// TODO detail (edit) part of the transaction form, split out so it can be reused in other forms
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
    ) {
        val account = SimpleObjectProperty<AccountObservable?>(this, "account", account)
        val amount = SimpleStringProperty(this, "amount", amount)
        val description = SimpleStringProperty(this, "description", description)
        val flag = SimpleBooleanProperty(this, "flag", flag)
    }

    private lateinit var stage: Stage

    private val transactionFactory = TransactionObservable.Factory()

    private val itemRows = FXCollections.observableArrayList<ItemRow> { row ->
        arrayOf(row.account, row.amount, row.description, row.flag)
    }
    private val itemsProperty = SimpleObjectProperty<List<TransactionContentItem>>(this, "items", emptyList())
    private var rebuildingRows = false
    private var syncingFromRows = false

    // projected account balance keyed by account uuid: stored rest adjusted by the unsaved change of
    // this transaction's rows for that account (may be several rows per account)
    private val projectedRestByAccount = mutableMapOf<Uuid, RawMoney>()

    lateinit var formDriver: FormDriver<*, TransactionObservable>
        private set

    val formState: FormState get() = formDriver.state

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

    override fun initialize(location: URL?, resources: ResourceBundle?) {
        setupItemsEditor()
        setupForm()
        updateEventInfo(null)
        updateCopyButton()
    }

    // TODO host form gives us the stage so we can open the modal account picker
    fun setStage(stage: Stage) {
        this.stage = stage
    }

    fun onMasterSelectionChanged(selected: TransactionObservable?) {
        updateEventInfo(selected)
        // form state is set by MasterDetailFormDriver on the same selection event - defer so we read it settled
        Platform.runLater { updateCopyButton() }
    }

    // new transaction has no events yet - clear the info fields
    fun onNewStarted() {
        updateEventInfo(null)
        updateCopyButton()
    }

    // saved transaction may be outside the current page/filter - keep showing its event info
    fun showEventInfoForCurrentItem() = updateEventInfo(formDriver.item)

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

        okButton.disableProperty().addListener { _, _, _ -> updateCopyButton() }
        copyButton.setOnAction { saveAndCopy() }
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
}
