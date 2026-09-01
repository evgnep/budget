package su.nepom.budget.event

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import su.nepom.budget.db.Session
import su.nepom.budget.model.AccountId
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.RawMoney
import su.nepom.budget.model.Uuid

@Serializable
@SerialName("SubaccountContentV1")
data class SubaccountContentV1(
  override val id: Uuid,
  val accountId: AccountId,
  val rest: RawMoney,
  override val isHidden: Boolean = false,
): StorableContent, ActualVersionContent {
  override val objectKind: ObjectKind get() = ObjectKind.SUBACCOUNT
}

/**
 * Actual version of Sabaccount content
 */
typealias SubaccountContent = SubaccountContentV1

fun SubaccountContent.validate(session: Session) {
  requireNotNull(session.accountDao.getById(accountId.uuid)) {
    "Account ${accountId.uuid} not found"
  }
}