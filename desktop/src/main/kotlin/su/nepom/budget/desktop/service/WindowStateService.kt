package su.nepom.budget.desktop.service

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Inject
import jakarta.inject.Singleton
import javafx.animation.PauseTransition
import javafx.stage.Screen
import javafx.stage.Stage
import javafx.util.Duration
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.notExists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val logger = KotlinLogging.logger {}

@Serializable
private data class WindowBounds(
    val x: Double? = null,
    val y: Double? = null,
    val width: Double? = null,
    val height: Double? = null,
    val maximized: Boolean = false,
)

@Serializable
private data class WindowSettings(
    val windows: MutableMap<String, WindowBounds> = mutableMapOf(),
    var lastExportFolder: String? = null,
)

/**
 * Keeps size and position of windows and other settings in `settings.json` next to the app.
 * When a window has no saved state, its current (default) bounds are left untouched.
 */
@Singleton
class WindowStateService @Inject constructor() {
    private val path = Path.of("./settings.json").toAbsolutePath().normalize()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val settings = load()

    private val saveDebounce = PauseTransition(Duration.millis(400.0)).apply {
        setOnFinished { save() }
    }

    private fun load(): WindowSettings {
        if (path.notExists()) return WindowSettings()
        return try {
            json.decodeFromString<WindowSettings>(path.readText())
        } catch (e: Exception) {
            logger.warn(e) { "Can't read $path, using defaults" }
            WindowSettings()
        }
    }

    private fun save() {
        try {
            path.writeText(json.encodeToString(settings))
        } catch (e: Exception) {
            logger.error(e) { "Can't write $path" }
        }
    }

    /**
     * Apply the saved bounds for [key] to [stage] and start tracking its changes.
     * Call before `stage.show()`.
     */
    fun bind(stage: Stage, key: String) {
        settings.windows[key]?.let { b ->
            if (b.width != null && b.width > 0 && b.height != null && b.height > 0) {
                stage.width = b.width
                stage.height = b.height
            }
            if (b.x != null && b.y != null && isOnScreen(b.x, b.y, b.width ?: 1.0, b.height ?: 1.0)) {
                stage.x = b.x
                stage.y = b.y
            }
            stage.isMaximized = b.maximized
        }

        val listener = javafx.beans.value.ChangeListener<Any> { _, _, _ -> store(stage, key) }
        stage.widthProperty().addListener(listener)
        stage.heightProperty().addListener(listener)
        stage.xProperty().addListener(listener)
        stage.yProperty().addListener(listener)
        stage.maximizedProperty().addListener(listener)
    }

    private fun store(stage: Stage, key: String) {
        val bounds = if (stage.isMaximized) {
            (settings.windows[key] ?: WindowBounds()).copy(maximized = true)
        } else {
            if (stage.width <= 0 || stage.height <= 0 || stage.width.isNaN() || stage.height.isNaN()) return
            WindowBounds(stage.x, stage.y, stage.width, stage.height, false)
        }
        settings.windows[key] = bounds
        saveDebounce.playFromStart()
    }

    private fun isOnScreen(x: Double, y: Double, width: Double, height: Double): Boolean =
        Screen.getScreensForRectangle(x, y, width, height).isNotEmpty()

    /** Folder last picked in a file-save dialog (e.g. CSV export), remembered across restarts. */
    var lastExportFolder: String?
        get() = settings.lastExportFolder
        set(value) {
            settings.lastExportFolder = value
            saveDebounce.playFromStart()
        }
}
