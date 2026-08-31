package su.nepom.budget.desktop.ui.configuration

import jakarta.inject.Inject
import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.Scene
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.Pane
import javafx.stage.DirectoryChooser
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.stage.StageStyle
import net.synedra.validatorfx.Severity
import net.synedra.validatorfx.Validator
import su.nepom.budget.desktop.service.DbService
import su.nepom.budget.desktop.service.EventStoreService
import su.nepom.budget.desktop.service.SettingsService
import su.nepom.budget.desktop.util.fx.ValidatorHelper
import su.nepom.budget.desktop.util.fx.FxmlService
import su.nepom.budget.desktop.util.fx.StageOwner
import su.nepom.budget.desktop.util.fx.StageOwnerAwareController
import java.net.URL
import java.nio.file.Path
import java.util.*
import kotlin.io.path.absolute
import kotlin.io.path.isDirectory


class ConfigurationController @Inject constructor(
    private val dbService: DbService,
    private val eventStoreService: EventStoreService,
    private val settingsService: SettingsService,
    private val fxmlService: FxmlService,
) : Initializable, StageOwnerAwareController {
    @FXML
    private lateinit var placeTextField: TextField

    @FXML
    private lateinit var usernameTextField: TextField

    @FXML
    private lateinit var okButton: Button

    @FXML
    private lateinit var eventsFolderButton: Button

    @FXML
    private lateinit var eventsInfoButton: Button

    @FXML
    private lateinit var eventsPathTextField: TextField

    @FXML
    private lateinit var dataDbFailPane: Pane

    @FXML
    private lateinit var dataDbPathTextField: TextField

    @FXML
    private lateinit var dataDbStatLabel: Label

    @FXML
    private lateinit var dataDbOKPane: Pane

    private lateinit var dialog: ConfigurationDialog

    private val validator = Validator()

    private lateinit var validatorHelper: ValidatorHelper

    @FXML
    private fun createDbButtonClick(actionEvent: ActionEvent) {
        afterDbAction(dbService.create())
    }

    @FXML
    private fun repeatDbFindButtonClick(actionEvent: ActionEvent) {
        afterDbAction(dbService.openExisting())
    }

    @FXML
    private fun selectEventsFolderButtonClick(actionEvent: ActionEvent) {
        val dc = DirectoryChooser()
        dc.title = "Выберите папку с событиями"
        runCatching { Path.of(eventsPathTextField.text).absolute() }.onSuccess {
            if (it.isDirectory()) dc.initialDirectory = it.toFile()
        }
        dc.showDialog(dialog.stage)?.also {
            eventsPathTextField.text = it.absolutePath
        }
    }

    @FXML
    private fun showEventsInfoButtonClick(actionEvent: ActionEvent) {
        val stage = Stage()
        stage.scene = Scene(fxmlService.load("configuration/eventsInfo.fxml", stage, dialog) {
            (it as EventsInfoController).eventsPath = Path.of(eventsPathTextField.text)
        })
        stage.title = "Информация о хранилище событий"
        stage.initStyle(StageStyle.UTILITY);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.show()
    }

    @FXML
    private fun okButtonClick(actionEvent: ActionEvent) {
        if (validatorHelper.setValuesToStorage()) dialog.stage.close()
    }

    @FXML
    private fun cancelButtonClick(actionEvent: ActionEvent) {
        if (dialog.shouldClose()) {
            validatorHelper.setValuesToFields()
            dialog.stage.close()
        }
    }

    private fun afterDbAction(error: String) {
        if (error.isEmpty()) recalcDbStatus()
        else {
            Alert(Alert.AlertType.ERROR, error).showAndWait()
        }
    }


    override fun initialize(location: URL?, resources: ResourceBundle?) {
        recalcDbStatus()
        setInitialValues()
        setupValidator()
    }

    private fun setInitialValues() {
        dataDbPathTextField.text = dbService.dbPath.toString()
        eventsPathTextField.text = eventStoreService.eventStoreFolder.value
    }

    private fun setupValidator() {
        validatorHelper = ValidatorHelper.builder(validator)
            .dependsOn("eventsPath", eventsPathTextField.textProperty())
            .decorates(eventsPathTextField)
            .check {
                it.validationResultProperty().addListener { _, _, newValue ->
                    eventsInfoButton.isDisable = newValue.messages.any { it.severity == Severity.ERROR }
                }
            }
            .immediate(eventStoreService.eventStoreFolder)
            .dependsOn("username", usernameTextField.textProperty())
            .decorates(usernameTextField)
            .immediate(settingsService.creator)
            .dependsOn("place", placeTextField.textProperty())
            .decorates(placeTextField)
            .immediate(settingsService.place)
            .build()
    }

    override fun initialize(owner: StageOwner) {
        dialog = owner as ConfigurationDialog
    }

    private fun recalcDbStatus() {
        val error = dbService.db == null
        dataDbFailPane.isVisible = error
        dataDbFailPane.isManaged = error
        dataDbOKPane.isVisible = !error
        dataDbOKPane.isManaged = !error
        eventsPathTextField.isDisable = error
        eventsFolderButton.isDisable = error
        eventsInfoButton.isDisable = error
        usernameTextField.isDisable = error
        placeTextField.isDisable = error
        okButton.isDisable = error

        dbService.session?.let { "Всего транзакций: " + it.transactionDao.count() }?.also { dataDbStatLabel.text = it }
    }
}