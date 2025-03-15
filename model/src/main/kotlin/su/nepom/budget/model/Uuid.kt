package su.nepom.budget.model

import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi

@JvmInline
@Serializable
value class Uuid(override val id: String): Id {
    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun generate() = Uuid(kotlin.uuid.Uuid.random().toString())

        val NULL = Uuid("")
    }
}