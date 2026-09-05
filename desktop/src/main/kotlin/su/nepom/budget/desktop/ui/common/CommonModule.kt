package su.nepom.budget.desktop.ui.common

import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import su.nepom.budget.desktop.util.fx.Controller

@Module
interface CommonModule {
    @Binds
    @IntoMap
    @ClassKey(CsvExportController::class)
    fun csvExportController(v: CsvExportController): Controller
}
