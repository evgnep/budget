package su.nepom.budget.desktop.util.db

import javafx.beans.Observable
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ObservableValue
import su.nepom.budget.db.Session
import su.nepom.budget.model.ContentHolder
import su.nepom.budget.model.ObjectWithId
import su.nepom.budget.model.Uuid

interface ObservableEntity<C : ObjectWithId> : ContentHolder<C> {
  val uuid: Uuid

  override val content: C

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