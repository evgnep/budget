package su.nepom.budget.desktop.model

import javafx.beans.Observable
import javafx.beans.binding.Bindings
import javafx.beans.property.SimpleObjectProperty
import su.nepom.budget.db.Session
import su.nepom.budget.desktop.util.db.ObservableEntitiesList
import su.nepom.budget.desktop.util.db.ObservableEntity
import su.nepom.budget.desktop.util.db.ObservableEntityBuilder
import su.nepom.budget.desktop.util.db.ObservableEntityFactory
import su.nepom.budget.desktop.util.db.SimpleObjectWithIdProperty
import su.nepom.budget.desktop.util.toBigDecimal
import su.nepom.budget.event.AccountContent
import su.nepom.budget.model.AccountId
import su.nepom.budget.model.AccountKind
import su.nepom.budget.model.CurrencyId
import su.nepom.budget.model.RawMoney
import su.nepom.budget.model.Uuid

class AccountObservable(
  contentValue: AccountContent,
  rest: RawMoney,
  private val currencies: ObservableEntitiesList<CurrencyObservable>,
) : ObservableEntity<AccountContent> {
  val contentProperty = SimpleObjectWithIdProperty(this, "content", contentValue)
  override val content: AccountContent get() = contentProperty.get()
  val restProperty = SimpleObjectProperty<RawMoney>(this, "rest", rest)

  override val uuid: Uuid get() = content.uuid

  override val uuidObservable = contentProperty.map { it.uuid }
  val name = contentProperty.map { it.name }
  val description = contentProperty.map { it.description }
  val currency = Bindings.createObjectBinding({ currencies[content.currency.uuid] }, contentProperty, currencies)
  val currencyName = currency.map { it?.content?.name ?: "-" }
  val kind = contentProperty.map { it.kind }
  val tags = contentProperty.map { it.tags }
  val orderNo = contentProperty.map { it.orderNo }
  val hidden = contentProperty.map { it.hidden }
  val rest = Bindings.createObjectBinding(
    { restProperty.get().toBigDecimal(currency.get()) },
    restProperty, currency
  )

  override fun properties(): Array<Observable> =
    arrayOf(uuidObservable, name, description, currency, currencyName, kind, tags, orderNo, hidden, rest)

  class Builder(source: AccountObservable) : ObservableEntityBuilder<AccountObservable> {
    var name: String = source.content.name
    var description: String = source.content.description
    var currency: CurrencyId = source.content.currency
    var kind: AccountKind = source.content.kind
    var tags: Set<String> = source.content.tags
    var orderNo: Int = source.content.orderNo
    var hidden: Boolean = source.content.hidden

    override fun saveAndUpdate(session: Session, target: AccountObservable) {
      val content = AccountContent(target.content.id, name, description, currency, kind, tags, orderNo, hidden)
      session.accountDao.save(content)
      target.contentProperty.set(content)
    }
  }

  class Factory(
    private val currencies: ObservableEntitiesList<CurrencyObservable>,
  ) : ObservableEntityFactory<AccountObservable, Builder> {
    override fun createNew(): AccountObservable =
      AccountObservable(
        AccountContent(
          AccountId(Uuid.generate()),
          "",
          "",
          CurrencyId(Uuid.NULL),
          AccountKind.MONEY,
          setOf(),
          0,
          false
        ),
        RawMoney.ZERO,
        currencies
      )

    override fun builder(entity: AccountObservable) = Builder(entity)
  }
}