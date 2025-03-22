package su.nepom.budget.event

import kotlinx.serialization.Serializable
import su.nepom.budget.model.Id
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.Uuid

/**
 * This interface represents content that can be stored.
 */
@Serializable
sealed interface StorableContent {
    val objectKind: ObjectKind
    val id: Id
    val uuid: Uuid get() = id.uuid
}

/**
 * Represents the content of the actual version.
 */
@Serializable
sealed interface ActualVersionContent : StorableContent