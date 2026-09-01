package su.nepom.budget.desktop.ui.conflict

import jakarta.inject.Inject
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.Parent
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import su.nepom.budget.desktop.ui.account.AccountDetailController
import su.nepom.budget.desktop.ui.currency.CurrencyDetailController
import su.nepom.budget.desktop.ui.transaction.TransactionDetailController
import su.nepom.budget.desktop.util.formatDateTime
import su.nepom.budget.desktop.util.fx.Controller
import su.nepom.budget.desktop.util.fx.FxmlService
import su.nepom.budget.event.AccountContent
import su.nepom.budget.event.ActualEvent
import su.nepom.budget.event.CurrencyContent
import su.nepom.budget.event.TransactionContent
import su.nepom.budget.events.synchronizer.ConflictResolver
import su.nepom.budget.model.ObjectKind
import java.net.URL
import java.util.*

@Suppress("unused")
class ConflictController @Inject constructor(
    private val fxmlService: FxmlService,
) : Controller, Initializable {

    @FXML private lateinit var tabPane: TabPane
    @FXML private lateinit var authorLabel: Label
    @FXML private lateinit var timeLabel: Label
    @FXML private lateinit var useButton: Button
    @FXML private lateinit var leftDetailPane: StackPane
    @FXML private lateinit var rightDetailPane: StackPane

    private lateinit var kind: ObjectKind
    private lateinit var heads: List<ActualEvent>
    private lateinit var stage: Stage
    private var onDone: ((ConflictResolver.Result) -> Unit)? = null
    private var done = false

    private var leftController: Any? = null
    private var rightController: Any? = null

    // set by the dialog before the fxml is fully loaded
    fun configure(
        kind: ObjectKind,
        heads: List<ActualEvent>,
        stage: Stage,
        onDone: (ConflictResolver.Result) -> Unit,
    ) {
        this.kind = kind
        this.heads = heads
        this.stage = stage
        this.onDone = onDone
    }

    override fun initialize(location: URL?, resources: ResourceBundle?) {
        val path = when (kind) {
            ObjectKind.CURRENCY -> "currency/currencyDetail.fxml"
            ObjectKind.ACCOUNT -> "account/accountDetail.fxml"
            ObjectKind.TRANSACTION -> "transaction/transactionDetail.fxml"
            // every simple-object kind gets the generic JSON form for free
            else -> "conflict/jsonDetail.fxml"
        }
        leftDetailPane.children.setAll(loadDetail(path) { leftController = it })
        rightDetailPane.children.setAll(loadDetail(path) { rightController = it })

        heads.forEach { tabPane.tabs.add(Tab(coordsText(it))) }
        tabPane.selectionModel.selectedIndexProperty().addListener { _, _, i -> showHead(i.toInt()) }
        useButton.setOnAction { seedResult(selectedHead()) }

        showHead(0)
        seedResult(heads.first())

        stage.setOnCloseRequest { finish(ConflictResolver.Result(ConflictResolver.Action.CANCEL, null)) }
    }

    private fun loadDetail(path: String, collect: (Any) -> Unit): Parent =
        fxmlService.load(path, stage, null) { controller ->
            collect(controller)
            if (controller is TransactionDetailController) controller.setStage(stage)
        }

    private fun selectedHead(): ActualEvent = heads[tabPane.selectionModel.selectedIndex.coerceAtLeast(0)]

    private fun showHead(index: Int) {
        val event = heads.getOrNull(index) ?: return
        authorLabel.text = event.creator
        timeLabel.text = event.created.formatDateTime()
        when (val c = leftController) {
            is CurrencyDetailController -> c.showReadOnly(event.content as CurrencyContent)
            is AccountDetailController -> c.showReadOnly(event.content as AccountContent)
            is TransactionDetailController -> c.showReadOnly(event.content as TransactionContent)
            is JsonDetailController -> c.showReadOnly(event.content)
        }
    }

    private fun seedResult(source: ActualEvent) {
        val onCancel = { finish(ConflictResolver.Result(ConflictResolver.Action.CANCEL, null)) }
        when (val c = rightController) {
            is CurrencyDetailController -> c.editForConflict(
                source.content as CurrencyContent,
                { finish(ConflictResolver.Result(ConflictResolver.Action.RESOLVE, it)) },
                onCancel,
            )

            is AccountDetailController -> c.editForConflict(
                source.content as AccountContent,
                { finish(ConflictResolver.Result(ConflictResolver.Action.RESOLVE, it)) },
                onCancel,
            )

            is TransactionDetailController -> c.editForConflict(
                source.content as TransactionContent,
                { finish(ConflictResolver.Result(ConflictResolver.Action.RESOLVE, it)) },
                onCancel,
            )

            is JsonDetailController -> c.editForConflict(
                source.content,
                { finish(ConflictResolver.Result(ConflictResolver.Action.RESOLVE, it)) },
                onCancel,
            )
        }
    }

    private fun finish(result: ConflictResolver.Result) {
        if (done) return
        done = true
        onDone?.invoke(result)
    }

    private fun coordsText(event: ActualEvent) = "${event.coords.source.code} #${event.coords.no}"
}
