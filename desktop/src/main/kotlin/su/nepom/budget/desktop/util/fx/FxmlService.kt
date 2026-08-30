package su.nepom.budget.desktop.util.fx

import jakarta.inject.Inject
import jakarta.inject.Singleton
import javafx.fxml.FXMLLoader
import javafx.stage.Stage

@Singleton
class FxmlService @Inject constructor(
    private val controllers: ControllerMap,
) {
    fun <T> load(
        relativeResourcePath: String,
        stage: Stage,
        stageOwner: StageOwner?,
        controllerSetup: (Controller) -> Unit = {}
    ): T {
        val loader = FXMLLoader(
            FxmlService::class.java.getResource("/fxml/$relativeResourcePath"),
            null,
            null
        ) { controllers[it]?.get()?.apply(controllerSetup) ?: error("No controller for $it") }
        val scene = loader.load<T>()
        val controller = loader.getController<T>()
        if (controller is StageOwnerAwareController) {
            controller.initialize(requireNotNull(stageOwner) { "$relativeResourcePath needs a StageOwner" })
        }
        if (controller is StageAwareController) {
            controller.initialize(stage)
        }
        return scene
    }
}