package su.nepom.budget.events

import su.nepom.budget.event.ActualEvent
import su.nepom.budget.events.impl.EventsSequenceImpl
import su.nepom.budget.events.impl.readContent
import su.nepom.budget.model.Place
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.walk

internal val eventFilenameRegex = Regex("(\\d+)-(\\d+)\\.json")

class EventStoreReader(
    val rootPath: Path,
    val ourPlace: Place,
) {
    fun getAllSources(): List<Place> =
        rootPath.listDirectoryEntries().filter { it.isDirectory() }.map { Place(it.fileName.toString()) }

    fun getFilesSequence(resolver: Path.() -> Path = { this }): Sequence<EventFile> = rootPath.let(resolver).walk()
        .mapNotNull {
            it.fileName.toString().getFirstEventNoAndCount()?.let { (first, count) ->
                val place = Place(
                    Path.of(it.absolutePathString().removePrefix(rootPath.absolutePathString()))
                        .subpath(0, 1).toString()
                )
                if (place == ourPlace) null else EventFile(place, it, first, count)
            }
        }

    fun getMaxEventsNo(): Map<Place, Int> =
        getFilesSequence()
            .groupingBy { it.place }
            .fold(0) { acc, eventFile -> maxOf(acc, eventFile.endEventNo) }

    fun getMaxEventNoForSource(source: Place): Int? =
        getFilesSequence { resolve(source.code) }
            .map { it.endEventNo }
            .maxOrNull()

    fun createEventsSequence(from: Map<Place, Int>): EventsSequence = EventsSequenceImpl(this, from)

    data class EventFile(val place: Place, val path: Path, val startEventNo: Int, val eventsCount: Int) {
        val endEventNo: Int get() = startEventNo + eventsCount - 1
        val alastEventNo: Int get() = startEventNo + eventsCount
    }
}

interface EventsSequence : Sequence<ActualEvent> {
    fun getNextEventNoByPlace(): Map<Place, Int>

    fun getReadingErrorsByPlace(): Map<Place, ReadingError>

    data class ReadingError(
        val place: Place,
        val eventNo: Int,
        val message: String,
        val exception: Exception? = null
    )
}

private fun String.getFirstEventNoAndCount(): Pair<Int, Int>? =
    eventFilenameRegex.matchEntire(this)?.let {
        it.groupValues[1].toInt() to it.groupValues[2].toInt()
    }

fun isValidFilename(filename: String): Boolean = eventFilenameRegex.matches(filename)

fun isValidEventFile(file: Path): String? =
    try {
        file.readContent()
        null
    } catch (e: Exception) {
        e.message ?: "Unknown error"
    }
