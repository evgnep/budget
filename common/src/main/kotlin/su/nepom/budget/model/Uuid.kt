package su.nepom.budget.model

import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi

@JvmInline
@Serializable
value class Uuid(val id: String): Id {
    override val uuid: Uuid get() = this
    override val readable: HumanReadableId? get() = null

    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun generate() = Uuid(kotlin.uuid.Uuid.random().toString())

        val NULL = Uuid("")
    }
}