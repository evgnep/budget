package su.nepom.budget.desktop.ui.subaccount

import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import su.nepom.budget.desktop.util.fx.Controller

@Module
interface SubaccountsModule {
    @Binds
    @IntoMap
    @ClassKey(SubaccountsController::class)
    fun subaccountsController(v: SubaccountsController): Controller

    @Binds
    @IntoMap
    @ClassKey(SubaccountDetailController::class)
    fun subaccountDetailController(v: SubaccountDetailController): Controller
}
