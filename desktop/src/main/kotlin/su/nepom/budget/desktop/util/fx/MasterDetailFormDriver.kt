package su.nepom.budget.desktop.util.fx

import javafx.scene.control.Button
import javafx.scene.control.SelectionModel
import su.nepom.budget.desktop.util.db.ObservableEntity

class MasterDetailFormDriver<MasterT, DetailT : ObservableEntity<*>>(
    val selectionModel: SelectionModel<MasterT>,
    val formDriver: FormDriver<*, DetailT>,
    val newButton: Button,
    // master and detail are the same entity unless overridden (e.g. transaction items master list
    // vs. whole-transaction detail form)
    @Suppress("UNCHECKED_CAST")
    private val toDetail: (MasterT) -> DetailT? = { it as DetailT },
    // whether two master rows point at the same detail entity - moving selection between them must
    // not reload the form or ask to save pending changes. Default: every row is its own entity.
    private val sameDetail: (MasterT, MasterT) -> Boolean = { _, _ -> false },
    // called whenever the detail entity behind the selection actually changes (not on every master
    // selection change - see sameDetail)
    private val onDetailChanged: ((DetailT?) -> Unit)? = null,
) {
    private var lastSelected: Int = -1
    private var lastMaster: MasterT? = null

    init {
        selectionModel.selectedIndexProperty().addListener { _, _, newValue -> selectedIndexChanged(newValue.toInt()) }
        newButton.setOnAction { onNewButtonClick() }
    }

    private fun onNewButtonClick() {
        if (formDriver.newItem()) {
            lastSelected = -1
            lastMaster = null
            selectionModel.select(lastSelected)
            onDetailChanged?.invoke(null)
        }
    }

    private fun selectedIndexChanged(index: Int) {
        if (lastSelected == index) return
        val master = if (index == -1) null else selectionModel.selectedItem
        val previousMaster = lastMaster
        if (master != null && previousMaster != null && sameDetail(master, previousMaster)) {
            lastSelected = index
            lastMaster = master
            return
        }
        val ok = if (master == null) formDriver.clear() else formDriver.setItem(toDetail(master))
        if (!ok) {
            selectionModel.select(lastSelected)
        } else {
            lastSelected = index
            lastMaster = master
            onDetailChanged?.invoke(master?.let(toDetail))
        }
    }
}