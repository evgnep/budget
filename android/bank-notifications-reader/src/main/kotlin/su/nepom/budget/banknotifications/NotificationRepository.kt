package su.nepom.budget.banknotifications

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Stores read notifications in a plain text file so the log survives app
 * restarts (the listener service can run without the activity).
 */
object NotificationRepository {
    private lateinit var logFile: File
    private val timeFormat = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())

    private val _text = MutableStateFlow("")
    val text: StateFlow<String> get() = _text

    fun init(context: Context) {
        if (::logFile.isInitialized) return
        logFile = File(context.filesDir, "notifications.log")
        _text.value = if (logFile.exists()) logFile.readText() else ""
    }

    @Synchronized
    fun append(packageName: String, title: String?, content: String?) {
        val line = "${timeFormat.format(Date())} [$packageName] ${title.orEmpty()}: ${content.orEmpty()}\n"
        logFile.appendText(line)
        _text.value += line
    }

    @Synchronized
    fun clear() {
        logFile.writeText("")
        _text.value = ""
    }
}
