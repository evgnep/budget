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
    override val content: TransactionContent get() = contentProperty.get()

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
        private val id = source.content.id
        // keep original instant so pure re-save does not drop time-of-day
        private val originalDate: Instant = source.content.date
        var date: LocalDate = originalDate.toLocalDate()
        var description: String = source.content.description
        var flag: Boolean = source.content.flag
        var deleted: Boolean = source.content.deleted
        var items: List<TransactionContentItem> = source.content.items

        override fun buildContent(): TransactionContent {
            val dateInstant = if (date == originalDate.toLocalDate()) originalDate else date.toStartOfDayInstant()
            return TransactionContent(
                id = id,
                date = dateInstant,
                description = description,
                items = items,
                flag = flag,
                deleted = deleted,
            )
        }

        override fun saveAndUpdate(session: Session, target: TransactionObservable) {
            val content = buildContent()
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
