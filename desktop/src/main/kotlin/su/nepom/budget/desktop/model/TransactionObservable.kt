package su.nepom.budget.desktop.model

import javafx.beans.Observable
import kotlinx.datetime.Instant
import su.nepom.budget.db.Session
import su.nepom.budget.desktop.util.db.ObservableEntity
import su.nepom.budget.desktop.util.db.ObservableEntityBuilder
import su.nepom.budget.desktop.util.db.ObservableEntityFactory
import su.nepom.budget.desktop.util.db.SimpleObjectWithIdProperty
import su.nepom.budget.desktop.util.toLocalDate
import su.nepom.budget.desktop.util.toStartOfDayInstant
import su.nepom.budget.event.TransactionContent
import su.nepom.budget.event.TransactionContentItem
import su.nepom.budget.model.Uuid
import su.nepom.budget.utils.SecondsClock
import java.time.LocalDate

class TransactionObservable(
    contentValue: TransactionContent,
) : ObservableEntity<TransactionContent> {
    val contentProperty = SimpleObjectWithIdProperty(this, "content", contentValue)
    val content: TransactionContent get() = contentProperty.get()

    override val uuid: Uuid get() = content.uuid
    override val uuidObservable = contentProperty.map { it.uuid }
    val date = contentProperty.map { it.date }
    val description = contentProperty.map { it.description }
    val flag = contentProperty.map { it.flag }
    val deleted = contentProperty.map { it.deleted }
    val items = contentProperty.map { it.items }

    override fun properties(): Array<Observable> =
        arrayOf(uuidObservable, date, description, flag, deleted, items)

    class Builder(source: TransactionObservable) : ObservableEntityBuilder<TransactionObservable> {
        // TODO keep original instant so pure re-save does not drop time-of-day
        private val originalDate: Instant = source.content.date
        var date: LocalDate = originalDate.toLocalDate()
        var description: String = source.content.description
        var flag: Boolean = source.content.flag
        var deleted: Boolean = source.content.deleted
        var items: List<TransactionContentItem> = source.content.items

        override fun saveAndUpdate(session: Session, target: TransactionObservable) {
            val dateInstant = if (date == originalDate.toLocalDate()) originalDate else date.toStartOfDayInstant()
            val content = TransactionContent(
                id = target.content.id,
                date = dateInstant,
                description = description,
                items = items,
                flag = flag,
                deleted = deleted,
            )
            session.transactionDao.save(content)
            target.contentProperty.set(content)
        }
    }

    class Factory : ObservableEntityFactory<TransactionObservable, Builder> {
        override fun createNew(): TransactionObservable =
            TransactionObservable(
                TransactionContent(
                    id = Uuid.generate(),
                    date = SecondsClock.now(),
                    description = "",
                    items = emptyList(),
                    flag = false,
                    deleted = false,
                )
            )

        override fun builder(entity: TransactionObservable) = Builder(entity)
    }
}
