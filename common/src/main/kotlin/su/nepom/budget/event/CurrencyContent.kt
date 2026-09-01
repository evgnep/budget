package su.nepom.budget.event

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import su.nepom.budget.model.CurrencyId
import su.nepom.budget.model.ObjectKind

@Serializable
@SerialName("CurrencyContentV1")
data class CurrencyContentV1(
    override val id: CurrencyId,
    val name: String,
    val digitsAfterPoint: Int,
    val officialCode: String,
    val hidden: Boolean = false,
): StorableContent, ActualVersionContent {
    override val objectKind: ObjectKind get() = ObjectKind.CURRENCY

    override val isHidden get() = hidden
}

/**
 * Actual version of Currency content
 */
typealias CurrencyContent = CurrencyContentV1