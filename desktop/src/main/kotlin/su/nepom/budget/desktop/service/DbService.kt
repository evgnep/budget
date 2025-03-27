package su.nepom.budget.desktop.service

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Inject
import jakarta.inject.Singleton
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import su.nepom.budget.db.Db
import su.nepom.budget.db.Session
import su.nepom.budget.db.sqlite.createSqliteDatabase
import java.nio.file.Path
import kotlin.io.path.isRegularFile

private val logger = KotlinLogging.logger { }

@Singleton
class DbService @Inject constructor() {
    val dbPath = Path.of("./budget.sqlite").toAbsolutePath().normalize()

    var db: Db? = null
        private set

    private val sessionPropertyWrapper = ReadOnlyObjectWrapper<Session?>()

    val sessionProperty: ReadOnlyObjectProperty<Session?> = sessionPropertyWrapper.readOnlyProperty

    val session: Session? get() = sessionProperty.value

    init {
        openExisting()
    }

    fun openExisting(): String {
        if (db != null) return ""
        if (!dbPath.isRegularFile()) return "Нет файла БД"
        return create()
    }

    fun create(): String {
        try {
            db = createSqliteDatabase(dbPath)
                .also {
                    sessionPropertyWrapper.value = it.createSession("UI", autoCommit = true)
                }
            return ""
        } catch (e: Exception) {
            logger.error(e) { "Can't open database" }
            return "Не получается открыть БД"
        }
    }
}