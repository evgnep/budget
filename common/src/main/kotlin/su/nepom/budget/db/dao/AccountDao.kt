package su.nepom.budget.db.dao

import su.nepom.budget.event.AccountContent
import su.nepom.budget.model.Uuid

interface AccountDao: CrudDao<AccountContent> {
    fun getAllExt(): List<AccountExtInfo>

    fun getExtById(id: Uuid): AccountExtInfo?
}

data class AccountExtInfo(
    val content: AccountContent,
    val rest: Long,
)
