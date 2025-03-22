package su.nepom.budget.db.sqlite

import org.ktorm.dsl.eq
import su.nepom.budget.db.dao.AccountDao
import su.nepom.budget.db.sqlite.impl.AbstractSqliteCrudDao
import su.nepom.budget.db.sqlite.mapping.AccountEntity
import su.nepom.budget.db.sqlite.mapping.Accounts
import su.nepom.budget.db.sqlite.mapping.accounts
import su.nepom.budget.db.sqlite.mapping.toEntity
import su.nepom.budget.event.AccountContent
import su.nepom.budget.model.Uuid

internal class SqliteAccountDao(
    session: SqliteSession
) : AbstractSqliteCrudDao<AccountContent, AccountEntity, Accounts>(
    session,
    { accounts },
    { toAccountContent() },
    { toEntity() }),
    AccountDao {

    override fun idPredicate(table: Accounts, id: Uuid) = table.uuid eq id.id

    override fun validateUpdate(old: AccountContent, new: AccountContent) {
        require(new.currency.uuid == old.currency.uuid) { "You can't change currency" }
        require(new.kind == old.kind) { "You can't change kind" }
    }
}