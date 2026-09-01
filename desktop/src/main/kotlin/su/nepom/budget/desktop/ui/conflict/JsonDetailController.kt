package su.nepom.budget.desktop.ui.conflict

import jakarta.inject.Inject
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.TextArea
import kotlinx.serialization.json.Json
import su.nepom.budget.desktop.util.fx.Controller
import su.nepom.budget.event.ActualVersionContent
import java.net.URL
import java.util.*

private val prettyJson = Json { prettyPrint = true }

/**
 * Generic conflict form for "simple object" kinds (see ObjectKind.simpleObject) - content is shown
 * and edited as raw JSON, so a new simple-object kind gets a working conflict form for free.
 */
@Suppress("unused")
class JsonDetailController @Inject constructor() : Controller, Initializable {

    @FXML private lateinit var contentTextArea: TextArea
    @FXML private lateinit var okButton: Button
    @FXML private lateinit var cancelButton: Button

    override fun initialize(location: URL?, resources: ResourceBundle?) {
        contentTextArea.isEditable = false
        setButtonsVisible(false)
    }

    // show a past version, view only
    fun showReadOnly(content: ActualVersionContent) {
        contentTextArea.text = prettyJson.encodeToString<ActualVersionContent>(content)
        contentTextArea.isEditable = false
        setButtonsVisible(false)
    }

    // edit a version inside the conflict-resolution dialog: OK returns the parsed content, no DB write
    fun editForConflict(
        content: ActualVersionContent,
        onAccept: (ActualVersionContent) -> Unit,
        onCancel: () -> Unit,
    ) {
        contentTextArea.text = prettyJson.encodeToString<ActualVersionContent>(content)
        contentTextArea.isEditable = true
        setButtonsVisible(true)
        okButton.setOnAction {
            val parsed = runCatching { prettyJson.decodeFromString<ActualVersionContent>(contentTextArea.text) }
                .getOrElse {
                    showError("Некорректный JSON: ${it.message}")
                    return@setOnAction
                }
            if (parsed.objectKind != content.objectKind) {
                showError("Вид объекта менять нельзя")
                return@setOnAction
            }
            onAccept(parsed)
        }
        cancelButton.setOnAction { onCancel() }
    }

    private fun setButtonsVisible(visible: Boolean) {
        okButton.isVisible = visible
        okButton.isManaged = visible
        cancelButton.isVisible = visible
        cancelButton.isManaged = visible
    }

    private fun showError(message: String) {
        Alert(Alert.AlertType.ERROR, message).showAndWait()
    }
}
