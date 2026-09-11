package su.nepom.budget.desktop.ui.common

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Inject
import javafx.application.Platform
import javafx.fxml.FXML
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.Hyperlink
import javafx.scene.control.Label
import javafx.scene.control.ProgressBar
import javafx.scene.layout.HBox
import javafx.stage.FileChooser
import javafx.stage.Stage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import su.nepom.budget.desktop.service.GLOBAL_SETTINGS
import su.nepom.budget.desktop.service.WindowStateService
import su.nepom.budget.desktop.util.buildCsvText
import su.nepom.budget.desktop.util.fx.Controller
import su.nepom.budget.desktop.util.fx.Disposable
import java.io.File

private val logger = KotlinLogging.logger {}

private const val EXPORT_PAGE_SIZE = 500

/**
 * Everything a host needs to export a filtered result set to CSV, without [CsvExportController]
 * knowing anything about the host's DB session, model, or filter. [totalCount] and [fetchPage] run
 * off the FX thread, in the same coroutine, so a host that needs a DB session across them can open
 * it once and close it in [onFinished] (called on success, error, and cancellation alike).
 */
class CsvExportSpec(
    val suggestedFileName: String,
    val header: List<String>,
    val totalCount: suspend () -> Int,
    val fetchPage: suspend (offset: Int, limit: Int) -> List<List<String>>,
    val onFinished: suspend () -> Unit = {},
)

/**
 * Reusable "Экспорт CSV" widget: a button that turns into a progress bar + "Отменить" hyperlink of
 * the same height while the export runs, embed with fx:include (see csvExport.fxml). The host calls
 * [setStage] and [configure] once; [configure]'s provider is invoked fresh on every click, so it can
 * capture the host's current filter state.
 */
class CsvExportController @Inject constructor(
    private val windowStateService: WindowStateService,
) : Controller, Disposable {
    @FXML private lateinit var exportButton: Button
    @FXML private lateinit var progressBox: HBox
    @FXML private lateinit var progressBar: ProgressBar
    @FXML private lateinit var progressLabel: Label
    @FXML private lateinit var cancelLink: Hyperlink

    private lateinit var stage: Stage
    private val scope = CoroutineScope(Dispatchers.Default)
    private var job: Job? = null

    @FXML
    private fun initialize() {
        cancelLink.setOnAction { job?.cancel() }
        showButton()
    }

    fun setStage(stage: Stage) {
        this.stage = stage
    }

    fun configure(specProvider: () -> CsvExportSpec?) {
        exportButton.setOnAction { specProvider()?.let(::start) }
    }

    override fun dispose() {
        scope.cancel()
    }

    private fun start(spec: CsvExportSpec) {
        if (job?.isActive == true) return
        showProgress(0, 0)
        job = scope.launch {
            try {
                val total = spec.totalCount()
                setOnFx { showProgress(0, total) }
                val rows = ArrayList<List<String>>(total)
                var offset = 0
                while (offset < total) {
                    ensureActive()
                    val page = spec.fetchPage(offset, EXPORT_PAGE_SIZE)
                    if (page.isEmpty()) break
                    rows += page
                    offset += page.size
                    setOnFx { showProgress(offset, total) }
                }
                val csvText = buildCsvText(spec.header, rows)
                setOnFx { saveToFile(spec.suggestedFileName, csvText) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "CSV export failed" }
                setOnFx { Alert(Alert.AlertType.ERROR, "Не удалось экспортировать: ${e.message}", ButtonType.OK).showAndWait() }
            } finally {
                withContext(NonCancellable) { runCatching { spec.onFinished() } }
                setOnFx { showButton() }
            }
        }
    }

    private fun saveToFile(suggestedFileName: String, content: String) {
        val chooser = FileChooser().apply {
            title = "Сохранить CSV"
            initialFileName = suggestedFileName
            extensionFilters.add(FileChooser.ExtensionFilter("CSV файлы", "*.csv"))
            windowStateService.getString(GLOBAL_SETTINGS, "lastExportFolder")
                ?.let { File(it) }?.takeIf { it.isDirectory }?.let { initialDirectory = it }
        }
        val file = chooser.showSaveDialog(stage) ?: return
        windowStateService.setString(GLOBAL_SETTINGS, "lastExportFolder", file.parentFile?.absolutePath)
        runCatching { file.writeText(content, Charsets.UTF_8) }
            .onFailure {
                Alert(Alert.AlertType.ERROR, "Не удалось сохранить файл: ${it.message}", ButtonType.OK)
                    .showAndWait()
            }
    }

    private fun showButton() {
        exportButton.isVisible = true
        exportButton.isManaged = true
        progressBox.isVisible = false
        progressBox.isManaged = false
    }

    private fun showProgress(done: Int, total: Int) {
        exportButton.isVisible = false
        exportButton.isManaged = false
        progressBox.isVisible = true
        progressBox.isManaged = true
        progressBar.progress = if (total <= 0) -1.0 else done.toDouble() / total
        progressLabel.text = if (total <= 0) "Экспорт..." else "$done из $total"
    }

    private fun setOnFx(block: () -> Unit) {
        if (Platform.isFxApplicationThread()) block() else Platform.runLater(block)
    }
}
