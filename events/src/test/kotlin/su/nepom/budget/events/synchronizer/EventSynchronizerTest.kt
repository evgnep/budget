package su.nepom.budget.events.synchronizer

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import su.nepom.budget.Global
import su.nepom.budget.db.Db
import su.nepom.budget.db.Session
import su.nepom.budget.db.dao.CurrencyDao
import su.nepom.budget.db.dao.EventDao
import su.nepom.budget.event.ActualEvent
import su.nepom.budget.event.CurrencyContent
import su.nepom.budget.event.Event
import su.nepom.budget.event.EventType
import su.nepom.budget.events.EventStoreReader
import su.nepom.budget.events.EventsSequence
import su.nepom.budget.model.CurrencyCode
import su.nepom.budget.model.CurrencyId
import su.nepom.budget.model.EventCoords
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.Place
import su.nepom.budget.model.Uuid
import su.nepom.budget.model.no

private val PLACE_A = Place("a")
private val PLACE_B = Place("b")
private val PLACE_OUR = Place("our")
private const val CURRENT_USER = "petya"

class EventSynchronizerTest {
    private val events = mutableListOf<ActualEvent>()
    private val sequence = mockk<EventsSequence>(relaxed = true) {
        every { iterator() } answers { events.iterator() }
    }
    private val eventReader = mockk<EventStoreReader> {
        every { createEventsSequence(any()) } returns sequence
    }
    private val currencyDao = mockk<CurrencyDao>(relaxed = true) {
        every { save(any()) } answers { arg(0) }
    }
    private val eventDao = mockk<EventDao>(relaxed = true) {
        every { getLastEventForObject(any(), any()) } returns null
    }
    private val session = mockk<Session>(relaxed = true) {
        every { dao(any()) } answers { callOriginal() }
        every { currencyDao() } returns currencyDao
        every { eventDao() } returns eventDao
    }
    private val db = mockk<Db> {
        every { getSessionInBlockingMode(any()) } returns session
    }
    private val conflictResolver = mockk<ConflictResolver>()
    private val underTest = EventSynchronizer(eventReader, db, conflictResolver)

    @BeforeEach
    fun beforeEach() {
        Global.setCurrentPlace(PLACE_OUR)
        Global.setCurrentUser(CURRENT_USER)
    }

    @Test
    fun `no new events`() {
        val eventsNo =
            every { eventDao.getLastEventCoords() } returns mapOf(PLACE_A to 42, PLACE_B to 27)
        every { eventReader.getMaxEventsNo() } returns mapOf(PLACE_A to 42, PLACE_B to 27, PLACE_OUR to 10)
        // when
        val result = underTest.synchronize()
        // then
        assertThat(result.imported).isZero()
        assertThat(result.readingErrors).isEmpty()
        assertThat(result.error).isNull()
    }

    @Test
    fun `some new events, no conflicts, new object`() {
        every { eventDao.getLastEventCoords() } returns mapOf()
        every { eventReader.getMaxEventsNo() } returns mapOf(PLACE_A to 2, PLACE_B to 1)
        val ev1 = addEvent(PLACE_A no 1, "rubles", "rub")
        val ev2 = addEvent(PLACE_A no 2, "dollars", "usd")
        val ev3 = addEvent(PLACE_B no 1, ev1.uuid, "other", "rub", ev2.coords)
        // when
        val result = underTest.synchronize()
        // then
        assertThat(result.imported).isEqualTo(3)
        assertThat(result.readingErrors).isEmpty()
        assertThat(result.error).isNull()
        verify { eventDao.save(ev1) }
        verify { eventDao.save(ev2) }
        verify { eventDao.save(ev3) }
        verify { currencyDao.save(ev2.content) }
        verify { currencyDao.save(ev3.content) }
    }

