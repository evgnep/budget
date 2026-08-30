package su.nepom.budget.desktop.ui.transaction

import jakarta.inject.Inject
import javafx.stage.Window
import su.nepom.budget.desktop.util.fx.FxmlService
import su.nepom.budget.model.AccountId

class AccountPicker @Inject constructor(
    private val fxmlService: FxmlService,
) {
    /**
     * Opens a modal dialog to search and pick accounts. Returns null if cancelled.
     */
    fun pick(owner: Window?, preselected: Set<AccountId> = emptySet(), multi: Boolean = true): Set<AccountId>? =
        AccountPickerDialog(fxmlService, owner, preselected, multi).result
}
