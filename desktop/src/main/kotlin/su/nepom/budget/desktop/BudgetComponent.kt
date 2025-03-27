package su.nepom.budget.desktop

import dagger.BindsInstance
import dagger.Component
import jakarta.inject.Singleton
import javafx.stage.Stage
import su.nepom.budget.desktop.service.DbService
import su.nepom.budget.desktop.service.EventStoreService
import su.nepom.budget.desktop.service.SettingsService
import su.nepom.budget.desktop.ui.configuration.ConfigurationDialog
import su.nepom.budget.desktop.ui.configuration.ConfigurationModule
import su.nepom.budget.desktop.util.UtilModule

@Component(
    modules = [
        ConfigurationModule::class,
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

    @Component.Builder
    interface Builder {
        @BindsInstance
        fun mainStage(stage: Stage): Builder
        fun build(): BudgetComponent
    }
}