package su.nepom.budget.desktop.service

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Inject
import jakarta.inject.Singleton
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.collections.ListChangeListener
import javafx.scene.control.SplitPane
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.TreeTableColumn
import javafx.scene.control.TreeTableView
import javafx.stage.Screen
import javafx.stage.Stage
import javafx.stage.WindowEvent
import javafx.util.Duration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.notExists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val logger = KotlinLogging.logger {}

val GLOBAL_SETTINGS = "global"

@Serializable
sealed interface Setting

@Serializable
private data class WindowBounds(
    val x: Double? = null,
    val y: Double? = null,
    val width: Double? = null,
    val height: Double? = null,
    val maximized: Boolean = false,
): Setting

@Serializable
private data class SplitPaneSettings(
    val dividerPositions: MutableList<Double> = mutableListOf(),
): Setting

@Serializable
private data class StringSetting(val value: String): Setting

@Serializable
private data class TableColumnsSettings(
    val columnOrder: MutableList<String> = mutableListOf(),
    val columnWidths: MutableMap<String, Double> = mutableMapOf(),
): Setting

@Serializable
private data class WindowSettings(
    val settings: MutableMap<String, Setting> = mutableMapOf(),
    val bounds: WindowBounds = WindowBounds(),
)

@Serializable
private data class Settings(
    val windows: MutableMap<String, WindowSettings> = mutableMapOf(),
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

    private fun load(): Settings {
        if (path.notExists()) return Settings()
        return try {
            json.decodeFromString<Settings>(path.readText())
        } catch (e: Exception) {
            logger.warn(e) { "Can't read $path, using defaults" }
            Settings()
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
     * Apply the saved bounds for [window] to [stage] and start tracking its changes.
     * Call before `stage.show()`.
     */
    fun bindWindowBounds(stage: Stage, window: String) {
        settings.windows[window]?.bounds?.let { b ->
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

        val listener = javafx.beans.value.ChangeListener<Any> { _, _, _ -> store(stage, window) }
        stage.widthProperty().addListener(listener)
        stage.heightProperty().addListener(listener)
        stage.xProperty().addListener(listener)
        stage.yProperty().addListener(listener)
        stage.maximizedProperty().addListener(listener)
    }

    private fun store(stage: Stage, key: String) {
        val bounds = if (stage.isMaximized) {
            (settings.windows[key]?.bounds ?: WindowBounds()).copy(maximized = true)
        } else {
            if (stage.width <= 0 || stage.height <= 0 || stage.width.isNaN() || stage.height.isNaN()) return
            WindowBounds(stage.x, stage.y, stage.width, stage.height, false)
        }
        settings.windows[key] = (settings.windows[key] ?: WindowSettings()).copy(bounds = bounds)
        saveDebounce.playFromStart()
    }

    private fun isOnScreen(x: Double, y: Double, width: Double, height: Double): Boolean =
        Screen.getScreensForRectangle(x, y, width, height).isNotEmpty()

    fun getString(window: String, setting: String): String? =
        (settings.windows[window]?.settings?.get(setting) as? StringSetting)?.value

    fun setString(window: String, setting: String, value: String?) {
        val windowSettings = settings.windows.getOrPut(window) { WindowSettings() }
        if (value == null) {
            windowSettings.settings.remove(setting)
        } else {
            windowSettings.settings[setting] = StringSetting(value)
        }
        saveDebounce.playFromStart()
    }

    fun bindSplitPane(stage: Stage, window: String, splitPane: SplitPane) {
        val splitPaneSettings = settings.windows.getOrPut(window, { WindowSettings() }).settings.let {
            val s = it[splitPane.id] as? SplitPaneSettings
            if (s == null) it[splitPane.id] = SplitPaneSettings()
            it[splitPane.id] as SplitPaneSettings
        }
        if (splitPaneSettings.dividerPositions.isNotEmpty()) {
            // JavaFX resets divider positions on the first layout pass after the window becomes
            // visible, so we have to apply them after WINDOW_SHOWN, deferred by one more pulse.
            val positions = splitPaneSettings.dividerPositions.toDoubleArray()
            val apply = { splitPane.setDividerPositions(*positions) }
            if (stage.isShowing) {
                Platform.runLater(apply)
            } else {
                stage.addEventHandler(WindowEvent.WINDOW_SHOWN) { Platform.runLater(apply) }
            }
        }
        stage.addEventHandler(WindowEvent.WINDOW_HIDDEN) {
            splitPaneSettings.dividerPositions.clear()
            splitPaneSettings.dividerPositions.addAll(splitPane.dividerPositions.toList())
            saveDebounce.playFromStart()
        }
    }

    // no Stage needed - columns save continuously as they change, so this works both for windows
    // and for forms embedded (fx:include) into another screen with no window of their own
    fun <T> bindTableColumns(window: String, table: TableView<T>) {
        val key = table.id ?: return
        val columnsSettings = settings.windows.getOrPut(window, { WindowSettings() }).settings.let {
            val s = it[key] as? TableColumnsSettings
            if (s == null) it[key] = TableColumnsSettings()
            it[key] as TableColumnsSettings
        }

        if (columnsSettings.columnOrder.isNotEmpty()) {
            val byId = table.columns.associateBy { it.id }
            val ordered = columnsSettings.columnOrder.mapNotNull { byId[it] }
            val rest = table.columns.filterNot { it.id in columnsSettings.columnOrder }
            table.columns.setAll(ordered + rest)
        }
        table.columns.forEach { col ->
            val width = columnsSettings.columnWidths[col.id]
            if (col.isResizable && width != null && width > 0) col.prefWidth = width
        }

        fun store() {
            columnsSettings.columnOrder.clear()
            columnsSettings.columnOrder.addAll(table.columns.mapNotNull { it.id })
            columnsSettings.columnWidths.clear()
            table.columns.forEach { col -> col.id?.let { id -> columnsSettings.columnWidths[id] = col.width } }
            saveDebounce.playFromStart()
        }
        table.columns.forEach { col -> col.widthProperty().addListener { _, _, _ -> store() } }
        table.columns.addListener(ListChangeListener<TableColumn<T, *>> { store() })
    }

    // no Stage needed - see bindTableColumns
    fun <T> bindTreeTableColumns(window: String, table: TreeTableView<T>) {
        val key = table.id ?: return
        val columnsSettings = settings.windows.getOrPut(window, { WindowSettings() }).settings.let {
            val s = it[key] as? TableColumnsSettings
            if (s == null) it[key] = TableColumnsSettings()
            it[key] as TableColumnsSettings
        }

        if (columnsSettings.columnOrder.isNotEmpty()) {
            val byId = table.columns.associateBy { it.id }
            val ordered = columnsSettings.columnOrder.mapNotNull { byId[it] }
            val rest = table.columns.filterNot { it.id in columnsSettings.columnOrder }
            table.columns.setAll(ordered + rest)
        }
        table.columns.forEach { col ->
            val width = columnsSettings.columnWidths[col.id]
            if (col.isResizable && width != null && width > 0) col.prefWidth = width
        }

        fun store() {
            columnsSettings.columnOrder.clear()
            columnsSettings.columnOrder.addAll(table.columns.mapNotNull { it.id })
            columnsSettings.columnWidths.clear()
            table.columns.forEach { col -> col.id?.let { id -> columnsSettings.columnWidths[id] = col.width } }
            saveDebounce.playFromStart()
        }
        table.columns.forEach { col -> col.widthProperty().addListener { _, _, _ -> store() } }
        table.columns.addListener(ListChangeListener<TreeTableColumn<T, *>> { store() })
    }
}
