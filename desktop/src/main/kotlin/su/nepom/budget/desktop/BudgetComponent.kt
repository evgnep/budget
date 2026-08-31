package su.nepom.budget.desktop

import dagger.BindsInstance
import dagger.Component
import jakarta.inject.Singleton
import javafx.stage.Stage
import su.nepom.budget.desktop.service.DbService
import su.nepom.budget.desktop.service.EventStoreService
import su.nepom.budget.desktop.service.SettingsService
import su.nepom.budget.desktop.ui.UiModule
import su.nepom.budget.desktop.ui.account.AccountView
import su.nepom.budget.desktop.ui.configuration.ConfigurationDialog
import su.nepom.budget.desktop.ui.conflict.DesktopConflictResolver
import su.nepom.budget.desktop.ui.currency.CurrencyView
import su.nepom.budget.desktop.ui.WindowManager
import su.nepom.budget.desktop.util.UtilModule

@Component(
    modules = [
        UiModule::class,
        UtilModule::class,
    ]
)
@Singleton
interface BudgetComponent {
    fun configurationDialog(): ConfigurationDialog

    fun mainStage(): Stage

    fun dbService(): DbService

    fun eventStoreService(): EventStoreService

    fun settingsService(): SettingsService

    fun currencyView(): CurrencyView

    fun accountView(): AccountView

    fun windowManager(): WindowManager

    fun conflictResolver(): DesktopConflictResolver

    @Component.Builder
    interface Builder {
        @BindsInstance
        fun mainStage(stage: Stage): Builder
        fun build(): BudgetComponent
    }
}