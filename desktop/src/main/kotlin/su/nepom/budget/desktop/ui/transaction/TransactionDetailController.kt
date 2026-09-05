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
import javafx.scene.Node
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.Hyperlink
import javafx.scene.control.ComboBox
import javafx.scene.control.DatePicker
import javafx.scene.control.Label
import javafx.scene.control.TableCell
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.control.cell.CheckBoxTableCell
import javafx.scene.control.cell.TextFieldTableCell
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.shape.Rectangle
import javafx.scene.paint.Color
import javafx.stage.Stage
import javafx.util.StringConverter
import org.controlsfx.control.SearchableComboBox
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import su.nepom.budget.desktop.model.AccountObservable
import su.nepom.budget.desktop.model.TransactionObservable
import su.nepom.budget.desktop.service.AccountService
import su.nepom.budget.desktop.service.CurrencyService
import su.nepom.budget.desktop.service.DbService
import su.nepom.budget.desktop.ui.history.History
import su.nepom.budget.desktop.util.fx.Controller
import su.nepom.budget.desktop.util.fx.FormDriver
import su.nepom.budget.desktop.util.fx.FormState
import su.nepom.budget.desktop.util.fx.runAndShowError
import su.nepom.budget.desktop.util.fx.setupFlexibleDateFormat
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
  private val history: History,
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

  // a "pick account" control: SearchableComboBox ("name, currency", filters as you type in its
  // own popup search field - see ControlsFX SearchableComboBoxSkin) + "..." button that opens the
  // picker dialog + "x" button that clears, with currency/kind limits for both the combo
  // suggestions and the picker.
  private inner class AccountField(
    private val combo: SearchableComboBox<AccountObservable>,
    private val button: Button,
    private val clearButton: Button,
    private val kindProvider: () -> AccountKind?,
    private val currencyProvider: () -> CurrencyId?,
  ) {
    val account = SimpleObjectProperty<AccountObservable?>(this, "account", null)
    val pickButton: Button get() = button
    val removeButton: Button get() = clearButton
    val comboBox: ComboBox<AccountObservable> get() = combo

    // guards against the combo's value-sync listener feeding back into account
    private var applyingAccount = false

    // after Enter/Tab the skin clears its internal search field, which rebuilds its internal
    // filtered items list, resets its own selection and, via the bidirectional value binding,
    // bounces our value back to null right after a real pick - and restoring it immediately,
    // in the same listener call, retriggers the very same cascade synchronously (confirmed via
    // logging: an endless null/restore ping-pong within one call stack). Coalesce reactions to
    // a single deferred check on the next pulse instead, once the cascade has settled.
    private var settlePending = false

    init {
      // keep Tab within the meaningful sequence of fields (date/accounts/amount/description/flag) -
      // the "..." picker and "x" clear buttons stay reachable by mouse only
      button.isFocusTraversable = false
      clearButton.isFocusTraversable = false
      combo.converter = object : StringConverter<AccountObservable>() {
        override fun toString(a: AccountObservable?): String = a?.let(::accountComboText) ?: ""
        override fun fromString(text: String?): AccountObservable? = null
      }
      // populate suggestions lazily (not in init - kindProvider/currencyProvider may read
      // sibling AccountFields that are still being constructed at that point), right before
      // the popup shows. A click never focuses combo itself - the skin's own internal
      // delegate combo box gets the click, so combo.focusedProperty() never fired for mouse
      // use; combo.showingProperty() fires reliably either way. Deferred via runLater: a
      // click actually lands on the skin's internal delegate combo box, which is still
      // mid-way through its own show()/popup-population when this listener fires (nested via
      // the skin's own showingProperty cross-wiring) - mutating combo.items synchronously
      // there corrupted an in-progress iteration over it (IndexOutOfBoundsException).
      combo.showingProperty().addListener { _, _, showing ->
        if (showing) Platform.runLater { combo.items.setAll(matchingAccounts()) }
      }
      combo.valueProperty().addListener { _, _, v ->
        if (applyingAccount) return@addListener
        if (v != null) {
          account.set(v)
          return@addListener
        }
        if (account.get() == null || settlePending) return@addListener
        settlePending = true
        Platform.runLater {
          settlePending = false
          val settled = combo.value
          if (settled != null) {
            if (settled !== account.get()) account.set(settled)
          } else if (account.get() != null) {
            applyingAccount = true
            combo.value = account.get()
            applyingAccount = false
          }
        }
      }

      account.addListener { _, _, v ->
        // don't touch combo.items here - mutating it while a selection is still being
        // committed (e.g. right after Enter/click, from the combo's own value listener
        // above) made the library's internal filtered list lose the selection and bounce
        // the value back to null. The item list only needs refreshing before the user
        // opens the dropdown, which the focus listener above already covers.
        applyingAccount = true
        combo.value = v
        applyingAccount = false
      }
      clearButton.disableProperty().bind(account.isNull)
      clearButton.setOnAction { account.set(null) }
      button.setOnAction {
        val pre = account.get()?.let { setOf(AccountId(it.uuid)) } ?: emptySet()
        val picked = accountPicker.pick(
          stage, pre, multi = false,
          currency = currencyProvider(), kind = kindProvider(),
        ) ?: return@setOnAction
        val id = picked.firstOrNull() ?: return@setOnAction
        account.set(accountService.accounts[id.uuid])
      }
    }

    private fun matchingAccounts(): List<AccountObservable> {
      val kind = kindProvider()
      val currency = currencyProvider()
      return accountService.accounts
        .filter { !it.content.hidden }
        .filter { kind == null || it.content.kind == kind }
        .filter { currency == null || it.content.currency == currency }
        .sortedBy { it.content.name }
    }

    val value: AccountObservable? get() = account.get()
    fun set(a: AccountObservable?) = account.set(a)
  }

  private lateinit var stage: Stage
  private var viewOnly = false
  // mirrors the "Разрешить редактирование" master switch (off by default) - toggling the
  // deleted checkbox programmatically does not by itself move FormDriver's state to EDIT
  // unless editing is allowed, so the delete/restore button must respect it too
  private var editingAllowed = true

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

  // last operation-type tab visited (INCOME/EXPENSE/TRANSFER/EXCHANGE) - restored when switching
  // the view mode back from "Проводки" to "Обычная операция"
  private var lastTypeTab = OperationTab.EXPENSE
  private lateinit var viewModeGroup: ToggleGroup
  private lateinit var typeGroup: ToggleGroup

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

  lateinit var formDriver: FormDriver<*, TransactionObservable>
    private set

  val formState: FormState get() = formDriver.state

  // detail form
  @FXML
  private lateinit var root: StackPane
  @FXML
  private lateinit var focusRingOverlay: Rectangle
  @FXML
  private lateinit var idTextField: TextField
  @FXML
  private lateinit var dateEditPicker: DatePicker
  @FXML
  private lateinit var descriptionEditField: TextArea
  @FXML
  private lateinit var flagCheckbox: CheckBox
  @FXML
  private lateinit var deletedCheckbox: CheckBox
  @FXML
  private lateinit var itemsEditorBox: VBox
  @FXML
  private lateinit var viewModeBox: HBox
  @FXML
  private lateinit var viewModeNormalButton: ToggleButton
  @FXML
  private lateinit var viewModeEntriesButton: ToggleButton
  @FXML
  private lateinit var allowEditToggle: ToggleButton
  @FXML
  private lateinit var typeSwitchBox: HBox
  @FXML
  private lateinit var typeIncomeButton: ToggleButton
  @FXML
  private lateinit var typeExpenseButton: ToggleButton
  @FXML
  private lateinit var typeTransferButton: ToggleButton
  @FXML
  private lateinit var typeExchangeButton: ToggleButton
  @FXML
  private lateinit var operationContentPane: StackPane
  @FXML
  private lateinit var incomePane: GridPane
  @FXML
  private lateinit var expensePane: GridPane
  @FXML
  private lateinit var transferPane: GridPane
  @FXML
  private lateinit var exchangePane: GridPane
  @FXML
  private lateinit var entriesPane: VBox
  @FXML
  private lateinit var entriesDateGrid: GridPane
  @FXML
  private lateinit var entriesFooterGrid: GridPane

  @FXML
  private lateinit var incomeMoneyCombo: SearchableComboBox<AccountObservable>
  @FXML
  private lateinit var incomeMoneyButton: Button
  @FXML
  private lateinit var incomeMoneyClearButton: Button
  @FXML
  private lateinit var incomeBudgetCombo: SearchableComboBox<AccountObservable>
  @FXML
  private lateinit var incomeBudgetButton: Button
  @FXML
  private lateinit var incomeBudgetClearButton: Button
  @FXML
  private lateinit var incomeAmountField: TextField
  @FXML
  private lateinit var incomeCurrencyLabel: Label

  @FXML
  private lateinit var expenseMoneyCombo: SearchableComboBox<AccountObservable>
  @FXML
  private lateinit var expenseMoneyButton: Button
  @FXML
  private lateinit var expenseMoneyClearButton: Button
  @FXML
  private lateinit var expenseBudgetCombo: SearchableComboBox<AccountObservable>
  @FXML
  private lateinit var expenseBudgetButton: Button
  @FXML
  private lateinit var expenseBudgetClearButton: Button
  @FXML
  private lateinit var expenseAmountField: TextField
  @FXML
  private lateinit var expenseCurrencyLabel: Label

  @FXML
  private lateinit var transferFromCombo: SearchableComboBox<AccountObservable>
  @FXML
  private lateinit var transferFromButton: Button
  @FXML
  private lateinit var transferFromClearButton: Button
  @FXML
  private lateinit var transferToCombo: SearchableComboBox<AccountObservable>
  @FXML
  private lateinit var transferToButton: Button
  @FXML
  private lateinit var transferToClearButton: Button
  @FXML
  private lateinit var transferAmountField: TextField
  @FXML
  private lateinit var transferCurrencyLabel: Label
  @FXML
  private lateinit var swapAccountsButton: Button

  @FXML
  private lateinit var exchangeMoney1Combo: SearchableComboBox<AccountObservable>
  @FXML
  private lateinit var exchangeMoney1Button: Button
  @FXML
  private lateinit var exchangeMoney1ClearButton: Button
  @FXML
  private lateinit var exchangeBudget1Combo: SearchableComboBox<AccountObservable>
  @FXML
  private lateinit var exchangeBudget1Button: Button
  @FXML
  private lateinit var exchangeBudget1ClearButton: Button
  @FXML
  private lateinit var exchangeAmount1Field: TextField
  @FXML
  private lateinit var exchangeCurrency1Label: Label
  @FXML
  private lateinit var exchangeMoney2Combo: SearchableComboBox<AccountObservable>
  @FXML
  private lateinit var exchangeMoney2Button: Button
  @FXML
  private lateinit var exchangeMoney2ClearButton: Button
  @FXML
  private lateinit var exchangeBudget2Combo: SearchableComboBox<AccountObservable>
  @FXML
  private lateinit var exchangeBudget2Button: Button
  @FXML
  private lateinit var exchangeBudget2ClearButton: Button
  @FXML
  private lateinit var exchangeAmount2Field: TextField
  @FXML
  private lateinit var exchangeCurrency2Label: Label
  @FXML
  private lateinit var exchangeRateLabel: Label

  @FXML
  private lateinit var itemsTable: TableView<ItemRow>
  @FXML
  private lateinit var itemAccountColumn: TableColumn<ItemRow, String>
  @FXML
  private lateinit var itemCurrencyColumn: TableColumn<ItemRow, String>
  @FXML
  private lateinit var itemKindColumn: TableColumn<ItemRow, String>
  @FXML
  private lateinit var itemAmountColumn: TableColumn<ItemRow, String>
  @FXML
  private lateinit var itemDescriptionColumn: TableColumn<ItemRow, String>
  @FXML
  private lateinit var itemFlagColumn: TableColumn<ItemRow, Boolean>
  @FXML
  private lateinit var itemReservedUntilColumn: TableColumn<ItemRow, String>
  @FXML
  private lateinit var eventInfoLabel: Label
  @FXML
  private lateinit var changedAtLabel: Label
  @FXML
  private lateinit var creatorRowLabel: Label
  @FXML
  private lateinit var creatorLabel: Label
  @FXML
  private lateinit var historyLink: Hyperlink
  @FXML
  private lateinit var metaToggleLabel: Label
  @FXML
  private lateinit var metaPane: javafx.scene.layout.GridPane
  @FXML
  private lateinit var addItemButton: Button
  @FXML
  private lateinit var removeItemButton: Button
  @FXML
  private lateinit var balanceLabel: Label
  @FXML
  private lateinit var deleteButton: Button
  @FXML
  private lateinit var okButton: Button
  @FXML
  private lateinit var cancelButton: Button
  @FXML
  private lateinit var copyButton: Button

  override fun initialize(location: URL?, resources: ResourceBundle?) {
    dateEditPicker.setupFlexibleDateFormat()
    setupItemsEditor()
    setupOperationTabs()
    setupForm()
    setupShortcuts()
    setupMetaToggle()
    setupFooterButtons()
    setupDescriptionTabTraversal()
    setupFocusRingRefresh()
    setupAllowEditToggle()
    setupSwapAccountsButton()
    updateEventInfo(null)
    updateCopyButton()
  }

  // swaps "Со счёта" / "На счёт" without touching the amount - only meaningful on the Перевод tab
  private fun setupSwapAccountsButton() {
    swapAccountsButton.setOnAction {
      val from = transferFrom.value
      val to = transferTo.value
      transferFrom.set(to)
      transferTo.set(from)
    }
  }

  // "Разрешить редактирование" used to be a checkbox next to the master list, now a toggle right
  // beside the "Обычная операция" / "Проводки" switch - off by default, existing operations open
  // read-only until explicitly unlocked
  private fun setupAllowEditToggle() {
    updateAllowEditToggleChrome(allowEditToggle.isSelected)
    allowEditToggle.selectedProperty().addListener { _, _, allowed ->
      updateAllowEditToggleChrome(allowed)
      setEditingAllowed(allowed)
    }
    setEditingAllowed(allowEditToggle.isSelected)
  }

  private fun updateAllowEditToggleChrome(allowed: Boolean) {
    allowEditToggle.text = if (allowed) "Редактирование разрешено" else "Редактирование запрещено, разрешить"
  }

  // Tab-ing onto a SearchableComboBox or flagCheckbox does set its :focused pseudo-class right
  // away, but AtlantaFX's own :focused style for them apparently needs a later repaint (typing, a
  // click, ...) to actually show - a plain CSS ":focused" rule of our own didn't help either.
  // Sidestep the whole CSS pseudo-class path: draw focusRingOverlay (see the FXML/CSS) directly
  // over whichever of those controls currently has focus, positioned in root's own coordinates
  // since it is root's sibling in the StackPane.
  private fun setupFocusRingRefresh() {
    fun bind(scene: javafx.scene.Scene) {
      scene.focusOwnerProperty().addListener { _, _, focused -> updateFocusRing(focused) }
      updateFocusRing(scene.focusOwner)
    }
    root.scene?.let(::bind)
    root.sceneProperty().addListener { _, _, scene -> scene?.let(::bind) }
  }

  private fun updateFocusRing(focused: Node?) {
    if (focused == null || (focused !is SearchableComboBox<*> && focused !== flagCheckbox)) {
      focusRingOverlay.isVisible = false
      return
    }
    val bounds = root.sceneToLocal(focused.localToScene(focused.boundsInLocal))
    focusRingOverlay.x = bounds.minX - 2
    focusRingOverlay.y = bounds.minY - 2
    focusRingOverlay.width = bounds.width + 4
    focusRingOverlay.height = bounds.height + 4
    focusRingOverlay.isVisible = true
  }

  // TextArea's default behavior inserts a tab character instead of moving focus - Tab must follow
  // the same field order as everywhere else: onward to flagCheckbox, back to the active tab's
  // amount field
  private fun setupDescriptionTabTraversal() {
    descriptionEditField.addEventFilter(KeyEvent.KEY_PRESSED) { e ->
      if (e.code != KeyCode.TAB) return@addEventFilter
      e.consume()
      if (e.isShiftDown) fieldBeforeDescription(operationTabProperty.value).requestFocus()
      else flagCheckbox.requestFocus()
    }
  }

  private fun fieldBeforeDescription(tab: OperationTab): Node = when (tab) {
    OperationTab.INCOME -> incomeAmountField
    OperationTab.EXPENSE -> expenseAmountField
    OperationTab.TRANSFER -> transferAmountField
    OperationTab.EXCHANGE -> exchangeAmount2Field
    OperationTab.DETAILS -> addItemButton
  }

  // "Служебные данные" (id / changed at / creator / place) starts collapsed - it's rarely needed
  private fun setupMetaToggle() {
    metaPane.isVisible = false
    metaPane.isManaged = false
    metaToggleLabel.setOnMouseClicked {
      val expand = !metaPane.isVisible
      metaPane.isVisible = expand
      metaPane.isManaged = expand
      metaToggleLabel.text = if (expand) "▾ Служебные данные" else "▸ Служебные данные"
    }
  }

  // "Удалить операцию" / "Восстановить операцию" (footer, left) toggles the "Удалена" checkbox
  // and saves - deleting asks for confirmation first, restoring does not (non-destructive).
  private fun setupFooterButtons() {
    deleteButton.setOnAction { onDeleteButtonClicked() }
    deletedCheckbox.selectedProperty().addListener { _, _, deleted -> updateDeleteButton(deleted) }
    updateDeleteButton(deletedCheckbox.isSelected)
  }

  private fun updateDeleteButton(deleted: Boolean) {
    deleteButton.text = if (deleted) "Восстановить операцию" else "Удалить операцию"
    deleteButton.style = "-fx-background-color: transparent; -fx-text-fill: " +
      if (deleted) "#2e5aac;" else "#c23b32;"
  }

  private fun onDeleteButtonClicked() {
    if (deletedCheckbox.isSelected) {
      deletedCheckbox.isSelected = false
      okButton.fire()
      return
    }
    val alert = Alert(
      Alert.AlertType.CONFIRMATION,
      "Удалить операцию?",
      ButtonType.OK, ButtonType.CANCEL,
    )
    alert.title = "Удаление операции"
    alert.headerText = null
    if (::stage.isInitialized) alert.initOwner(stage)
    if (alert.showAndWait().orElse(null) != ButtonType.OK) return
    deletedCheckbox.isSelected = true
    okButton.fire()
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
    editingAllowed = allowed
    formDriver.setEditingEnabled(allowed)
    refreshDeleteButtonDisabled()
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
    // Tab order starts at the type selector, then walks date -> accounts -> amount -> description
    // -> flag; land the initial focus there instead of wherever the mouse click left it
    Platform.runLater { typeButtonFor(operationTabProperty.value).requestFocus() }
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
    // editing is already forced on for conflict resolution (see formDriver.editItem below) - the
    // "Разрешить редактирование" toggle would be redundant and misleading here
    allowEditToggle.isVisible = false
    allowEditToggle.isManaged = false
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
    // (FormDriver.applyReadOnly would otherwise disable the whole operationContentPane subtree -
    // re-enable it and gate per-control instead; viewModeBox/typeSwitchBox are never a FormDriver
    // field, so mode/type switching always stays available)
    operationContentPane.isDisable = false
    itemsTable.isEditable = false
    // eventInfoLabel/changedAtLabel/creatorRowLabel/creatorLabel show the *current* transaction's
    // last event, which would be misleading for a past version shown here - keep them hidden
    // regardless of the meta toggle. The toggle itself (and the id row) stays usable, same as edit.
    listOf(
      addItemButton, removeItemButton, copyButton, deleteButton, swapAccountsButton,
      eventInfoLabel, changedAtLabel, creatorRowLabel, creatorLabel, historyLink, allowEditToggle,
    ).forEach {
      it.isVisible = false
      it.isManaged = false
    }
    accountFields.forEach {
      it.pickButton.isVisible = false
      it.pickButton.isManaged = false
      it.removeButton.isVisible = false
      it.removeButton.isManaged = false
      // plain isDisable - the "allow editing" checkbox path (FormDriver.setEditingEnabled)
      // disables operationContentPane itself, which the combo boxes inherit regardless of what we
      // set on them directly, so avoiding isDisable here would only fix this one code path.
      // transactionDetail.css overrides the default disabled dimming to stay readable either way.
      it.comboBox.isDisable = true
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
      // rows may have changed the operation type - move the bold header
      if (::formDriver.isInitialized) applyTabChrome(operationTabProperty.value)
    }
  }

  // --- operation tabs ---

  private fun setupOperationTabs() {
    incomeMoney = AccountField(
      incomeMoneyCombo,
      incomeMoneyButton,
      incomeMoneyClearButton,
      { AccountKind.MONEY },
      { incomeBudget.value?.content?.currency }
    )
    incomeBudget = AccountField(
      incomeBudgetCombo,
      incomeBudgetButton,
      incomeBudgetClearButton,
      { AccountKind.BUDGET },
      { incomeMoney.value?.content?.currency }
    )
    expenseMoney = AccountField(
      expenseMoneyCombo,
      expenseMoneyButton,
      expenseMoneyClearButton,
      { AccountKind.MONEY },
      { expenseBudget.value?.content?.currency }
    )
    expenseBudget = AccountField(
      expenseBudgetCombo,
      expenseBudgetButton,
      expenseBudgetClearButton,
      { AccountKind.BUDGET },
      { expenseMoney.value?.content?.currency }
    )
    transferFrom = AccountField(
      transferFromCombo,
      transferFromButton,
      transferFromClearButton,
      { transferTo.value?.content?.kind },
      { transferTo.value?.content?.currency }
    )
    transferTo = AccountField(
      transferToCombo,
      transferToButton,
      transferToClearButton,
      { transferFrom.value?.content?.kind },
      { transferFrom.value?.content?.currency }
    )
    exchangeMoney1 = AccountField(
      exchangeMoney1Combo,
      exchangeMoney1Button,
      exchangeMoney1ClearButton,
      { AccountKind.MONEY },
      { exchangeBudget1.value?.content?.currency }
    )
    exchangeBudget1 = AccountField(
      exchangeBudget1Combo,
      exchangeBudget1Button,
      exchangeBudget1ClearButton,
      { AccountKind.BUDGET },
      { exchangeMoney1.value?.content?.currency }
    )
    exchangeMoney2 = AccountField(
      exchangeMoney2Combo,
      exchangeMoney2Button,
      exchangeMoney2ClearButton,
      { AccountKind.MONEY },
      { exchangeBudget2.value?.content?.currency }
    )
    exchangeBudget2 = AccountField(
      exchangeBudget2Combo,
      exchangeBudget2Button,
      exchangeBudget2ClearButton,
      { AccountKind.BUDGET },
      { exchangeMoney2.value?.content?.currency }
    )
    accountFields = listOf(
      incomeMoney, incomeBudget, expenseMoney, expenseBudget, transferFrom, transferTo,
      exchangeMoney1, exchangeBudget1, exchangeMoney2, exchangeBudget2,
    )

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
      selectButtonsFor(tab)
      syncingTab = false
      applyTabChrome(tab)
    }

    // two independent segmented controls: view mode (Обычная операция / Проводки) and, only
    // meaningful in the "Обычная операция" mode, the operation type. Both drive the same
    // operationTabProperty as the old TabPane selection did.
    viewModeGroup = ToggleGroup()
    listOf(viewModeNormalButton, viewModeEntriesButton).forEach { it.toggleGroup = viewModeGroup }
    preventDeselection(viewModeGroup)
    viewModeGroup.selectedToggleProperty().addListener { _, old, new ->
      if (syncingTab || new == null || new === old) return@addListener
      val from = operationTabProperty.value
      val to = if (new === viewModeEntriesButton) OperationTab.DETAILS else lastTypeTab
      operationTabProperty.value = to
      if (!populating) onUserTabSwitch(from, to)
    }

    typeGroup = ToggleGroup()
    listOf(typeIncomeButton, typeExpenseButton, typeTransferButton, typeExchangeButton)
      .forEach { it.toggleGroup = typeGroup }
    preventDeselection(typeGroup)
    typeGroup.selectedToggleProperty().addListener { _, old, new ->
      if (syncingTab || new == null || new === old) return@addListener
      val from = operationTabProperty.value
      val to = typeEnum(new as ToggleButton)
      operationTabProperty.value = to
      if (!populating) onUserTabSwitch(from, to)
    }

    // the property starts on EXPENSE while the buttons still show their FXML-default selection -
    // align them
    syncingTab = true
    selectButtonsFor(operationTabProperty.value)
    syncingTab = false
    applyTabChrome(operationTabProperty.value)
  }

  // a ToggleGroup normally allows deselecting its only selected toggle by clicking it again,
  // leaving selectedToggle null - not wanted here, one of the two/four buttons must stay active
  private fun preventDeselection(group: ToggleGroup) {
    group.selectedToggleProperty().addListener { _, old, new -> if (new == null) group.selectToggle(old) }
  }

  private fun selectButtonsFor(tab: OperationTab) {
    if (tab == OperationTab.DETAILS) {
      viewModeEntriesButton.isSelected = true
    } else {
      viewModeNormalButton.isSelected = true
      lastTypeTab = tab
      typeButtonFor(tab).isSelected = true
    }
    typeSwitchBox.isVisible = tab != OperationTab.DETAILS
    typeSwitchBox.isManaged = tab != OperationTab.DETAILS
    // the segmented type control should be a single Tab stop, not four - only the selected
    // button stays reachable via Tab (clicking with the mouse still switches between them)
    listOf(
      OperationTab.INCOME to typeIncomeButton,
      OperationTab.EXPENSE to typeExpenseButton,
      OperationTab.TRANSFER to typeTransferButton,
      OperationTab.EXCHANGE to typeExchangeButton,
    ).forEach { (t, button) -> button.isFocusTraversable = t == tab }
    listOf(
      OperationTab.INCOME to incomePane,
      OperationTab.EXPENSE to expensePane,
      OperationTab.TRANSFER to transferPane,
      OperationTab.EXCHANGE to exchangePane,
      OperationTab.DETAILS to entriesPane,
    ).forEach { (t, pane) ->
      pane.isVisible = t == tab
      pane.isManaged = t == tab
    }
    relocateSharedFields(tab)
  }

  // date / description / flag are single shared controls (one FormDriver binding each) but the
  // mockup places them inside every operation-type panel and the entries panel too - since a
  // Node can only have one parent, move the three controls into whichever panel is active instead
  // of duplicating them.
  private fun relocateSharedFields(tab: OperationTab) {
    val datePane = if (tab == OperationTab.DETAILS) entriesDateGrid else paneFor(tab)
    val footerPane = if (tab == OperationTab.DETAILS) entriesFooterGrid else paneFor(tab)
    val (descriptionRow, flagRow) = when (tab) {
      OperationTab.INCOME, OperationTab.EXPENSE -> 4 to 5
      OperationTab.TRANSFER -> 5 to 6
      OperationTab.EXCHANGE -> 10 to 11
      OperationTab.DETAILS -> 0 to 1
    }
    // dateEditPicker goes first in Tab order (see relocate's "atStart") - descriptionEditField and
    // flagCheckbox are always last, appended after every other field already declared in the pane
    relocate(dateEditPicker, datePane, row = 0, column = 1, atStart = true)
    relocate(descriptionEditField, footerPane, row = descriptionRow, column = 1)
    relocate(flagCheckbox, footerPane, row = flagRow, column = 1)
  }

  private fun paneFor(tab: OperationTab): GridPane = when (tab) {
    OperationTab.INCOME -> incomePane
    OperationTab.EXPENSE -> expensePane
    OperationTab.TRANSFER -> transferPane
    OperationTab.EXCHANGE -> exchangePane
    OperationTab.DETAILS -> throw IllegalArgumentException("DETAILS has no shared GridPane")
  }

  // atStart controls position in the pane's children list, which is what JavaFX's default Tab
  // traversal follows (GridPane.rowIndex only affects visual position) - dateEditPicker needs to be
  // first in the tab chain, so it is inserted at index 0 instead of appended
  private fun relocate(
    node: Node,
    target: GridPane,
    row: Int,
    column: Int,
    columnSpan: Int = 1,
    atStart: Boolean = false,
  ) {
    (node.parent as? GridPane)?.children?.remove(node)
    if (node !in target.children) {
      if (atStart) target.children.add(0, node) else target.children.add(node)
    }
    GridPane.setRowIndex(node, row)
    GridPane.setColumnIndex(node, column)
    GridPane.setColumnSpan(node, columnSpan)
  }

  private fun typeButtonFor(tab: OperationTab): ToggleButton = when (tab) {
    OperationTab.INCOME -> typeIncomeButton
    OperationTab.EXPENSE -> typeExpenseButton
    OperationTab.TRANSFER -> typeTransferButton
    OperationTab.EXCHANGE -> typeExchangeButton
    OperationTab.DETAILS -> throw IllegalArgumentException("DETAILS has no type button")
  }

  private fun typeEnum(button: ToggleButton): OperationTab = when (button) {
    typeIncomeButton -> OperationTab.INCOME
    typeExpenseButton -> OperationTab.EXPENSE
    typeTransferButton -> OperationTab.TRANSFER
    else -> OperationTab.EXCHANGE
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
      OperationTab.INCOME to typeIncomeButton,
      OperationTab.EXPENSE to typeExpenseButton,
      OperationTab.TRANSFER to typeTransferButton,
      OperationTab.EXCHANGE to typeExchangeButton,
    ).forEach { (t, button) ->
      button.style = if (t == highlightTab) "-fx-font-weight: bold;" else ""
    }
    val typeTab = viewOnlyTypeTab
    if (viewOnly && typeTab != null) {
      // history / read-only: keep only the type button matching the operation type visible,
      // alongside the two view-mode buttons and the "Проводки" pane.
      listOf(
        OperationTab.INCOME to typeIncomeButton,
        OperationTab.EXPENSE to typeExpenseButton,
        OperationTab.TRANSFER to typeTransferButton,
        OperationTab.EXCHANGE to typeExchangeButton,
      ).forEach { (t, button) ->
        val show = t == typeTab
        button.isVisible = show
        button.isManaged = show
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
        // bound to operationContentPane, not itemsEditorBox: the mode/type switch buttons are
        // itemsEditorBox's siblings-in-spirit (viewModeBox/typeSwitchBox), and switching to
        // "Проводки" doesn't change the operation - it must stay clickable even when this field
        // is read-only (history view, or "Разрешить редактирование" off)
        "items",
        operationContentPane,
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
    refreshDeleteButtonDisabled()
  }

  private fun refreshDeleteButtonDisabled() {
    deleteButton.isDisable = formDriver.state == FormState.EMPTY || !editingAllowed
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

  private fun digitsOf(account: AccountObservable?): Int =
    account?.let { currencyService.currencies[it.content.currency.uuid]?.content?.digitsAfterPoint } ?: 2

  private fun currencyName(account: AccountObservable): String =
    currencyService.currencies[account.content.currency.uuid]?.content?.name ?: "-"

  private fun accountComboText(account: AccountObservable): String =
    "${account.content.name}, ${currencyName(account)}"

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
    changedAtLabel.text = event?.created?.formatDateTime() ?: ""
    val creator = event?.creator ?: ""
    val place = event?.coords?.source?.code ?: ""
    creatorLabel.text = listOf(creator, place).filter { it.isNotBlank() }.joinToString(" · ")
    // a NEW, unsaved operation has no history yet
    historyLink.isDisable = tx == null
    historyLink.setOnAction {
      tx?.let { history.show(it.uuid, ObjectKind.TRANSACTION, "История операции") }
    }
  }
}
