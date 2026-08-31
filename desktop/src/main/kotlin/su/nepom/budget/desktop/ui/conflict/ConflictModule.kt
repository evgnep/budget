package su.nepom.budget.desktop.ui.conflict

import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import su.nepom.budget.desktop.util.fx.Controller
import su.nepom.budget.events.synchronizer.ConflictResolver

@Module
interface ConflictModule {
    @Binds
    @IntoMap
    @ClassKey(ConflictController::class)
    fun conflictController(v: ConflictController): Controller

    @Binds
    fun conflictResolver(v: DesktopConflictResolver): ConflictResolver
}
