package su.nepom.budget.desktop.ui.sync

import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import su.nepom.budget.desktop.util.fx.Controller

@Module
interface SyncModule {
    @Binds
    @IntoMap
    @ClassKey(SyncController::class)
    fun syncController(v: SyncController): Controller
}
