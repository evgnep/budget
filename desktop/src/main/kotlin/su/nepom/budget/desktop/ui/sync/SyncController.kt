package su.nepom.budget.desktop.ui.sync

import jakarta.inject.Inject
import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import su.nepom.budget.desktop.service.EventStoreService
import su.nepom.budget.desktop.service.SyncOutcome
import su.nepom.budget.desktop.service.SyncPlaceInfo
import su.nepom.budget.desktop.service.SyncStoreInfo
import su.nepom.budget.desktop.service.WindowStateService
import su.nepom.budget.desktop.util.formatDateTime
import su.nepom.budget.desktop.util.fx.Controller
import java.net.URL
import java.util.ResourceBundle

@Suppress("unused")
class SyncController @Inject constructor(
    private val eventStoreService: EventStoreService,
    private val windowStateService: WindowStateService,
) : Controller, Initializable {

    private companion object {
        const val NAME = "main"
    }

    @FXML private lateinit var unsavedLabel: Label
    @FXML private lateinit var saveNowButton: Button
    @FXML private lateinit var sourceLabel: Label
    @FXML private lateinit var placesTable: TableView<SyncPlaceInfo>
    @FXML private lateinit var placeColumn: TableColumn<SyncPlaceInfo, String>
    @FXML private lateinit var onDiskColumn: TableColumn<SyncPlaceInfo, Number>
    @FXML private lateinit var importedColumn: TableColumn<SyncPlaceInfo, Number>
    @FXML private lateinit var syncButton: Button
    @FXML private lateinit var progressLabel: Label
    @FXML private lateinit var lastSyncLabel: Label

    private val rows = FXCollections.observableArrayList<SyncPlaceInfo>()

    override fun initialize(location: URL?, resources: ResourceBundle?) {
        unsavedLabel.textProperty().bind(eventStoreService.savingGap.asString())
        saveNowButton.setOnAction { eventStoreService.saveNow() }
        syncButton.setOnAction { eventStoreService.syncNow() }

        progressLabel.visibleProperty().bind(eventStoreService.syncInProgress)
        progressLabel.managedProperty().bind(eventStoreService.syncInProgress)
        syncButton.disableProperty().bind(eventStoreService.syncInProgress)

        placesTable.items = rows
        placeColumn.setCellValueFactory { SimpleStringProperty(it.value.place) }
        onDiskColumn.setCellValueFactory { SimpleIntegerProperty(it.value.onDisk) }
        importedColumn.setCellValueFactory { SimpleIntegerProperty(it.value.imported) }
        windowStateService.bindTableColumns(NAME, placesTable)

        eventStoreService.storeInfo.addListener { _, _, info -> showStoreInfo(info) }
        showStoreInfo(eventStoreService.storeInfo.value)

        eventStoreService.lastSyncResult.addListener { _, _, outcome -> showOutcome(outcome) }
        showOutcome(eventStoreService.lastSyncResult.value)

        eventStoreService.refreshStoreInfo()
    }

    private fun showStoreInfo(info: SyncStoreInfo?) {
        sourceLabel.text = info?.source ?: "-"
        rows.setAll(info?.places ?: emptyList())
    }

    private fun showOutcome(outcome: SyncOutcome?) {
        lastSyncLabel.text = when {
            outcome == null -> "-"
            outcome.error != null -> "${outcome.at.formatDateTime()}: ошибка - ${outcome.error}"
            else -> buildString {
                append(outcome.at.formatDateTime())
                append(": импортировано ")
                append(outcome.imported)
                if (outcome.readingErrors > 0) append(", ошибок чтения: ${outcome.readingErrors}")
            }
        }
    }
}
