package su.nepom.budget.db.dao

import su.nepom.budget.event.AccountContent

interface AccountDao: CrudDao<AccountContent> {
    /**
     * All distinct tags used by accounts.
     */
    fun getAllTags(): Set<String>
}
