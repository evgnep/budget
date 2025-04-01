package su.nepom.budget.event

import kotlinx.serialization.Serializable
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.ObjectWithId

/**
 * This interface represents content that can be stored.
 */
@Serializable
sealed interface StorableContent: ObjectWithId {
    val objectKind: ObjectKind
}

/**
 * Represents the content of the actual version.
 */
@Serializable
sealed interface ActualVersionContent : StorableContent