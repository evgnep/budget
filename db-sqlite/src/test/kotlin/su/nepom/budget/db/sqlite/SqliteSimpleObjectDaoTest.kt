package su.nepom.budget.db.sqlite

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import su.nepom.budget.Global
import su.nepom.budget.event.EventType
import su.nepom.budget.event.SubaccountContent
import su.nepom.budget.model.AccountId
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.RawMoney
import su.nepom.budget.model.Uuid
import su.nepom.budget.model.no
import java.util.function.Consumer

internal class SqliteSimpleObjectDaoTest : AbstractDbTest() {
    private val dao by lazy { session.simpleObjectDao<SubaccountContent>(ObjectKind.SUBACCOUNT) }

    private fun setupAccount(): su.nepom.budget.event.AccountContent {
        val currency = createCurrency("rub", "rubles")
        session.currencyDao.save(currency)
        val account = createAccount("cash", currency)
        session.accountDao.save(account)
        session.commit()
        return account
    }

    @Test
    fun createNew() {
        val account = setupAccount()
        val subaccount = createSubaccount(account, rest = 100)
        // when
        dao.save(subaccount)
        session.commit()
        // then
        assertThat(dao.getAll()).containsExactlyInAnyOrder(subaccount)
        assertThat(dao.count()).isEqualTo(1)
        assertThat(dao.getById(subaccount.uuid)).isEqualTo(subaccount)
        assertThat(eventDao.getLastEventForObject(subaccount.uuid, ObjectKind.SUBACCOUNT))
            .satisfies(Consumer {
                requireNotNull(it)
                assertThat(it.coords).isEqualTo(Global.currentPlace no 3)
                assertThat(it.type).isEqualTo(EventType.NEW)
                assertThat(it.content).isEqualTo(subaccount)
            })
        assertThat(receivedEvents).anySatisfy { assertThat(it.content).isEqualTo(subaccount) }
    }

    @Test
    fun update() {
        val account = setupAccount()
        val subaccount1 = createSubaccount(account, rest = 100)
        dao.save(subaccount1)
        session.commit()
        val subaccount2 = subaccount1.copy(rest = RawMoney(200))
        // when
        dao.save(subaccount2)
        session.commit()
        // then
        assertThat(dao.getAll()).containsExactlyInAnyOrder(subaccount2)
        assertThat(eventDao.getLastEventForObject(subaccount1.uuid, ObjectKind.SUBACCOUNT))
            .satisfies(Consumer {
                requireNotNull(it)
                assertThat(it.coords).isEqualTo(Global.currentPlace no 4)
                assertThat(it.type).isEqualTo(EventType.UPDATE)
            })
    }

    @Test
    fun hiddenIsExcludedFromDefaultListButFindableById() {
        val account = setupAccount()
        val subaccount = createSubaccount(account, rest = 100, hidden = true)
        // when
        dao.save(subaccount)
        session.commit()
        // then
        assertThat(dao.getAll()).isEmpty()
        assertThat(dao.getAll(withHidden = true)).containsExactly(subaccount)
        assertThat(dao.getById(subaccount.uuid)).isEqualTo(subaccount)
    }

    @Test
    fun visibleInSameTransactionBeforeCommit() {
        val account = setupAccount()
        val subaccount = createSubaccount(account, rest = 100)
        // when
        dao.save(subaccount)
        // then - not committed yet, but visible through the cache overlay
        assertThat(dao.getAll()).containsExactlyInAnyOrder(subaccount)
        assertThat(dao.getById(subaccount.uuid)).isEqualTo(subaccount)
    }

    @Test
    fun rejectsUnknownAccount() {
        assertThatThrownBy {
            dao.save(SubaccountContent(Uuid.generate(), AccountId(Uuid.generate()), "sub", RawMoney(0)))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun rejectsNonMoneyAccount() {
        val currency = createCurrency("rub", "rubles")
        session.currencyDao.save(currency)
        val account = createAccount("budget", currency, kind = su.nepom.budget.model.AccountKind.BUDGET)
        session.accountDao.save(account)
        session.commit()
        assertThatThrownBy {
            dao.save(createSubaccount(account, rest = 100))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
