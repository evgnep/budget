package su.nepom.budget.event

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import su.nepom.budget.model.AccountCode
import su.nepom.budget.model.AccountId
import su.nepom.budget.model.AccountKind
import su.nepom.budget.model.CurrencyId
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.RestMark

@Serializable
@SerialName("AccountContentV1")
data class AccountContentV1(
    override val id: AccountId,
    val name: String,
    val description: String,
    val currency: CurrencyId,
    val kind: AccountKind,
    val tags: Set<String>,
    val orderNo: Int,
    val hidden: Boolean = false,
    val budget: AccountBudget = AccountBudget.EMPTY,
    val showOnMain: Boolean = false,
    // group chain from root, e.g. ["A", "B", "C"]; empty means "no group"
    val groupPath: List<String> = emptyList(),
    val restMark: RestMark = RestMark.IF_NEGATIVE,
): StorableContent, ActualVersionContent {
    override val objectKind: ObjectKind get() = ObjectKind.ACCOUNT
}

/**
 * Actual version of Account content
 */
typealias AccountContent = AccountContentV1

fun makeAccountCode(name: String, kind: AccountKind) = AccountCode("$name ${kind.name}")