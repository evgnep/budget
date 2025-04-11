package su.nepom.budget.desktop.util.fx

import javafx.scene.control.Button
import javafx.scene.control.SelectionModel
import su.nepom.budget.desktop.util.db.ObservableEntity

class MasterDetailFormDriver<ObservableEntityT : ObservableEntity<*>>(
    val selectionModel: SelectionModel<ObservableEntityT>,
    val formDriver: FormDriver<*, ObservableEntityT>,
    val newButton: Button,
) {
    private var lastSelected: Int = -1

    init {
        selectionModel.selectedIndexProperty().addListener { _, _, newValue -> selectedIndexChanged(newValue.toInt()) }
        newButton.setOnAction { onNewButtonClick() }
    }

    private fun onNewButtonClick() {
        if (formDriver.newItem()) {
            lastSelected = -1
            selectionModel.select(lastSelected)
        }
    }

    private fun selectedIndexChanged(index: Int) {
        if (lastSelected == index) return
        val ok = when (index) {
            -1 -> formDriver.clear()
            else -> formDriver.setItem(selectionModel.selectedItem)
        }
        if (!ok) {
            selectionModel.select(lastSelected)
        } else {
            lastSelected = index
        }
    }
}