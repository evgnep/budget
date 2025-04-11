package su.nepom.budget.desktop.util.fx

import io.github.oshai.kotlinlogging.KotlinLogging
import javafx.scene.control.Alert
import javafx.scene.control.ButtonType

private val logger = KotlinLogging.logger { }

fun <T> runAndShowError(block: () -> T): Result<T> =
    runCatching { block() }
        .onFailure {
            logger.error(it) { "Error while running block" }
            Alert(Alert.AlertType.ERROR, "Проблемы: " + it.message, ButtonType.OK).showAndWait()
        }
