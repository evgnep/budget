package su.nepom.budget.desktop.util.fx

import javafx.beans.property.Property
import javafx.scene.Node
import javafx.scene.control.Alert
import javafx.scene.control.ButtonType
import net.synedra.validatorfx.Check
import net.synedra.validatorfx.Severity
import net.synedra.validatorfx.Validator
import su.nepom.budget.desktop.util.CheckError
import su.nepom.budget.desktop.util.CheckOk
import su.nepom.budget.desktop.util.CheckWarning
import su.nepom.budget.desktop.util.db.CheckableDatabaseProperty
import kotlin.jvm.optionals.getOrNull

class ValidatorHelper private constructor(
    private val validator: Validator,
    private val storageSetters: List<() -> Unit>,
    private val fieldSetters: List<() -> Unit>,
    @Suppress("unused") private val weakListeners: WeakListeners,
) {
    fun setValuesToStorage(): Boolean {
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
        storageSetters.forEach { it() }
        return true
    }

    fun setValuesToFields() {
        fieldSetters.forEach { it() }
    }

    class Builder(private val validator: Validator) {
        private val storageSetters = mutableListOf<() -> Unit>()
        private val fieldSetters = mutableListOf<() -> Unit>()
        private val weakListeners = WeakListeners()

        fun <T> dependsOn(key: String, dependency: Property<T>) = CheckBuilder<T>()
            .dependsOn(key, dependency)

        fun build() = ValidatorHelper(validator, storageSetters, fieldSetters, weakListeners)

        inner class CheckBuilder<T> {
            private val check: Check = validator.createCheck()

            private var firstProperty: Property<T>? = null

            fun dependsOn(key: String, dependency: Property<T>): CheckBuilder<T> {
                if (firstProperty == null) firstProperty = dependency
                check.dependsOn(key, dependency)
                return this
            }

            fun decorates(target: Node): CheckBuilder<T> {
                check.decorates(target)
                return this
            }

            fun check(acton: (Check)->Unit): CheckBuilder<T> {
                acton(check)
                return this
            }

            fun immediate(checkable: CheckableDatabaseProperty<T>): Builder {
                check.immediate()
                return build(checkable)
            }

            fun explicit(checkable: CheckableDatabaseProperty<T>): Builder {
                check.explicit()
                return build(checkable)
            }

            private fun build(checkable: CheckableDatabaseProperty<T>): Builder {
                val property = requireNotNull(firstProperty) { "dependsOn should be called" }
                storageSetters.add { checkable.setIfValid(property.value) }
                fieldSetters.add { property.value = checkable.value }
                property.value = checkable.value
                check.withMethod {
                    when (val result = checkable.check(property.value)) {
                        is CheckOk -> {}
                        is CheckWarning -> it.warn(result.message)
                        is CheckError -> it.error(result.message)
                    }
                }
                checkable.property.addListener(weakListeners { _, _, newValue -> property.value = newValue})
                return this@Builder
            }
        }
    }

    companion object {
        fun builder(validator: Validator) = Builder(validator)
    }
}

