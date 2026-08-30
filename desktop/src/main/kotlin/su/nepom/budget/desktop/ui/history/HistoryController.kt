package su.nepom.budget.desktop.ui.history

import jakarta.inject.Inject
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import su.nepom.budget.desktop.service.DbService
import su.nepom.budget.desktop.ui.account.AccountDetailController
import su.nepom.budget.desktop.ui.currency.CurrencyDetailController
import su.nepom.budget.desktop.ui.transaction.TransactionDetailController
import su.nepom.budget.desktop.util.fx.Controller
import su.nepom.budget.desktop.util.fx.runAndShowError
import su.nepom.budget.desktop.util.formatDateTime
import su.nepom.budget.event.AccountContent
import su.nepom.budget.event.ActualEvent
import su.nepom.budget.event.CurrencyContent
import su.nepom.budget.event.TransactionContent
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.Uuid
import java.net.URL
import java.util.*

// TODO history form: master list of events for one object, detail shows that version read only
@Suppress("unused")
class HistoryController @Inject constructor(
    private val dbService: DbService,
) : Controller, Initializable {

    private val events = FXCollections.observableArrayList<ActualEvent>()
    private var target: Pair<Uuid, ObjectKind>? = null

    @FXML private lateinit var eventsTable: TableView<ActualEvent>
    @FXML private lateinit var placeColumn: TableColumn<ActualEvent, String>
    @FXML private lateinit var noColumn: TableColumn<ActualEvent, String>
    @FXML private lateinit var creatorColumn: TableColumn<ActualEvent, String>
    @FXML private lateinit var dateColumn: TableColumn<ActualEvent, String>

    @FXML private lateinit var placeholderLabel: Label
    @FXML private lateinit var currencyDetail: Node
    @FXML private lateinit var currencyDetailController: CurrencyDetailController
    @FXML private lateinit var accountDetail: Node
    @FXML private lateinit var accountDetailController: AccountDetailController
    @FXML private lateinit var transactionDetail: Node
    @FXML private lateinit var transactionDetailController: TransactionDetailController

    // set by the dialog before the fxml is fully loaded
    fun configure(uuid: Uuid, kind: ObjectKind) {
        target = uuid to kind
    }

    override fun initialize(location: URL?, resources: ResourceBundle?) {
        eventsTable.items = events
        listOf(placeColumn, noColumn, creatorColumn, dateColumn).forEach { it.isSortable = false }
        placeColumn.setCellValueFactory { SimpleStringProperty(it.value.coords.source.code) }
        noColumn.setCellValueFactory { SimpleStringProperty(it.value.coords.no.toString()) }
        creatorColumn.setCellValueFactory { SimpleStringProperty(it.value.creator) }
        dateColumn.setCellValueFactory { SimpleStringProperty(it.value.created.formatDateTime()) }
        eventsTable.selectionModel.selectedItemProperty().addListener { _, _, event -> showDetail(event) }

        loadEvents()
        showDetail(null)
        if (events.isNotEmpty()) eventsTable.selectionModel.select(0)
    }

    private fun loadEvents() {
        val (uuid, kind) = target ?: return
        val session = dbService.session ?: return
        val loaded = runAndShowError { session.eventDao.getEventsForObject(uuid, kind) }.getOrDefault(emptyList())
        events.setAll(loaded.sortedByDescending { it.created })
    }

    private fun showDetail(event: ActualEvent?) {
        val content = event?.content
        setVisible(placeholderLabel, content == null)
        setVisible(currencyDetail, content is CurrencyContent)
        setVisible(accountDetail, content is AccountContent)
        setVisible(transactionDetail, content is TransactionContent)
        when (content) {
            is CurrencyContent -> currencyDetailController.showReadOnly(content)
            is AccountContent -> accountDetailController.showReadOnly(content)
            is TransactionContent -> transactionDetailController.showReadOnly(content)
            else -> {}
        }
    }

    private fun setVisible(node: Node, visible: Boolean) {
        node.isVisible = visible
        node.isManaged = visible
    }
}
