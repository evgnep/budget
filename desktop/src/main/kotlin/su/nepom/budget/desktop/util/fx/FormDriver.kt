package su.nepom.budget.desktop.util.fx

import javafx.beans.property.Property
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.scene.Node
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBoxBase
import javafx.scene.control.TextField
import javafx.scene.control.TextInputControl
import net.synedra.validatorfx.Check
import net.synedra.validatorfx.Severity
import net.synedra.validatorfx.Validator
import su.nepom.budget.db.Session
import su.nepom.budget.desktop.util.CheckError
import su.nepom.budget.desktop.util.CheckOk
import su.nepom.budget.desktop.util.CheckWarning
import su.nepom.budget.desktop.util.Checkable
import su.nepom.budget.desktop.util.db.ObservableEntity
import su.nepom.budget.desktop.util.db.ObservableEntityBuilder
import su.nepom.budget.desktop.util.db.ObservableEntityFactory
import java.util.function.Consumer
import kotlin.jvm.optionals.getOrNull

class FormDriver<Builder : ObservableEntityBuilder<O>, O : ObservableEntity<*>>
private constructor(
    val okButton: Button,
    val cancelButton: Button,
    val validator: Validator,
    private val factory: ObservableEntityFactory<O, Builder>,
    private val weakListeners: WeakListeners,
    private val fields: List<FieldInfo<Builder, O>>,
    private val session: ReadOnlyObjectProperty<Session?>,
) {

    var item: O? = null
        private set

    var state: FormState = FormState.EMPTY
        private set

    private var ignoreChanges = false

    init {
        okButton.setOnAction { okButtonClicked() }
        okButton.isDisable = true
        cancelButton.setOnAction { cancelButtonClicked() }
        cancelButton.isDisable = true
        fields.forEach { field ->
            field.fieldProperty.addListener(weakListeners { onSomethingChanged() })
        }
        setState(FormState.EMPTY)
    }

    fun setItem(item: O?): Boolean {
        if (!okButtonClicked()) return false
        this.item = item
        setState(if (item == null) FormState.EMPTY else FormState.VIEW)
        return true
    }

    var readOnly = false
        private set

    // when false, existing items (VIEW state) are shown read-only; NEW items stay editable
    var editingEnabled = true
        private set

    fun setEditingEnabled(enabled: Boolean) {
        if (editingEnabled == enabled) return
        editingEnabled = enabled
        if (state == FormState.VIEW) setState(FormState.VIEW)
    }

    // conflict-resolution mode: OK returns the built content through this sink instead of saving to
    // the DB; when set, cancelSink is used for the Cancel button and Cancel stays always enabled
    var contentSink: ((su.nepom.budget.event.ActualVersionContent) -> Unit)? = null
    var cancelSink: (() -> Unit)? = null

    // show item's values with fields editable and OK active from the start (no DB session needed)
    fun editItem(item: O) {
        this.item = item
        setState(FormState.VIEW)
        setState(FormState.EDIT)
    }

    // view-only mode for the history form: show the item, no editing possible
    fun showReadOnly(item: O?) {
        readOnly = true
        okButton.isVisible = false
        okButton.isManaged = false
        cancelButton.isVisible = false
        cancelButton.isManaged = false
        this.item = item
        setState(if (item == null) FormState.EMPTY else FormState.VIEW)
    }

    private fun setState(state: FormState) {
        this.state = state
        ignoreChanges = true
        when (state) {
            FormState.EMPTY -> {
                okButton.isDisable = true
                cancelButton.isDisable = true
                item = null
                fields.forEach {
                    it.setField(item)
                    it.field.isDisable = true
                }
                validator.clear()
            }
            FormState.VIEW -> {
                okButton.isDisable = true
                cancelButton.isDisable = true
                fields.forEach {
                    it.setField(item)
                    if (readOnly || !editingEnabled) {
                        it.field.applyReadOnly()
                    } else {
                        it.field.applyEditable()
                        it.field.isDisable = it.disabledInEditMode || it.alwaysDisabled
                    }
                }
            }
            FormState.EDIT -> {
                okButton.isDisable = false
                cancelButton.isDisable = false
                fields.forEach {
                    it.field.applyEditable()
                    it.field.isDisable = it.disabledInEditMode || it.alwaysDisabled
                }
            }
            FormState.NEW -> {
                okButton.isDisable = false
                cancelButton.isDisable = false
                item = null
                fields.forEach {
                    it.setField(item)
                    it.field.applyEditable()
                    it.field.isDisable = it.alwaysDisabled
                }
                validator.clear()
            }
        }
        // conflict dialog always allows Cancel, whatever the form state
        if (cancelSink != null) cancelButton.isDisable = false
        ignoreChanges = false
    }

    fun newItem(): Boolean {
        if (!okButtonClicked()) return false
        setState(FormState.NEW)
        return true
    }

    fun clear(): Boolean {
        if (!okButtonClicked()) return false
        validator.clear()
        this.item = null
        setState(FormState.EMPTY)
        return true
    }

    private fun onSomethingChanged() {
        if ((state == FormState.VIEW) && !ignoreChanges && !readOnly && editingEnabled) {
            setState(FormState.EDIT)
        }
    }

    private fun okButtonClicked(): Boolean {
        if (state != FormState.EDIT && state != FormState.NEW) return true
        val session = session.value
        val sink = contentSink
        if (session == null && sink == null) {
            Alert(Alert.AlertType.ERROR, "Нет базы данных", ButtonType.OK)
                .showAndWait()
            return false
        }
        validator.validate()
        val messages = validator.validationResult.messages.groupBy { it.severity }
        messages[Severity.ERROR]?.run {
            Alert(Alert.AlertType.ERROR, "Сначала исправьте ошибки:\n" + joinToString("\n") { it.text }, ButtonType.OK)
                .showAndWait()
            return false
        }
        messages[Severity.WARNING]?.run {
            if (Alert(
                    Alert.AlertType.WARNING,
                    "Есть предупреждения. Все равно продолжить?\n" + joinToString("\n") { it.text },
                    ButtonType.YES,
                    ButtonType.NO
                ).showAndWait().getOrNull() != ButtonType.YES
            ) return false
        }
        val item = this.item ?: factory.createNew()
        this.item = item
        val builder = factory.builder(item)
        fields.forEach { it.setValue(builder) }
        if (sink != null) {
            val content = runAndShowError { builder.buildContent() }.getOrElse { return false }
            sink(content)
            setState(FormState.VIEW)
            return true
        }
        runAndShowError { builder.saveAndUpdate(session!!, item) }.onFailure { return false }
        setState(FormState.VIEW)
        return true
    }

    private fun cancelButtonClicked() {
        cancelSink?.let { it(); return }
        setState(if (state == FormState.EDIT) FormState.VIEW else FormState.EMPTY)
    }

    class FormDriverBuilder<Builder : ObservableEntityBuilder<O>, O : ObservableEntity<*>>(
        private val okButton: Button,
        private val cancelButton: Button,
        private val factory: ObservableEntityFactory<O, Builder>,
        private val session: ReadOnlyObjectProperty<Session?>,
    ) {
        private val weakListeners = WeakListeners()
        private val validator = Validator()
        private val fields = mutableListOf<FieldInfo<Builder, O>>()

        fun idField(field: TextField) =
            field("id", field, field.textProperty(), { it?.uuid?.id ?: "-"}, {}, alwaysDisabled = true)

        fun <T> field(
            key: String,
            field: Node,
            fieldProperty: Property<T>,
            getValue: (O?) -> T,
            setValue: Builder.(T) -> Unit,
            disabledInEditMode: Boolean = false,
            alwaysDisabled: Boolean = false,
            configurator: CheckBuilder<T>.() -> Unit = {},
        ): FormDriverBuilder<Builder, O> {
            val fieldInfo = FieldInfo<Builder, O>(
                key,
                field,
                fieldProperty,
                { fieldProperty.value = getValue(it) },
                { it.setValue(fieldProperty.value) },
                disabledInEditMode,
                alwaysDisabled
            )
            fields.add(fieldInfo)
            CheckBuilder(fieldInfo, fieldProperty).configurator()
            return this
        }

        fun build() = FormDriver(okButton, cancelButton, validator, factory, weakListeners, fields, session)

        inner class CheckBuilder<T>(
            private val fieldInfo: FieldInfo<Builder, O>,
            private val fieldProperty: Property<T>
        ) {
            private val check: Check by lazy {
                validator.createCheck()
                    .decorates(fieldInfo.field)
                    .dependsOn(fieldInfo.key, fieldInfo.fieldProperty)
            }

            fun withCheckable(checkable: Checkable<T>): CheckBuilder<T> {
                check.withMethod {
                    when (val result = checkable.check(fieldProperty.value)) {
                        is CheckOk -> {}
                        is CheckWarning -> it.warn(result.message)
                        is CheckError -> it.error(result.message)
                    }
                }
                return this
            }

            fun withMethod(method: Consumer<Check.Context>): CheckBuilder<T> {
                check.withMethod(method)
                return this
            }

            fun dependsOn(key: String, dependency: Property<*>): CheckBuilder<T> {
                check.dependsOn(key, dependency)
                return this
            }

            fun check(acton: (Check) -> Unit): CheckBuilder<T> {
                acton(check)
                return this
            }

            fun immediate(): CheckBuilder<T> {
                check.immediate()
                return this
            }

            fun immediateClear(): CheckBuilder<T> {
                check.immediateClear()
                return this
            }

            fun explicit(): CheckBuilder<T> {
                check.explicit()
                return this
            }
        }
    }

    data class FieldInfo<Builder : ObservableEntityBuilder<O>, O : ObservableEntity<*>>(
        val key: String,
        val field: Node,
        val fieldProperty: Property<*>,
        val setField: (O?) -> Unit,
        val setValue: (Builder) -> Unit,
        val disabledInEditMode: Boolean,
        val alwaysDisabled: Boolean,
    )

    companion object {
        fun <Builder : ObservableEntityBuilder<O>, O : ObservableEntity<*>> builder(
            okButton: Button,
            cancelButton: Button,
            factory: ObservableEntityFactory<O, Builder>,
            session: ReadOnlyObjectProperty<Session?>,
        ) = FormDriverBuilder(okButton, cancelButton, factory, session)
    }
}

enum class FormState {
    EMPTY,
    VIEW,
    EDIT,
    NEW,
}

// make a field non-editable but still usable for text selection / copy (history form)
private fun Node.applyReadOnly() {
    when (this) {
        is TextInputControl -> {
            isDisable = false
            isEditable = false
            isFocusTraversable = false
        }
        is ComboBoxBase<*>, is CheckBox -> {
            isDisable = false
            isMouseTransparent = true
            isFocusTraversable = false
        }
        else -> isDisable = true
    }
}

// undo applyReadOnly so the field can be edited again
private fun Node.applyEditable() {
    when (this) {
        is TextInputControl -> {
            isEditable = true
            isFocusTraversable = true
        }
        is ComboBoxBase<*>, is CheckBox -> {
            isMouseTransparent = false
            isFocusTraversable = true
        }
        else -> isDisable = false
    }
}