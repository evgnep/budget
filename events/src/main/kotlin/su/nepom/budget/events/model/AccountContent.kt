package su.nepom.budget.events.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import su.nepom.budget.model.AccountCode
import su.nepom.budget.model.AccountKind
import su.nepom.budget.model.CurrencyCode
import su.nepom.budget.model.ObjectKind

@Serializable
@SerialName("AccountContentV1")
data class AccountContentV1(
    val code: AccountCode,
    val name: String,
    val description: String,
    val currency: CurrencyCode,
    val kind: AccountKind,
    val tags: Set<String>,
    val orderNo: Int,
    val hidden: Boolean = false,
): StorableContent, ActualVersionContent {
    override val objectKind: ObjectKind get() = ObjectKind.ACCOUNT
}

/**
 * Actual version of Account content
 */
typealias AccountContent = AccountContentV1