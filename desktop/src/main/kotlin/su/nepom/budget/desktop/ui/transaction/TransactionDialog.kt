package su.nepom.budget.desktop.ui.transaction

import jakarta.inject.Inject
import javafx.scene.Scene
import javafx.stage.Stage
import su.nepom.budget.desktop.util.fx.FxmlService
import su.nepom.budget.desktop.util.fx.StageOwner

class TransactionDialog @Inject constructor(
    private val fxmlService: FxmlService,
) : StageOwner {
    override val stage: Stage = makeStage()

    private fun makeStage(): Stage {
        val stage = Stage()
        stage.scene = Scene(fxmlService.load("transaction/transactions.fxml", stage, this))
        stage.title = "Операции"
        stage.show()
        return stage
    }
}
