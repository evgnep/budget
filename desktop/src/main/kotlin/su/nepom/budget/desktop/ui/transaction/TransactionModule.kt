package su.nepom.budget.desktop.ui.transaction

import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import su.nepom.budget.desktop.util.fx.Controller

@Module
interface TransactionModule {
    @Binds
    @IntoMap
    @ClassKey(TransactionController::class)
    fun transactionController(v: TransactionController): Controller

    @Binds
    @IntoMap
    @ClassKey(TransactionDetailController::class)
    fun transactionDetailController(v: TransactionDetailController): Controller

    @Binds
    @IntoMap
    @ClassKey(AccountPickerController::class)
    fun accountPickerController(v: AccountPickerController): Controller
}
