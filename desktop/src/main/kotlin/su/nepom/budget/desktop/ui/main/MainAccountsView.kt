package su.nepom.budget.desktop.ui.main

import jakarta.inject.Inject
import jakarta.inject.Singleton
import javafx.scene.Parent
import javafx.stage.Stage
import su.nepom.budget.desktop.util.fx.FxmlService
import su.nepom.budget.desktop.util.fx.StageOwner

@Singleton
class MainAccountsView @Inject constructor(
    fxmlService: FxmlService,
    mainStage: Stage,
) : StageOwner {
    override val stage: Stage = mainStage

    val root: Parent = fxmlService.load("main/mainAccounts.fxml", mainStage, this)
}
