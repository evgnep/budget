package su.nepom.budget.desktop.ui.currency

import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import su.nepom.budget.desktop.util.fx.Controller

@Module
interface CurrencyModule {
    @Binds
    @IntoMap
    @ClassKey(CurrencyController::class)
    fun currencyController(v: CurrencyController): Controller
}