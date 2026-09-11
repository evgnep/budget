package su.nepom.budget.desktop.ui.subaccount

import jakarta.inject.Inject
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.collections.ListChangeListener
import javafx.collections.transformation.FilteredList
import javafx.collections.transformation.SortedList
import javafx.css.PseudoClass
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.SplitPane
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.stage.Stage
import javafx.util.Duration
import su.nepom.budget.db.Db
import su.nepom.budget.desktop.model.SubaccountObservable
import su.nepom.budget.desktop.service.AccountService
import su.nepom.budget.desktop.service.CurrencyService
import su.nepom.budget.desktop.service.DbService
import su.nepom.budget.desktop.service.SubaccountService
import su.nepom.budget.desktop.service.WindowStateService
import su.nepom.budget.desktop.util.fx.Controller
import su.nepom.budget.desktop.util.fx.Disposable
import su.nepom.budget.desktop.util.fx.MasterDetailFormDriver
import su.nepom.budget.desktop.util.fx.StageAwareController
import su.nepom.budget.desktop.util.fx.WeakListeners
import su.nepom.budget.desktop.util.fx.runAndShowError
import su.nepom.budget.desktop.util.fx.table.CheckBoxTableCell
import su.nepom.budget.model.AccountId
import su.nepom.budget.model.RawMoney
import su.nepom.budget.model.minus
import su.nepom.budget.model.plus
import su.nepom.budget.utils.format
import java.net.URL
import java.util.*

@Suppress("unused")
class SubaccountsController @Inject constructor(
    private val dbService: DbService,
    private val subaccountService: SubaccountService,
    private val accountService: AccountService,
    private val currencyService: CurrencyService,
    private val windowStateService: WindowStateService,
) : Controller, Initializable, StageAwareController, Disposable {

    private var accountId: AccountId? = null

    private val weakListeners = WeakListeners()
    private val refreshPause = PauseTransition(Duration.millis(200.0)).apply { setOnFinished { refreshRest() } }

    @FXML
    private lateinit var accountNameLabel: Label

    @FXML
    private lateinit var accountRestLabel: Label

    @FXML
    private lateinit var differenceLabel: Label

    @FXML
    private lateinit var showHiddenCheckbox: CheckBox

    @FXML
    private lateinit var createNewButton: Button

    @FXML
    private lateinit var subaccountsSplitter: SplitPane

    @FXML
    private lateinit var subaccountsTableView: TableView<SubaccountObservable>

    @FXML
    private lateinit var nameColumn: TableColumn<SubaccountObservable, String>

    @FXML
    private lateinit var restColumn: TableColumn<SubaccountObservable, String>

    @FXML
    private lateinit var hiddenColumn: TableColumn<SubaccountObservable, Boolean>

    @FXML
    private lateinit var subaccountDetailController: SubaccountDetailController

    private lateinit var masterDetailFormDriver: MasterDetailFormDriver<SubaccountObservable, SubaccountObservable>

    // set by the window manager before the fxml is fully loaded, so it is ready when initialize() runs
    fun configure(accountId: AccountId) {
        this.accountId = accountId
    }

    override fun initialize(location: URL?, resources: ResourceBundle?) {
        val accountId = requireNotNull(accountId) { "configure() must be called before opening the screen" }
        val account = accountService.accounts[accountId.uuid]
        val digitsAfterPoint = digitsFor(accountId)

        accountNameLabel.text = account?.content?.name ?: ""
        subaccountDetailController.configure(accountId, digitsAfterPoint)

        val allForAccount = FilteredList(subaccountService.subaccounts) { it.content.accountId == accountId }
        val visible = FilteredList(allForAccount) { showHiddenCheckbox.isSelected || !it.content.isHidden }
        val sorted = SortedList(visible, compareBy { it.content.name.lowercase() })
        subaccountsTableView.items = sorted

        showHiddenCheckbox.selectedProperty().addListener { _, _, _ -> visible.setPredicate {
            showHiddenCheckbox.isSelected || !it.content.isHidden
        } }
        hiddenColumn.visibleProperty().bind(showHiddenCheckbox.selectedProperty())

        nameColumn.setCellValueFactory { it.value.name }
        restColumn.setCellValueFactory { it.value.rest.map { rest -> rest.format(digitsAfterPoint) } }
        hiddenColumn.setCellValueFactory { it.value.hidden }
        hiddenColumn.cellFactory = CheckBoxTableCell.forTableColumn(hiddenColumn)

        masterDetailFormDriver = MasterDetailFormDriver(
            subaccountsTableView.selectionModel,
            subaccountDetailController.formDriver,
            createNewButton
        )

        // any change to a subaccount's rest/hidden affects the difference shown in the header
        allForAccount.addListener(ListChangeListener { updateDifference(accountId, digitsAfterPoint) })
        weakListeners.addListenerAndCallNow(dbService.sessionProperty) { _, _, session ->
            if (session != null) {
                weakListeners.subscribe(session.db, Db.SubscribeKind.TRANSACTION, Db.SubscribeKind.ACCOUNT) {
                    Platform.runLater { refreshPause.playFromStart() }
                }
            }
            refreshRest()
        }
    }

    override fun initialize(stage: Stage) {
        windowStateService.bindSplitPane(stage, NAME, subaccountsSplitter)
        windowStateService.bindTableColumns(NAME, subaccountsTableView)
    }

    override fun dispose() {
        weakListeners.dispose()
        refreshPause.stop()
    }

    private var lastAccountRest = RawMoney.ZERO

    private fun refreshRest() {
        val accountId = accountId ?: return
        val session = dbService.session ?: return
        val digitsAfterPoint = digitsFor(accountId)
        val rest = runAndShowError { session.transactionDao.accountRest(setOf(accountId), null) }
            .getOrDefault(emptyMap())[accountId] ?: RawMoney.ZERO
        lastAccountRest = rest
        accountRestLabel.text = rest.format(digitsAfterPoint)
        updateDifference(accountId, digitsAfterPoint)
    }

    private fun digitsFor(accountId: AccountId): Int {
        val account = accountService.accounts[accountId.uuid] ?: return 2
        return currencyService.currencies[account.content.currency.uuid]?.content?.digitsAfterPoint ?: 2
    }

    private fun updateDifference(accountId: AccountId, digitsAfterPoint: Int) {
        val visibleSubaccounts = subaccountService.subaccounts.filter {
            it.content.accountId == accountId && !it.content.isHidden
        }
        if (visibleSubaccounts.isEmpty()) {
            differenceLabel.text = ""
            differenceLabel.pseudoClassStateChanged(MISMATCH, false)
            return
        }
        val sum = visibleSubaccounts.fold(RawMoney.ZERO) { acc, s -> acc + s.content.rest }
        val diff = sum - lastAccountRest
        differenceLabel.text = diff.format(digitsAfterPoint)
        differenceLabel.pseudoClassStateChanged(MISMATCH, diff.value != 0L)
    }

    companion object {
        const val NAME = "subaccounts"
        private val MISMATCH: PseudoClass = PseudoClass.getPseudoClass("mismatch")
    }
}
