package su.nepom.budget.desktop.model

import javafx.beans.Observable
import su.nepom.budget.db.Db
import su.nepom.budget.db.Session
import su.nepom.budget.desktop.util.db.ObservableEntity
import su.nepom.budget.desktop.util.db.ObservableEntityBuilder
import su.nepom.budget.desktop.util.db.ObservableEntityFactory
import su.nepom.budget.desktop.util.db.SimpleObjectWithIdProperty
import su.nepom.budget.event.CurrencyContent
import su.nepom.budget.model.CurrencyId
import su.nepom.budget.model.Uuid

class CurrencyObservable(
    contentValue: CurrencyContent
) : ObservableEntity<CurrencyContent> {
    override val contentProperty = SimpleObjectWithIdProperty(this, "content", contentValue)

    override val uuidObservable = contentProperty.map { it.uuid }
    val name = contentProperty.map { it.name }
    val digitsAfterPoint = contentProperty.map { it.digitsAfterPoint }
    val officialCode = contentProperty.map { it.officialCode }
    val hidden = contentProperty.map { it.hidden }

    override fun properties(): Array<Observable> =
        arrayOf(uuidObservable, name, digitsAfterPoint, officialCode, hidden)

    companion object : ObservableEntityFactory<CurrencyObservable, CurrencyContent, Builder> {
        override fun create(content: CurrencyContent?) =
            CurrencyObservable(content ?: CurrencyContent(CurrencyId(Uuid.generate()), "", 2, "", false))

        override val subscribeKinds get() = setOf(Db.SubscribeKind.CURRENCY)

        override fun builder(entity: CurrencyObservable?) = Builder(entity)
    }

    class Builder(source: CurrencyObservable?) : ObservableEntityBuilder<CurrencyObservable> {
        var name: String = source?.content?.name ?: ""
        var digitsAfterPoint: Int = source?.content?.digitsAfterPoint ?: 2
        var officialCode: String = source?.content?.officialCode ?: ""
        var hidden: Boolean = source?.content?.hidden ?: false

        override fun saveAndUpdate(session: Session, target: CurrencyObservable) {
            val content = CurrencyContent(target.content.id, name, digitsAfterPoint, officialCode, hidden)
            session.currencyDao.save(content)
            target.content = content
        }
    }
}
