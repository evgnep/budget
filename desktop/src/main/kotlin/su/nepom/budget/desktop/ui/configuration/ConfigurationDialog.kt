package su.nepom.budget.desktop.ui.configuration

import jakarta.inject.Inject
import jakarta.inject.Singleton
import javafx.scene.Scene
import javafx.scene.control.Alert
import javafx.scene.control.ButtonType
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.stage.StageStyle
import su.nepom.budget.Global
import su.nepom.budget.desktop.service.DbService
import su.nepom.budget.desktop.service.EventStoreService
import su.nepom.budget.desktop.util.fx.FxmlService
import su.nepom.budget.desktop.util.fx.StageOwner
import su.nepom.budget.model.Place

@Singleton
class ConfigurationDialog @Inject constructor(
    private val fxmlService: FxmlService,
    private val dbService: DbService,
    private val eventsStoreService: EventStoreService,
    private val mainStage: Stage,
): StageOwner {
    override val stage by lazy { makeStage() }

    private fun makeStage(): Stage {
        val stage = Stage()
        stage.scene = Scene(fxmlService.load("configuration/configuration.fxml", stage, this))
        stage.title = "Настройки"
        stage.initStyle(StageStyle.UTILITY);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setOnCloseRequest { if (!shouldClose()) it.consume() }
        return stage
    }

    fun shouldClose(): Boolean =
        if (checkCanWork()) true
        else {
            Alert(
                Alert.AlertType.ERROR,
                "Ряд обязательных настроек не указаны. Если вы продолжите, то приложение будет закрыто",
                ButtonType.CLOSE, ButtonType.PREVIOUS
            ).showAndWait().get().let {
                if (it != ButtonType.CLOSE) false
                else {
                    mainStage.close()
                    true
                }
            }
        }


    private fun checkCanWork(): Boolean =
        dbService.db != null &&
                eventsStoreService.eventStoreFolder.value.isNotEmpty() &&
                Global.currentUser.isNotEmpty() &&
                Global.currentPlace != Place.NULL

    fun showOnStartIfNeed() {
        if (!checkCanWork()) show()
    }

    fun show() {
        stage.show()
    }
}