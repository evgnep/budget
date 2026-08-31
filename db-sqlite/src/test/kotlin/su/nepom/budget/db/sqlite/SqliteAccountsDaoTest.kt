package su.nepom.budget.db.sqlite

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlinx.datetime.LocalDate
import su.nepom.budget.Global
import su.nepom.budget.event.AccountBudget
import su.nepom.budget.event.CurrencyContent
import su.nepom.budget.event.DailyAllowance
import su.nepom.budget.event.EventType
import su.nepom.budget.event.Reserve
import su.nepom.budget.model.AccountKind
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.RawMoney
import su.nepom.budget.model.no
import java.util.function.Consumer

internal class SqliteAccountsDaoTest : AbstractDbTest() {
    private lateinit var currency1: CurrencyContent

    private var eventNo = 0

    @BeforeEach
    fun createCurrency() {
        currency1 = createCurrency("rub", "rubles")
        session.currencyDao.save(currency1)
        session.commit()
        eventNo = session.eventDao.getLastEventCoords()[Global.currentPlace]!!
    }

    @Test
    fun createNew() {
        val account = createAccount("account", currency1)
        session.accountDao.save(account)
        session.commit()
        // then
        assertThat(session.accountDao.getAll())
            .singleElement()
            .usingRecursiveComparison()
            .ignoringFields("id.readable", "currency.readable")
            .isEqualTo(account)
        assertThat(session.accountDao.count()).isEqualTo(1)
        assertThat(eventDao.getLastEventCoords()).containsExactlyInAnyOrderEntriesOf(mapOf(Global.currentPlace to eventNo + 1))
        assertThat(eventDao.getEventsForSourceFrom(Global.currentPlace, eventNo + 1))
            .singleElement()
            .satisfies(Consumer {
                assertThat(it.coords).isEqualTo(Global.currentPlace no eventNo + 1)
                assertThat(it.creator).isEqualTo(Global.currentUser)
                assertThat(it.type).isEqualTo(EventType.NEW)
                assertThat(it.basedOn).isEmpty()
                assertThat(it.content).isEqualTo(account)
            })
        assertThat(eventDao.getLastEventForObject(account.uuid, ObjectKind.ACCOUNT))
            .satisfies(Consumer {
                requireNotNull(it)
                assertThat(it.coords).isEqualTo(Global.currentPlace no eventNo + 1)
            })
    }

    @Test
    fun getAllTags() {
        session.accountDao.save(createAccount("account1", currency1).copy(tags = setOf("food", "home")))
        session.accountDao.save(createAccount("account2", currency1).copy(tags = setOf("home", "car")))
        session.commit()
        // then
        assertThat(session.accountDao.getAllTags()).containsExactlyInAnyOrder("food", "home", "car")
    }

    @Test
    fun update() {
        val account1 = createAccount("account", currency1)
        val account2 = account1.copy(name = "other", description = "other desc", tags = setOf("<>"))
        // when
        session.accountDao.save(account1)
        session.commit()
        //--
        session.accountDao.save(account2)
        session.commit()
        // then
        assertThat(session.accountDao.getAll())
            .singleElement()
            .usingRecursiveComparison()
            .ignoringFields("id.readable", "currency.readable")
            .isEqualTo(account2)
        assertThat(eventDao.getLastEventCoords()).containsExactlyInAnyOrderEntriesOf(mapOf(Global.currentPlace to eventNo + 2))
    }

    @Test
    fun `budget is stored and read back`() {
        val account = createAccount("budget account", currency1, AccountKind.BUDGET).copy(
            budget = AccountBudget(
                replenishDay = 10,
                dailyAllowances = listOf(DailyAllowance(RawMoney(700), to = LocalDate(2026, 9, 5))),
                reserves = listOf(Reserve(RawMoney(5000), from = LocalDate(2026, 8, 1), to = LocalDate(2026, 8, 31))),
            )
        )
        session.accountDao.save(account)
        session.commit()
        // then
        assertThat(session.accountDao.getAll())
            .singleElement()
            .usingRecursiveComparison()
            .ignoringFields("id.readable", "currency.readable")
            .isEqualTo(account)
    }
}