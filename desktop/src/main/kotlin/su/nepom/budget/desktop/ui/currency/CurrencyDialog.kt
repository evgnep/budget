package su.nepom.budget.desktop.ui.currency

import jakarta.inject.Inject
import javafx.scene.Scene
import javafx.stage.Stage
import su.nepom.budget.desktop.util.fx.FxmlService
import su.nepom.budget.desktop.util.fx.StageOwner

class CurrencyDialog @Inject constructor(
    private val fxmlService: FxmlService,
): StageOwner {
    override val stage: Stage = makeStage()

    private fun makeStage(): Stage {
        val stage = Stage()
        stage.scene = Scene(fxmlService.load("currencies.fxml", stage, this))
        stage.title = "Валюты"
        stage.show()
        return stage
    }

}