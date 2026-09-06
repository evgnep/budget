package su.nepom.budget.db.sqlite

import kotlinx.datetime.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import su.nepom.budget.Global
import su.nepom.budget.db.dao.EventDao
import su.nepom.budget.event.ActualEvent
import su.nepom.budget.event.ActualVersionContent
import su.nepom.budget.event.CurrencyContent
import su.nepom.budget.event.Event
import su.nepom.budget.event.EventType
import su.nepom.budget.model.EventCoords
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.Place
import java.util.function.Consumer
import kotlin.time.Duration.Companion.seconds

internal class SqliteEventDaoTest : AbstractDbTest() {

    private fun saveEvent(
        place: Place = Global.currentPlace,
        no: Int = 0,
        created: Instant = TIME_MOMENT,
        creator: String = Global.currentUser,
        type: EventType = EventType.NEW,
        content: ActualVersionContent = createCurrency("rub", "rub"),
    ) {
        eventDao.save(
            Event(
                coords = EventCoords(place, no),
                created = created,
                creator = creator,
                type = type,
                basedOn = emptyList(),
                content = content,
            )
        )
        session.commit()
    }

    private fun otherPlace() = Place("other")

    @Test
    fun `filters by place`() {
        saveEvent(content = createCurrency("aaa", "aaa"))
        saveEvent(place = otherPlace(), no = 1, content = createCurrency("bbb", "bbb"))
        // when
        val result = eventDao.getByQuery(EventDao.Query(filter = EventDao.Filter(place = otherPlace())))
        // then
        assertThat(result).singleElement()
            .satisfies(Consumer { assertThat(it.coords.source).isEqualTo(otherPlace()) })
    }

    @Test
    fun `filters by no range`() {
        saveEvent(content = createCurrency("aaa", "aaa")) // local, gets no = 1
        saveEvent(content = createCurrency("bbb", "bbb")) // local, gets no = 2
        saveEvent(place = otherPlace(), no = 5, content = createCurrency("ccc", "ccc"))
        // when
        val result = eventDao.getByQuery(EventDao.Query(filter = EventDao.Filter(no = 1..2)))
        // then
        assertThat(result).hasSize(2)
            .allSatisfy { assertThat(it.coords.no).isBetween(1, 2) }
    }

    @Test
    fun `filters by created range`() {
        saveEvent(created = TIME_MOMENT, content = createCurrency("aaa", "aaa"))
        saveEvent(created = TIME_MOMENT + 10.seconds, content = createCurrency("bbb", "bbb"))
        saveEvent(created = TIME_MOMENT + 20.seconds, content = createCurrency("ccc", "ccc"))
        // when
        val result = eventDao.getByQuery(
            EventDao.Query(filter = EventDao.Filter(created = TIME_MOMENT..(TIME_MOMENT + 15.seconds)))
        )
        // then
        assertThat(result).extracting("content").extracting("officialCode")
            .containsExactlyInAnyOrder("aaa", "bbb")
    }

    @Test
    fun `filters by creator`() {
        saveEvent(creator = "user1", content = createCurrency("aaa", "aaa"))
        saveEvent(creator = "user2", content = createCurrency("bbb", "bbb"))
        // when
        val result = eventDao.getByQuery(EventDao.Query(filter = EventDao.Filter(creator = "user2")))
        // then
        assertThat(result).singleElement().satisfies(Consumer { assertThat(it.creator).isEqualTo("user2") })
    }

    @Test
    fun `filters by type`() {
        saveEvent(type = EventType.NEW, content = createCurrency("aaa", "aaa"))
        saveEvent(type = EventType.UPDATE, content = createCurrency("bbb", "bbb"))
        // when
        val result = eventDao.getByQuery(EventDao.Query(filter = EventDao.Filter(type = EventType.UPDATE)))
        // then
        assertThat(result).singleElement().satisfies(Consumer { assertThat(it.type).isEqualTo(EventType.UPDATE) })
    }

    @Test
    fun `filters by contentPart`() {
        saveEvent(content = createCurrency("aaa", "aaa"))
        saveEvent(content = createCurrency("bbb", "bbb"))
        // when
        val result = eventDao.getByQuery(EventDao.Query(filter = EventDao.Filter(contentPart = "bbb")))
        // then
        assertThat(result).singleElement()
            .satisfies(Consumer { assertThat((it.content as CurrencyContent).officialCode).isEqualTo("bbb") })
    }

    @Test
    fun `filters by objectKind`() {
        val currency = createCurrency("aaa", "aaa")
        saveEvent(content = currency)
        saveEvent(content = createAccount("acc", currency))
        // when
        val result = eventDao.getByQuery(EventDao.Query(filter = EventDao.Filter(objectKind = ObjectKind.ACCOUNT)))
        // then
        assertThat(result).singleElement()
            .satisfies(Consumer { assertThat(it.content.objectKind).isEqualTo(ObjectKind.ACCOUNT) })
    }

    @Test
    fun `sorts and pages by created date`() {
        saveEvent(created = TIME_MOMENT, content = createCurrency("aaa", "aaa"))
        saveEvent(created = TIME_MOMENT + 10.seconds, content = createCurrency("bbb", "bbb"))
        saveEvent(created = TIME_MOMENT + 20.seconds, content = createCurrency("ccc", "ccc"))
        // when
        val ascending = eventDao.getByQuery(EventDao.Query(sortByDateAsc = true))
        val descending = eventDao.getByQuery(EventDao.Query(sortByDateAsc = false))
        val secondPage = eventDao.getByQuery(EventDao.Query(offset = 1, limit = 1, sortByDateAsc = true))
        // then
        fun codesOf(events: List<ActualEvent>) =
            events.map { (it.content as CurrencyContent).officialCode }
        assertThat(codesOf(ascending)).containsExactly("aaa", "bbb", "ccc")
        assertThat(codesOf(descending)).containsExactly("ccc", "bbb", "aaa")
        assertThat(codesOf(secondPage)).containsExactly("bbb")
    }
}
