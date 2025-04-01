package su.nepom.budget.db.model

import su.nepom.budget.model.AccountId
import su.nepom.budget.model.Id
import su.nepom.budget.model.ObjectWithId
import su.nepom.budget.model.RawMoney

data class AccountRest(val accountId: AccountId, val rest: RawMoney): ObjectWithId {
    override val id: Id get() = accountId
}
