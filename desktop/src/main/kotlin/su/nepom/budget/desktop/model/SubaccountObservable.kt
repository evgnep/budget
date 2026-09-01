package su.nepom.budget.desktop.model

import javafx.beans.Observable
import su.nepom.budget.db.Session
import su.nepom.budget.desktop.util.db.ObservableEntity
import su.nepom.budget.desktop.util.db.ObservableEntityBuilder
import su.nepom.budget.desktop.util.db.ObservableEntityFactory
import su.nepom.budget.desktop.util.db.SimpleObjectWithIdProperty
import su.nepom.budget.event.SubaccountContent
import su.nepom.budget.model.AccountId
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.RawMoney
import su.nepom.budget.model.Uuid

class SubaccountObservable(
  contentValue: SubaccountContent
) : ObservableEntity<SubaccountContent> {
  val contentProperty = SimpleObjectWithIdProperty(this, "content", contentValue)
  override val content: SubaccountContent get() = contentProperty.get()

  override val uuid: Uuid get() = content.uuid
  override val uuidObservable = contentProperty.map { it.uuid }
  val name = contentProperty.map { it.name }
  val accountId = contentProperty.map { it.accountId }
  val rest = contentProperty.map { it.rest }
  val hidden = contentProperty.map { it.isHidden }

  override fun properties(): Array<Observable> =
    arrayOf(uuidObservable, name, accountId, rest, hidden)

  class Builder(source: SubaccountObservable) : ObservableEntityBuilder<SubaccountObservable> {
    private val id = source.content.uuid
    private val accountId = source.content.accountId
    var name: String = source.content.name
    var rest: RawMoney = source.content.rest
    var hidden: Boolean = source.content.isHidden

    override fun buildContent() = SubaccountContent(id, accountId, name, rest, hidden)

    override fun saveAndUpdate(session: Session, target: SubaccountObservable) {
      val content = buildContent()
      session.simpleObjectDao<SubaccountContent>(ObjectKind.SUBACCOUNT).save(content)
      target.contentProperty.set(content)
    }
  }

  // accountId is set by the owning screen once it knows which account the "new" button is for
  class Factory : ObservableEntityFactory<SubaccountObservable, Builder> {
    var accountId: AccountId = AccountId(Uuid.NULL)

    override fun createNew(): SubaccountObservable =
      SubaccountObservable(SubaccountContent(Uuid.generate(), accountId, "", RawMoney.ZERO, false))

    override fun builder(entity: SubaccountObservable) = Builder(entity)
  }
}
