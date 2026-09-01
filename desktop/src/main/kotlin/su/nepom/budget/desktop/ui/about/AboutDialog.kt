package su.nepom.budget.desktop.ui.about

import jakarta.inject.Inject
import jakarta.inject.Singleton
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.TextArea
import javafx.scene.layout.BorderPane
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.stage.StageStyle
import su.nepom.budget.desktop.BuildInfo
import su.nepom.budget.desktop.util.fx.setIcon

@Singleton
class AboutDialog @Inject constructor(
    private val mainStage: Stage,
) {
    private val stage by lazy { makeStage() }

    private fun makeStage(): Stage {
        val info = TextArea(BuildInfo.summary()).apply {
            isEditable = false
            isWrapText = false
        }
        val root = BorderPane(info).apply { padding = Insets(10.0) }
        return Stage().apply {
            title = "О программе"
            setIcon("app")
            initOwner(mainStage)
            initModality(Modality.NONE)
            initStyle(StageStyle.UTILITY)
            scene = Scene(root, 380.0, 150.0)
        }
    }

    fun show() {
        stage.show()
        stage.toFront()
    }
}
