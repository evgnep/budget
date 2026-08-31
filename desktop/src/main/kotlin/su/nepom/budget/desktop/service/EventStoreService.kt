package su.nepom.budget.desktop.service

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Inject
import jakarta.inject.Singleton
import javafx.beans.property.ReadOnlyIntegerProperty
import javafx.beans.property.ReadOnlyIntegerWrapper
import javafx.beans.property.ReadOnlyObjectWrapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import su.nepom.budget.Global
import su.nepom.budget.db.Db
import su.nepom.budget.db.Db.SubscribeKind
import su.nepom.budget.db.Session
import su.nepom.budget.desktop.util.CheckError
import su.nepom.budget.desktop.util.CheckOk
import su.nepom.budget.desktop.util.CheckResult
import su.nepom.budget.desktop.util.CheckWarning
import su.nepom.budget.desktop.util.db.CheckableDatabaseStringProperty
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
import kotlin.time.Duration.Companion.minutes

@Singleton
class EventStoreService @Inject constructor(
  private val dbService: DbService,
  settingsService: SettingsService
) {
  private companion object {
    val logger = KotlinLogging.logger {}
  }

  private class Data(
    val writer: EventStoreWriter,
    val session: Session,
    val currentPlace: Place,
    val path: String,
  ) {
    var lastLocalEvent: Int = readLastLocalEventFromDisk()
    var currentDbSubscription: Db.Subscription? = null
    var syncIfNoNewEvents: Job? = null
    var syncOnTimeout: Job? = null

    fun readLastLocalEventFromDisk(): Int =
      EventStoreReader(Path.of(path), Place.NULL).getMaxEventNoForSource(currentPlace) ?: 0

    suspend fun stopAll() {
      currentDbSubscription?.unsubscribe()
      syncIfNoNewEvents?.cancel()
      syncOnTimeout?.cancel()
      session.coroDbOp {
        close()
      }
    }
  }

  private var data: Data? = null

  private val mutex = Mutex()

  private val scope = CoroutineScope(Dispatchers.Default)

  private val savingGapBacked = ReadOnlyIntegerWrapper(0)

  val savingGap: ReadOnlyIntegerProperty = savingGapBacked.readOnlyProperty

  val eventStoreFolder =
    CheckableDatabaseStringProperty(
      "eventStoreFolder",
      dbService.sessionProperty,
      "Путь к папке с событиями не задан"
    ) { checkPath(it) }

  init {
    eventStoreFolder.property.addListener { _, _, _ -> onEventStoreFolderOrPlaceOrDbChanged() }
    settingsService.place.addListenerAndCallItNow { _, _, _ -> onEventStoreFolderOrPlaceOrDbChanged() }
    dbService.sessionProperty.addListener {
      if (data?.session?.db != dbService.db) {
        onEventStoreFolderOrPlaceOrDbChanged()
      }
    }
  }

  private fun Data.resubscribe() {
    currentDbSubscription?.unsubscribe()
    currentDbSubscription = session.db.subscribe(SubscribeKind.ALL, { events ->
      val localCount = events.count { it.coords.source == currentPlace }
      if (localCount == 0) return@subscribe
      scope.launch {
        mutex.withLock {
          savingGapBacked.set(savingGapBacked.get() + localCount)
          syncIfNoNewEvents?.cancel()
          syncIfNoNewEvents = scope.launch {
            delay(10.minutes)
            scope.launch { saveEvents("ifNoNewEvents") }
          }
          if (syncOnTimeout == null) {
            syncOnTimeout = scope.launch {
              delay(30.minutes)
              scope.launch { saveEvents("onTimeout") }
            }
          }
        }
      }
    })
  }

  fun saveNow() {
    scope.launch {
      saveEvents("manual")
    }
  }

  private suspend fun saveEvents(reason: String) {
    try {
      mutex.withLock {
        logger.info { "Saving events because $reason" }
        val data = data ?: run {
          logger.warn { "data is null" }
          return@withLock
        }
        data.syncIfNoNewEvents?.cancel()
        data.syncIfNoNewEvents = null
        data.syncOnTimeout?.cancel()
        data.syncOnTimeout = null
        val events = data.session.coroDbOp {
          eventDao.getEventsForSourceFrom(data.currentPlace, data.lastLocalEvent + 1)
        }
        logger.info { "Got ${events.size} new events from ${data.lastLocalEvent}" }
        if (events.isEmpty()) return@withLock
        try {
          data.writer.writeEvents(events)
        } catch (e: Exception) {
          data.lastLocalEvent = data.readLastLocalEventFromDisk()
          savingGapBacked.set(events.last().coords.no - data.lastLocalEvent)
          throw e
        }
        savingGapBacked.set(0)
        data.lastLocalEvent = events.last().coords.no
        logger.info { "Write ${events.size} events, new lastLocalEvent = ${data.lastLocalEvent}" }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      logger.error(e) { "Error saving events" }
    }
  }

  private fun onEventStoreFolderOrPlaceOrDbChanged() {
    scope.launch {
      try {
        val ok = mutex.withLock {
          val path = eventStoreFolder.property.value ?: return@launch
          val place = Global.currentPlace.takeIf { it != Place.NULL } ?: return@launch
          val writer = EventStoreWriter(Path.of(path), place)
          val session = dbService.db?.createSession("writeEvents", autoCommit = true, createEvents = false)
          if (session != null) {
            data?.stopAll()
            data = Data(writer, session, place, path)
            data?.resubscribe()
            true
          } else false
        }
        if (ok) {
          saveEvents("startup or settings changed")
        }
      } catch (e: Exception) {
        logger.error(e) { "Error during initialization" }
      }
    }
  }

  fun onStop() {
    runBlocking {
      saveEvents("onStop")
    }
    scope.cancel()
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