package su.nepom.budget.event

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import su.nepom.budget.model.AccountId
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.RawMoney
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

    override val isHidden get() = deleted

    @Serializable
    data class Item(
        val account: AccountId,
        val money: RawMoney,
        val description: String = "",
        val flag: Boolean = false,
        // See docs/budget.md
        val reservedUntil: LocalDate? = null,
    )
}

/**
 * Actual version of Transaction content
 */
typealias TransactionContent = TransactionContentV1

typealias TransactionContentItem = TransactionContentV1.Item

data class TransactionContextItemAndTransaction(
    val item: TransactionContentItem,
    val itemNoInTransaction: Int,
    // true if this is the first row of its transaction in the list this item came from
    val isFirstInGroup: Boolean,
    val transaction: TransactionContent,
)