package su.nepom.budget.desktop.ui

import dagger.Module
import su.nepom.budget.desktop.ui.account.AccountModule
import su.nepom.budget.desktop.ui.configuration.ConfigurationModule
import su.nepom.budget.desktop.ui.currency.CurrencyModule

@Module(
    includes = [
        ConfigurationModule::class,
        CurrencyModule::class,
        AccountModule::class,
    ]
)
interface UiModule