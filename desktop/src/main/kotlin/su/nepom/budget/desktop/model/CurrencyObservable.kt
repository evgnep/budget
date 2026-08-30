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
import java.util.Currency

class CurrencyObservable(
    contentValue: CurrencyContent
) : ObservableEntity<CurrencyContent> {
    val contentProperty = SimpleObjectWithIdProperty(this, "content", contentValue)
    override val content: CurrencyContent get() = contentProperty.get()

    override val uuid: Uuid get() = contentProperty.get().uuid
    override val uuidObservable = contentProperty.map { it.uuid }
    val name = contentProperty.map { it.name }
    val digitsAfterPoint = contentProperty.map { it.digitsAfterPoint }
    val officialCode = contentProperty.map { it.officialCode }
    val hidden = contentProperty.map { it.hidden }

    override fun properties(): Array<Observable> =
        arrayOf(uuidObservable, name, digitsAfterPoint, officialCode, hidden)

    class Builder(source: CurrencyObservable) : ObservableEntityBuilder<CurrencyObservable> {
        var name: String = source.content.name
        var digitsAfterPoint: Int = source.content.digitsAfterPoint
        var officialCode: String = source.content.officialCode
        var hidden: Boolean = source.content.hidden

        override fun saveAndUpdate(session: Session, target: CurrencyObservable) {
            val content = CurrencyContent(target.content.id, name, digitsAfterPoint, officialCode, hidden)
            session.currencyDao.save(content)
            target.contentProperty.set(content)
        }
    }

    class Factory : ObservableEntityFactory<CurrencyObservable, Builder> {
        override fun createNew(): CurrencyObservable =
            CurrencyObservable(CurrencyContent(CurrencyId(Uuid.generate()), "", 2, "", false))

        override fun builder(entity: CurrencyObservable) = Builder(entity)
    }
}
