package su.nepom.budget.db.sqlite

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Index
import org.junit.jupiter.api.Test
import su.nepom.budget.Global
import su.nepom.budget.event.EventType
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.no
import java.util.function.Consumer

internal class SqliteCurrencyDaoTest : AbstractDbTest() {
    private val dao by lazy { session.currencyDao }

    @Test
    fun createNew() {
        val currency = createCurrency("rub", "rubles")
        dao.save(currency)
        session.commit()
        // then
        assertThat(dao.getAll()).containsExactlyInAnyOrder(currency)
        assertThat(dao.count()).isEqualTo(1)
        assertThat(eventDao.getLastEventCoords()).containsExactlyInAnyOrderEntriesOf(mapOf(Global.currentPlace to 1))
        assertThat(eventDao.getEventsForSourceFrom(Global.currentPlace, 1))
            .singleElement()
            .satisfies(Consumer {
                assertThat(it.coords).isEqualTo(Global.currentPlace no 1)
                assertThat(it.creator).isEqualTo(Global.currentUser)
                assertThat(it.type).isEqualTo(EventType.NEW)
                assertThat(it.basedOn).isEmpty()
                assertThat(it.content).isEqualTo(currency)
            })
        assertThat(eventDao.getLastEventForObject(currency.uuid, ObjectKind.CURRENCY))
            .satisfies(Consumer {
                requireNotNull(it)
                assertThat(it.coords).isEqualTo(Global.currentPlace no 1)
            })
    }

    @Test
    fun update() {
        val currency1 = createCurrency("rub", "rubles")
        val currency2 = currency1.copy(name = "other")
        // when
        dao.save(currency1)
        session.commit()
        //--
        dao.save(currency2)
        assertThat(eventDao.getLastEventCoords()).containsExactlyInAnyOrderEntriesOf(mapOf(Global.currentPlace to 1))
        session.commit()
        // then
        assertThat(dao.getAll()).containsExactlyInAnyOrder(currency2)
        assertThat(eventDao.getLastEventCoords()).containsExactlyInAnyOrderEntriesOf(mapOf(Global.currentPlace to 2))
        assertThat(eventDao.getEventsForSourceFrom(Global.currentPlace, 1))
            .satisfies({
                assertThat(it.coords).isEqualTo(Global.currentPlace no 1)
                assertThat(it.creator).isEqualTo(Global.currentUser)
                assertThat(it.type).isEqualTo(EventType.NEW)
                assertThat(it.basedOn).isEmpty()
                assertThat(it.content).isEqualTo(currency1)
            }, Index.atIndex(0))
            .satisfies({
                assertThat(it.coords).isEqualTo(Global.currentPlace no 2)
                assertThat(it.creator).isEqualTo(Global.currentUser)
                assertThat(it.type).isEqualTo(EventType.UPDATE)
                assertThat(it.basedOn).isEmpty()
                assertThat(it.content).isEqualTo(currency2)
            }, Index.atIndex(1))
        assertThat(eventDao.getLastEventForObject(currency1.uuid, ObjectKind.CURRENCY))
            .satisfies(Consumer {
                requireNotNull(it)
                assertThat(it.coords).isEqualTo(Global.currentPlace no 2)
            })
    }
}