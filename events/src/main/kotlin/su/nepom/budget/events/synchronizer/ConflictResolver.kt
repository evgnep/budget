package su.nepom.budget.events.synchronizer

import su.nepom.budget.event.ActualEvent
import su.nepom.budget.event.ActualVersionContent
import su.nepom.budget.model.ObjectKind

interface ConflictResolver {
    suspend fun resolve(kind: ObjectKind, events: List<ActualEvent>): Result

    enum class Action {
        RESOLVE,
        // SKIP, - may be in the future
        CANCEL
    }

    data class Result(val action: Action, val content: ActualVersionContent?)
}