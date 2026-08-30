package su.nepom.budget.desktop.ui.history

import jakarta.inject.Inject
import javafx.stage.Window
import su.nepom.budget.desktop.util.fx.FxmlService
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.Uuid

// TODO opens the history form for a given object; call from any entity form
class History @Inject constructor(
    private val fxmlService: FxmlService,
) {
    fun show(owner: Window?, uuid: Uuid, kind: ObjectKind, title: String) {
        HistoryDialog(fxmlService, owner, uuid, kind, title)
    }
}
