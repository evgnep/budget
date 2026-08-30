package su.nepom.budget.desktop.ui.history

import jakarta.inject.Inject
import su.nepom.budget.desktop.ui.WindowManager
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.Uuid

// TODO opens the history form for a given object; call from any entity form
class History @Inject constructor(
    private val windowManager: WindowManager,
) {
    fun show(uuid: Uuid, kind: ObjectKind, title: String) {
        windowManager.openHistory(uuid, kind, title)
    }
}
