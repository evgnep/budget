package su.nepom.budget.db.sqlite

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import su.nepom.budget.Global
import su.nepom.budget.event.CurrencyContent
import su.nepom.budget.event.EventType
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.no
import java.util.function.Consumer

internal class SqlitePropertyDaoTest : AbstractDbTest() {
    val dao by lazy { session.propertyDao }

    @Test
    fun createNew() {
        assertThat(dao.get("x")).isNull()
        dao.save("x", "42")
        session.commit()
        assertThat(dao.get("x")).isEqualTo("42")
    }

    @Test
    fun update() {
        assertThat(dao.get("x")).isNull()
        dao.save("x", "42")
        session.commit()
        dao.save("x", "43")
        session.commit()
        assertThat(dao.get("x")).isEqualTo("43")
    }

    @Test
    fun delete() {
        assertThat(dao.get("x")).isNull()
        dao.save("x", "42")
        session.commit()
        assertThat(dao.get("x")).isEqualTo("42")

        dao.delete("x")
        session.commit()
        assertThat(dao.get("x")).isNull()
    }
}
