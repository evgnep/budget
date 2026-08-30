package su.nepom.budget.desktop.util.db

import javafx.beans.Observable
import javafx.beans.property.ObjectProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ObservableValue
import su.nepom.budget.db.Db
import su.nepom.budget.db.Session
import su.nepom.budget.model.ObjectWithId
import su.nepom.budget.model.Uuid

interface ObservableEntity<C : ObjectWithId> {
    /*val contentProperty: ObjectProperty<C>

    var content: C
        get() = contentProperty.value
        set(value) { contentProperty.set(value) }

    val uuid: Uuid get() = content.uuid
*/
    val uuid: Uuid

    val uuidObservable: ObservableValue<Uuid>

    fun properties(): Array<Observable>
}

interface ObservableEntityBuilder<T : ObservableEntity<*>> {
    fun saveAndUpdate(session: Session, target: T)
}

interface ObservableEntityFactory<T : ObservableEntity<*>, B : ObservableEntityBuilder<T>> {
    fun createNew(): T

    fun builder(entity: T): B
}

class SimpleObjectWithIdProperty<T : ObjectWithId>(bean: Any, name: String, initialValue: T) :
    SimpleObjectProperty<T>(bean, name, initialValue) {

    override fun set(newValue: T) {
        require(value.uuid == newValue.uuid) { "Cannot change uuid" }
        super.set(newValue)
    }
}