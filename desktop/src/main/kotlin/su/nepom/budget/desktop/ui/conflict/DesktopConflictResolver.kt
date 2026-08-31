package su.nepom.budget.desktop.ui.conflict

import jakarta.inject.Inject
import jakarta.inject.Singleton
import javafx.application.Platform
import javafx.stage.Stage
import kotlinx.coroutines.CompletableDeferred
import su.nepom.budget.desktop.util.fx.FxmlService
import su.nepom.budget.event.ActualEvent
import su.nepom.budget.events.synchronizer.ConflictResolver
import su.nepom.budget.model.ObjectKind

/**
 * Shows a modal form for every real merge conflict during a sync. Must not be called on the FX
 * thread - the sync loop has to run on a background thread so this can marshal to FX and wait.
 */
@Singleton
class DesktopConflictResolver @Inject constructor(
    private val fxmlService: FxmlService,
    private val mainStage: Stage,
) : ConflictResolver {

    override suspend fun resolve(kind: ObjectKind, events: List<ActualEvent>): ConflictResolver.Result {
        val done = CompletableDeferred<ConflictResolver.Result>()
        Platform.runLater {
            done.complete(ConflictResolverDialog(fxmlService, mainStage, kind, events).result)
        }
        return done.await()
    }
}
