package su.nepom.budget.desktop.service

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Inject
import jakarta.inject.Singleton
import javafx.application.Platform
import javafx.beans.property.ReadOnlyBooleanProperty
import javafx.beans.property.ReadOnlyBooleanWrapper
import javafx.beans.property.ReadOnlyIntegerProperty
import javafx.beans.property.ReadOnlyIntegerWrapper
import javafx.beans.property.ReadOnlyObjectProperty
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
import kotlinx.datetime.Instant
import su.nepom.budget.Global
import su.nepom.budget.db.Db
import su.nepom.budget.db.Db.SubscribeKind
import su.nepom.budget.db.Session
import su.nepom.budget.db.model.AccountRest
import su.nepom.budget.desktop.util.CheckError
import su.nepom.budget.desktop.util.CheckOk
import su.nepom.budget.desktop.util.CheckResult
import su.nepom.budget.desktop.util.CheckWarning
import su.nepom.budget.desktop.util.db.CheckableDatabaseStringProperty
import su.nepom.budget.events.EventStoreReader
import su.nepom.budget.events.EventStoreWriter
import su.nepom.budget.events.isValidEventFile
import su.nepom.budget.events.isValidFilename
import su.nepom.budget.events.synchronizer.ConflictResolver
import su.nepom.budget.events.synchronizer.EventSynchronizer
import su.nepom.budget.model.Place
import su.nepom.budget.utils.SecondsClock
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
  settingsService: SettingsService,
  private val conflictResolver: ConflictResolver,
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

  private val storeInfoBacked = ReadOnlyObjectWrapper<SyncStoreInfo?>(null)

  val storeInfo: ReadOnlyObjectProperty<SyncStoreInfo?> = storeInfoBacked.readOnlyProperty

  private val lastSyncResultBacked = ReadOnlyObjectWrapper<SyncOutcome?>(null)

  val lastSyncResult: ReadOnlyObjectProperty<SyncOutcome?> = lastSyncResultBacked.readOnlyProperty

  private val hasEventsToSyncBacked = ReadOnlyBooleanWrapper(false)

  val hasEventsToSync: ReadOnlyBooleanProperty = hasEventsToSyncBacked.readOnlyProperty

  private val syncInProgressBacked = ReadOnlyBooleanWrapper(false)

  val syncInProgress: ReadOnlyBooleanProperty = syncInProgressBacked.readOnlyProperty

  private var syncJob: Job? = null

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
      val localCount = events.count { it.coords.source == currentPlace && it.content !is AccountRest }
      if (localCount == 0) return@subscribe
      scope.launch {
        mutex.withLock {
          setOnFx {
            savingGapBacked.set(savingGapBacked.get() + localCount)
          }
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

  fun syncNow() {
    if (syncJob?.isActive == true) {
      logger.info { "Sync already running, ignoring request" }
      return
    }
    syncJob = scope.launch { syncEvents("manual") }
  }

  fun refreshStoreInfo() {
    scope.launch {
      mutex.withLock {
        val d = data ?: return@withLock
        recomputeStoreInfo(d, EventStoreReader(Path.of(d.path), d.currentPlace))
      }
    }
  }

  private suspend fun syncEvents(reason: String) {
    val d = data ?: run {
      logger.warn { "Cannot sync: data is null" }
      return
    }
    val db = d.session.db
    setOnFx { syncInProgressBacked.set(true) }
    try {
      mutex.withLock {
        logger.info { "Synchronizing events because $reason" }
        val reader = EventStoreReader(Path.of(d.path), d.currentPlace)
        val result = EventSynchronizer(reader, db, conflictResolver).synchronize()
        setOnFx {
          lastSyncResultBacked.set(
            SyncOutcome(SecondsClock.now(), result.imported, result.error, result.readingErrors.size)
          )
        }
        logger.info { "Sync done: imported ${result.imported}, error ${result.error}, reading errors ${result.readingErrors}" }
        recomputeStoreInfo(d, reader)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      logger.error(e) { "Error synchronizing events" }
      setOnFx { lastSyncResultBacked.set(SyncOutcome(SecondsClock.now(), 0, e.message ?: "Unknown error", 0)) }
    } finally {
      setOnFx { syncInProgressBacked.set(false) }
    }
  }

  // must be called under mutex
  private suspend fun recomputeStoreInfo(d: Data, reader: EventStoreReader) {
    val onDisk = reader.getMaxEventsNo()
    val imported = d.session.coroDbOp { eventDao.getLastEventCoords() }
    val places = (onDisk.keys + imported.keys)
      .filter { it != d.currentPlace }
      .sortedBy { it.code }
      .map { SyncPlaceInfo(it.code, onDisk[it] ?: 0, imported[it] ?: 0) }
    val info = SyncStoreInfo(d.path, places)
    setOnFx {
      storeInfoBacked.set(info)
      hasEventsToSyncBacked.set(places.any { it.onDisk > it.imported })
    }
  }

  private fun setOnFx(block: () -> Unit) {
    if (Platform.isFxApplicationThread()) block() else Platform.runLater(block)
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
        if (events.isEmpty()) {
          setOnFx {
            savingGapBacked.set(0)
          }
          return@withLock
        }
        try {
          data.writer.writeEvents(events)
        } catch (e: Exception) {
          data.lastLocalEvent = data.readLastLocalEventFromDisk()
          setOnFx {
            savingGapBacked.set(events.last().coords.no - data.lastLocalEvent)
            }
          throw e
        }
        setOnFx {
          savingGapBacked.set(0)
        }
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
          syncEvents("startup or settings changed")
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

data class SyncStoreInfo(val source: String, val places: List<SyncPlaceInfo>)

data class SyncPlaceInfo(val place: String, val onDisk: Int, val imported: Int)

data class SyncOutcome(val at: Instant, val imported: Int, val error: String?, val readingErrors: Int)

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