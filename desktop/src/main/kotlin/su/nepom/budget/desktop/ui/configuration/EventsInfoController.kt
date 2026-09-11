package su.nepom.budget.desktop.ui.configuration

import jakarta.inject.Inject
import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.stage.Stage
import su.nepom.budget.desktop.service.WindowStateService
import su.nepom.budget.desktop.util.fx.Controller
import su.nepom.budget.desktop.util.fx.StageAwareController
import su.nepom.budget.events.EventStoreReader
import su.nepom.budget.model.Place
import java.net.URL
import java.nio.file.Path
import java.util.*

class EventsInfoController @Inject constructor(
    private val windowStateService: WindowStateService,
) : Controller, Initializable, StageAwareController {

    private companion object {
        const val NAME = "eventsInfo"
    }

    @FXML
    private lateinit var eventCountColumn: TableColumn<EventInfo, Number>

    @FXML
    private lateinit var eventPlaceColumn: TableColumn<EventInfo, String>

    @FXML
    private lateinit var eventsTableView: TableView<EventInfo>

    lateinit var eventsPath: Path

    override fun initialize(location: URL?, resources: ResourceBundle?) {
        val items = EventStoreReader(
            eventsPath,
            Place.NULL
        ).getMaxEventsNo().entries.map { EventInfo(it.key.code, it.value) }
        eventsTableView.items = FXCollections.observableArrayList(items)
        eventPlaceColumn.setCellValueFactory { it.value.place }
        eventCountColumn.setCellValueFactory { it.value.count }
        windowStateService.bindTableColumns(NAME, eventsTableView)
    }

    override fun initialize(stage: Stage) {
        windowStateService.bindWindowBounds(stage, NAME)
    }

    private class EventInfo(place: String, count: Int) {
        val place = SimpleStringProperty(place)
        val count = SimpleIntegerProperty(count)
    }
}