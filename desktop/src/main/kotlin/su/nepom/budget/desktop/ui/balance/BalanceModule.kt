package su.nepom.budget.desktop.ui.balance

import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import su.nepom.budget.desktop.util.fx.Controller

@Module
interface BalanceModule {
    @Binds
    @IntoMap
    @ClassKey(BalanceController::class)
    fun balanceController(v: BalanceController): Controller
}
