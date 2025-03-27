package su.nepom.budget.desktop.service

import jakarta.inject.Inject
import jakarta.inject.Singleton
import su.nepom.budget.Global
import su.nepom.budget.desktop.util.CheckError
import su.nepom.budget.desktop.util.CheckOk
import su.nepom.budget.desktop.util.CheckResult
import su.nepom.budget.desktop.util.CheckWarning
import su.nepom.budget.desktop.util.CheckableDatabaseStringProperty
import su.nepom.budget.events.EventStoreReader
import su.nepom.budget.events.EventStoreWriter
import su.nepom.budget.events.isValidEventFile
import su.nepom.budget.events.isValidFilename
import su.nepom.budget.model.Place
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.walk

@Singleton
class EventStoreService @Inject constructor(
    dbService: DbService,
    settingsService: SettingsService
) {
    var reader: EventStoreReader? = null
        private set

    var writer: EventStoreWriter? = null
        private set

    val eventStoreFolder =
        CheckableDatabaseStringProperty(
            "eventStoreFolder",
            dbService.sessionProperty,
            "Путь к папке с событиями не задан"
        ) { checkPath(it) }

    init {
        eventStoreFolder.property.addListener { _, _, _ -> onEventStoreFolderOrPlaceChanged() }
        settingsService.place.addListenerAndCallItNow { _, _, _ -> onEventStoreFolderOrPlaceChanged() }
    }

    private fun onEventStoreFolderOrPlaceChanged() {
        val path = eventStoreFolder.property.value ?: return
        val place = Global.currentPlace.takeIf { it != Place.NULL } ?: return
        reader = EventStoreReader(Path.of(path), place)
        writer = EventStoreWriter(Path.of(path), place)
    }
}

private fun checkPath(path: String): CheckResult {
    if (path.isBlank()) return CheckError("Укажите папку")
    val path = try {
        Path.of(path)
    } catch (e: Exception) {
        return CheckError("Некорректный путь: " + e.message)
    }
    if (!path.exists()) return CheckError("Папка не существует")
    if (!path.isDirectory()) return CheckError("Это не папка")
    path.listDirectoryEntries().forEach { child ->
        if (!child.isDirectory()) {
            return CheckWarning("Неожиданный файл $child")
        }
        child.walk().take(10).forEach { eventFile ->
            if (!eventFile.isRegularFile() || !isValidFilename(eventFile.fileName.toString())) {
                return CheckWarning("Неожиданный файл $eventFile")
            }
            isValidEventFile(eventFile)?.apply {
                return CheckWarning("Неверное содержимое файла $eventFile: $this")
            }
        }
    }
    return CheckOk
}