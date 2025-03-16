package su.nepom.budget.model

import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class Place(val code: String) {
    override fun toString() = code

    companion object {
        val NULL = Place("")
    }
}