package su.nepom.budget.model

import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class CurrencyCode(val code: String): Id {
    override val id: String get() = code
}