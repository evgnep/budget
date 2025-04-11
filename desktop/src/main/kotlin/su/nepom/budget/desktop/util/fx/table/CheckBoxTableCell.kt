package su.nepom.budget.desktop.util.fx.table

import javafx.beans.binding.Bindings
import javafx.beans.property.ObjectProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ObservableValue
import javafx.geometry.Pos
import javafx.scene.control.CheckBox
import javafx.scene.control.TableCell
import javafx.scene.control.TableColumn
import javafx.util.Callback
import javafx.util.StringConverter

/**
 * Copy of [javafx.scene.control.cell.CheckBoxTableCell]
 **/
class CheckBoxTableCell<S, T>(converter: StringConverter<T>?) : TableCell<S, T>() {

    private val checkBox = CheckBox()

    private var showLabel = false

    val converterProperty: ObjectProperty<StringConverter<T>?> =
        object : SimpleObjectProperty<StringConverter<T>?>(this, "converter", converter) {
            override fun invalidated() {
                updateShowLabel()
            }
        }

    var converter
        get() = converterProperty.get()
        set(value) { converterProperty.set(value) }


    init {
        // we let getSelectedProperty be null here, as we can always defer to the
        // TableColumn
        styleClass.add("check-box-table-cell")

        // by default the graphic is null until the cell stops being empty
        graphic = null
    }


    public override fun updateItem(item: T, empty: Boolean) {
        super.updateItem(item, empty)

        if (empty) {
            text = null
            this.setGraphic(null)
        } else {
            val c = converter

            if (showLabel) {
                text = c?.toString(item) ?: item.toString()
            }
            graphic = checkBox

            checkBox.selectedProperty().unbind()
            val obsValue = tableColumn.getCellObservableValue(index)
            checkBox.selectedProperty().bind(obsValue as ObservableValue<Boolean>)
            checkBox.disableProperty().bind(
                Bindings.not(
                    tableView.editableProperty().and(
                        tableColumn.editableProperty()
                    ).and(
                        editableProperty()
                    )
                )
            )
        }
    }

    private fun updateShowLabel() {
        this.showLabel = converter != null
        checkBox.alignment = if (showLabel) Pos.CENTER_LEFT else Pos.CENTER
    }

    companion object {
        fun <S, T> forTableColumn(
            column: TableColumn<S, T>
        ): Callback<TableColumn<S, T>, TableCell<S, T>> {
            return forTableColumn(null)
        }

        fun <S, T> forTableColumn(
            converter: StringConverter<T>?
        ): Callback<TableColumn<S, T>, TableCell<S, T>> {
            return Callback { CheckBoxTableCell(converter) }
        }
    }
}