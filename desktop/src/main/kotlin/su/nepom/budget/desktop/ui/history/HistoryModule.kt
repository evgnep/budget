package su.nepom.budget.desktop.ui.history

import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import su.nepom.budget.desktop.util.fx.Controller

@Module
interface HistoryModule {
    @Binds
    @IntoMap
    @ClassKey(HistoryController::class)
    fun historyController(v: HistoryController): Controller
}
