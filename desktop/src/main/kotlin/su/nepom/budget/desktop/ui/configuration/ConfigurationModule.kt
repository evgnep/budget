package su.nepom.budget.desktop.ui.configuration

import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import su.nepom.budget.desktop.util.fx.Controller

@Module
interface ConfigurationModule {
    @Binds
    @IntoMap
    @ClassKey(ConfigurationController::class)
    fun configurationController(v: ConfigurationController): Controller

    @Binds
    @IntoMap
    @ClassKey(EventsInfoController::class)
    fun eventsInfoController(v: EventsInfoController): Controller
}