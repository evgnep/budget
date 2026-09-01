package su.nepom.budget.db.sqlite.impl

import org.ktorm.dsl.and
import org.ktorm.dsl.eq
import org.ktorm.entity.filter
import org.ktorm.entity.forEach
import su.nepom.budget.db.sqlite.SqliteDatabase
import su.nepom.budget.db.sqlite.mapping.simpleObjects
import su.nepom.budget.event.ActualVersionContent
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.Uuid
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches only visible (!isHidden) simple objects, one map per kind, loaded lazily on first access.
 * Uncommitted changes of the current write transaction live in a separate overlay so readers in
 * other sessions don't see them before commit.
 */
internal class SimpleObjectCache(private val db: SqliteDatabase) {
    private val visibleByKind = ConcurrentHashMap<ObjectKind, ConcurrentHashMap<Uuid, ActualVersionContent>>()
    private val overlay = ConcurrentHashMap<ObjectKind, MutableMap<Uuid, ActualVersionContent>>()

    private fun visible(kind: ObjectKind) = visibleByKind.computeIfAbsent(kind) { loadVisible(it) }

    private fun loadVisible(kind: ObjectKind): ConcurrentHashMap<Uuid, ActualVersionContent> {
        val loaded = ConcurrentHashMap<Uuid, ActualVersionContent>()
        db.simpleObjects
            .filter { (it.kind eq kind.name) and (it.hidden eq false) }
            .forEach { loaded[Uuid(it.uuid)] = it.toContent() }
        return loaded
    }

    fun getVisible(kind: ObjectKind): List<ActualVersionContent> {
        val changes = overlay[kind] ?: return visible(kind).values.toList()
        val merged = HashMap(visible(kind))
        changes.forEach { (id, content) -> if (content.isHidden) merged.remove(id) else merged[id] = content }
        return merged.values.toList()
    }

    fun get(kind: ObjectKind, id: Uuid): ActualVersionContent? = overlay[kind]?.get(id) ?: visible(kind)[id]

    fun setInTransaction(kind: ObjectKind, content: ActualVersionContent) {
        overlay.computeIfAbsent(kind) { ConcurrentHashMap() }[content.uuid] = content
    }

    fun onTransactionStart() = overlay.clear()

    fun onTransactionRollback() = overlay.clear()

    fun onTransactionCommit() {
        overlay.forEach { (kind, changes) ->
            val base = visibleByKind[kind] ?: return@forEach // not loaded yet - next access loads fresh
            changes.forEach { (id, content) -> if (content.isHidden) base.remove(id) else base[id] = content }
        }
        overlay.clear()
    }
}
