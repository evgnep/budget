package su.nepom.budget.desktop.ui.conflict

import javafx.scene.Scene
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.stage.Window
import su.nepom.budget.desktop.util.fx.FxmlService
import su.nepom.budget.desktop.util.fx.StageOwner
import su.nepom.budget.desktop.util.fx.setIcon
import su.nepom.budget.event.ActualEvent
import su.nepom.budget.events.synchronizer.ConflictResolver
import su.nepom.budget.model.ObjectKind

class ConflictResolverDialog(
    fxmlService: FxmlService,
    owner: Window?,
    kind: ObjectKind,
    heads: List<ActualEvent>,
) : StageOwner {
    override val stage: Stage = Stage()

    var result: ConflictResolver.Result = ConflictResolver.Result(ConflictResolver.Action.CANCEL, null)
        private set

    init {
        stage.initModality(Modality.APPLICATION_MODAL)
        stage.setIcon("history")
        if (owner != null) stage.initOwner(owner)
        stage.title = "Разрешение конфликта"
        stage.scene = Scene(fxmlService.load("conflict/conflict.fxml", stage, this) { controller ->
            (controller as ConflictController).configure(kind, heads, stage) { r ->
                result = r
                stage.close()
            }
        })
        stage.showAndWait()
    }
}
