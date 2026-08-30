package su.nepom.budget.desktop.ui.history

import javafx.scene.Scene
import javafx.stage.Stage
import javafx.stage.Window
import su.nepom.budget.desktop.util.fx.FxmlService
import su.nepom.budget.desktop.util.fx.StageOwner
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.Uuid

class HistoryDialog(
    fxmlService: FxmlService,
    owner: Window?,
    uuid: Uuid,
    kind: ObjectKind,
    title: String,
) : StageOwner {
    override val stage: Stage = Stage()

    init {
        if (owner != null) stage.initOwner(owner)
        stage.title = title
        stage.scene = Scene(fxmlService.load("history/history.fxml", stage, this) { controller ->
            if (controller is HistoryController) controller.configure(uuid, kind)
        })
        stage.show()
    }
}
