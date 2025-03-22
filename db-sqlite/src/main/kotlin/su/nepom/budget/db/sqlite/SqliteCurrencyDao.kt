package su.nepom.budget.db.sqlite

import org.ktorm.dsl.eq
import su.nepom.budget.db.dao.CurrencyDao
import su.nepom.budget.db.sqlite.impl.AbstractSqliteCrudDao
import su.nepom.budget.db.sqlite.mapping.Currencies
import su.nepom.budget.db.sqlite.mapping.CurrencyEntity
import su.nepom.budget.db.sqlite.mapping.currencies
import su.nepom.budget.db.sqlite.mapping.toEntity
import su.nepom.budget.event.CurrencyContent
import su.nepom.budget.model.Uuid

internal class SqliteCurrencyDao(
    session: SqliteSession
) : AbstractSqliteCrudDao<CurrencyContent, CurrencyEntity, Currencies>(
    session,
    { currencies },
    { toCurrencyContent() },
    { toEntity() }),
    CurrencyDao {

    override fun idPredicate(table: Currencies, id: Uuid) = table.uuid eq id.id

    override fun validateNew(new: CurrencyContent) {
        require(new.digitsAfterPoint >= 0) { "Digits after point must be non-negative" }
    }

    override fun validateUpdate(old: CurrencyContent, new: CurrencyContent) {
        require(new.digitsAfterPoint == old.digitsAfterPoint) { "You can't change digits after point" }
    }
}