package su.nepom.budget.desktop.ui.event

import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import su.nepom.budget.desktop.util.fx.Controller

@Module
interface EventModule {
    @Binds
    @IntoMap
    @ClassKey(EventsController::class)
    fun eventsController(v: EventsController): Controller
}
