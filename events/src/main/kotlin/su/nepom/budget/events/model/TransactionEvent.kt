package su.nepom.budget.events.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import su.nepom.budget.model.AccountId
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.Uuid

@Serializable
@SerialName("TransactionContentV1")
data class TransactionContentV1(
    override val id: Uuid,
    val date: Instant,
    val description: String = "",
    val items: List<Item>,
    val flag: Boolean = false,
    val deleted: Boolean = false,
): StorableContent, ActualVersionContent {
    override val objectKind: ObjectKind get() = ObjectKind.TRANSACTION

    @Serializable
    data class Item(
        val account: AccountId,
        val money: Long,
        val description: String = "",
        val flag: Boolean = false,
    )
}

/**
 * Actual version of Transaction content
 */
typealias TransactionContent = TransactionContentV1

typealias TransactionContentItem = TransactionContentV1.Item
