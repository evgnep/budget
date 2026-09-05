package su.nepom.budget.desktop.ui

import dagger.Module
import su.nepom.budget.desktop.ui.account.AccountModule
import su.nepom.budget.desktop.ui.balance.BalanceModule
import su.nepom.budget.desktop.ui.configuration.ConfigurationModule
import su.nepom.budget.desktop.ui.conflict.ConflictModule
import su.nepom.budget.desktop.ui.currency.CurrencyModule
import su.nepom.budget.desktop.ui.history.HistoryModule
import su.nepom.budget.desktop.ui.main.MainAccountsModule
import su.nepom.budget.desktop.ui.money.MoneyModule
import su.nepom.budget.desktop.ui.subaccount.SubaccountsModule
import su.nepom.budget.desktop.ui.sync.SyncModule
import su.nepom.budget.desktop.ui.transaction.TransactionModule

@Module(
    includes = [
        ConfigurationModule::class,
        CurrencyModule::class,
        AccountModule::class,
        MainAccountsModule::class,
        ConflictModule::class,
        BalanceModule::class,
        MoneyModule::class,
        TransactionModule::class,
        HistoryModule::class,
        SyncModule::class,
        SubaccountsModule::class,
    ]
)
interface UiModule