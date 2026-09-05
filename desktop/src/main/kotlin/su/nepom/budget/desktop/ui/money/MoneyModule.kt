package su.nepom.budget.desktop.ui.money

import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import su.nepom.budget.desktop.util.fx.Controller

@Module
interface MoneyModule {
    @Binds
    @IntoMap
    @ClassKey(MoneyController::class)
    fun moneyController(v: MoneyController): Controller
}