    @Test
    fun `some new events, no conflicts, object already exists`() {
        every { eventDao.getLastEventCoords() } returns mapOf(PLACE_OUR to 1)
        every { eventReader.getMaxEventsNo() } returns mapOf(PLACE_A to 2, PLACE_B to 1)
        val ev0 = makeEvent(PLACE_OUR no 1, "rubles", "rub")
        val ev1 = addEvent(PLACE_A no 1, ev0.uuid, "rubles", "rub", PLACE_OUR no 1)
        val ev2 = addEvent(PLACE_A no 2, "dollars", "usd")
        val ev3 = addEvent(PLACE_B no 1, ev0.uuid, "other", "rub", ev2.coords)
        every { eventDao.getLastEventForObject(ev1.uuid, ObjectKind.CURRENCY) } returns ev0
        // when
        val result = underTest.synchronize()
        // then
        assertThat(result.imported).isEqualTo(3)
        assertThat(result.readingErrors).isEmpty()
        assertThat(result.error).isNull()
        verify(exactly = 0) { eventDao.save(ev0) }
        verify { eventDao.save(ev1) }
        verify { eventDao.save(ev2) }
        verify { eventDao.save(ev3) }
        verify { currencyDao.save(ev2.content) }
        verify { currencyDao.save(ev3.content) }
    }

    @Test
    fun `has conflict, abort`() {
        every { eventDao.getLastEventCoords() } returns mapOf()
        every { eventReader.getMaxEventsNo() } returns mapOf(PLACE_A to 1, PLACE_B to 1)
        val ev1 = addEvent(PLACE_A no 1, "rubles", "rub")
        val ev2 = addEvent(PLACE_B no 1, ev1.uuid, "dollars", "usd")
        coEvery { conflictResolver.resolve(any(), any()) } returns
                ConflictResolver.Result(ConflictResolver.Action.CANCEL, null)
        // when
        val result = underTest.synchronize()
        // then
        assertThat(result.imported).isZero()
        verify(exactly = 0) { eventDao.save(any()) }
        verify(exactly = 0) { currencyDao.save(any()) }
    }

    @Test
    fun `has conflict, resolve`() {
        every { eventDao.getLastEventCoords() } returns mapOf()
        every { eventReader.getMaxEventsNo() } returns mapOf(PLACE_A to 1, PLACE_B to 1)
        val ev1 = addEvent(PLACE_A no 1, "rubles", "rub")
        val ev2 = addEvent(PLACE_B no 1, ev1.uuid, "dollars", "usd")
        val resolved = ev1.content.copy(name = "other")
        coEvery { conflictResolver.resolve(any(), any()) } returns
                ConflictResolver.Result(ConflictResolver.Action.RESOLVE, resolved)
        // when
        val result = underTest.synchronize()
        // then
        assertThat(result.imported).isEqualTo(2)
        verify { eventDao.save(withArg {
            assertThat(it.coords.source).isEqualTo(PLACE_OUR)
            assertThat(it.content).isSameAs(resolved)
            assertThat(it.conflictResolve).containsExactlyInAnyOrder(ev1.coords, ev2.coords)
        }) }
        verify { currencyDao.save(resolved) }
    }

    private fun makeEvent(
        coords: EventCoords,
        uuid: Uuid,
        name: String,
        code: String,
        vararg basedOn: EventCoords
    ): Event<CurrencyContent> {
        val content = CurrencyContent(CurrencyId(uuid, CurrencyCode(code)), name, 2, code)
        val event = Event<CurrencyContent>(
            coords,
            Instant.fromEpochSeconds(0),
            "some",
            EventType.UPDATE,
            basedOn.toList(),
            content
        )
        return event
    }

    private fun makeEvent(
        coords: EventCoords,
        name: String,
        code: String,
        vararg basedOn: EventCoords
    ) = makeEvent(coords, Uuid.generate(), name, code, *basedOn)

    private fun addEvent(
        coords: EventCoords,
        uuid: Uuid,
        name: String,
        code: String,
        vararg basedOn: EventCoords
    ): Event<CurrencyContent> = makeEvent(coords, uuid, name, code, *basedOn).also { events.add(it) }

    private fun addEvent(coords: EventCoords, name: String, code: String, vararg basedOn: EventCoords) =
        addEvent(coords, Uuid.generate(), name, code, *basedOn)
}