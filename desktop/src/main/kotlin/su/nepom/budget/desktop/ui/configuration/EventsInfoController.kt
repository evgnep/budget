package su.nepom.budget.desktop.ui.configuration

import jakarta.inject.Inject
import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import su.nepom.budget.desktop.util.fx.Controller
import su.nepom.budget.events.EventStoreReader
import su.nepom.budget.model.Place
import java.net.URL
import java.nio.file.Path
import java.util.*

class EventsInfoController @Inject constructor() : Controller, Initializable {
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
    }

    private class EventInfo(place: String, count: Int) {
        val place = SimpleStringProperty(place)
        val count = SimpleIntegerProperty(count)
    }
}