package su.nepom.budget.model

import kotlinx.serialization.Serializable

/**
 * Descriptive, but may changes and be invalid
 */
interface HumanReadableId {
    val id: String
}

interface Id {
    val uuid: Uuid
    val readable: HumanReadableId?
}

@Serializable
data class UuidAndReadable<T : HumanReadableId>(override val uuid: Uuid, override val readable: T) : Id {
    constructor(readable: T) : this(Uuid.generate(), readable)

    override fun toString(): String = if (readable.id.isEmpty()) uuid.id else uuid.id + ": " + readable.id

    override fun equals(other: Any?): Boolean = when {
        this === other -> true
        other is UuidAndReadable<*> -> uuid == other.uuid
        else -> false
    }

    override fun hashCode(): Int = uuid.hashCode()
}

typealias CurrencyId = UuidAndReadable<CurrencyCode>

fun CurrencyId(uuid: Uuid) = CurrencyId(uuid, CurrencyCode.NULL)

typealias AccountId = UuidAndReadable<AccountCode>

fun AccountId(uuid: Uuid) = AccountId(uuid, AccountCode.NULL)

fun Id.uuidCode(): String = uuid.id
