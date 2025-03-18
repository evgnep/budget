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
data class UuidAndReadable<T: HumanReadableId>(override val uuid: Uuid, override val readable: T): Id {
    constructor(readable: T): this(Uuid.generate(), readable)

    override fun toString(): String = uuid.id + ": " + readable.id
}

typealias CurrencyId = UuidAndReadable<CurrencyCode>

typealias AccountId = UuidAndReadable<AccountCode>
