package su.nepom.budget.desktop.ui.transaction

import javafx.scene.Scene
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.stage.Window
import su.nepom.budget.desktop.util.fx.FxmlService
import su.nepom.budget.desktop.util.fx.StageOwner
import su.nepom.budget.model.AccountId

class AccountPickerDialog(
    fxmlService: FxmlService,
    owner: Window?,
    preselected: Set<AccountId>,
    multi: Boolean,
) : StageOwner {
    override val stage: Stage = Stage()

    var result: Set<AccountId>? = null
        private set

    init {
        stage.initModality(Modality.APPLICATION_MODAL)
        if (owner != null) stage.initOwner(owner)
        stage.title = "Выбор счетов"
        stage.scene = Scene(fxmlService.load("transaction/accountPicker.fxml", stage, this) { controller ->
            (controller as AccountPickerController).configure(preselected, multi) { picked ->
                result = picked
                stage.close()
            }
        })
        stage.showAndWait()
    }
}
