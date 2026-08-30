package su.nepom.budget.desktop.ui.transaction

import jakarta.inject.Inject
import javafx.stage.Window
import su.nepom.budget.desktop.util.fx.FxmlService
import su.nepom.budget.model.AccountId
import su.nepom.budget.model.AccountKind
import su.nepom.budget.model.CurrencyId

class AccountPicker @Inject constructor(
    private val fxmlService: FxmlService,
) {
    /**
     * Opens a modal dialog to search and pick accounts. Returns null if cancelled.
     * [currency] and [kind], when set, hard-limit the list on top of the user filters.
     */
    fun pick(
        owner: Window?,
        preselected: Set<AccountId> = emptySet(),
        multi: Boolean = true,
        currency: CurrencyId? = null,
        kind: AccountKind? = null,
    ): Set<AccountId>? =
        AccountPickerDialog(fxmlService, owner, preselected, multi, currency, kind).result
}
