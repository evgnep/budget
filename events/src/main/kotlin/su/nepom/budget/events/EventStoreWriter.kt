package su.nepom.budget.events

import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import su.nepom.budget.events.model.ActualVersionContent
import su.nepom.budget.events.model.Event
import su.nepom.budget.events.model.FileContent
import su.nepom.budget.events.model.StorableContent
import su.nepom.budget.model.Place
import java.io.OutputStream
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream
import kotlin.math.min

class EventStoreWriter(
    private val rootPath: Path,
    private val source: Place   ,
    private val maxEventsPerFile: Int = 999,
    private val dateProvider: () -> LocalDate =
        { Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date }
) {
    private val eventCountDigits = when {
        maxEventsPerFile < 10 -> 1
        maxEventsPerFile < 100 -> 2
        maxEventsPerFile < 1000 -> 3
        else -> 4
    }

    fun writeEvents(events: List<Event<ActualVersionContent>>) {
        if (events.isEmpty()) return
        events.validate()
        val filesContent = events.paged()
        val target = getTargetDirectory().createDirectories()
        filesContent.forEach { content ->
            target.resolve(content.fileName()).outputStream(StandardOpenOption.CREATE_NEW).use { stream ->
                content.writeToStream(stream)
            }
        }
    }

    private fun List<Event<ActualVersionContent>>.validate() {
        if (isEmpty()) return
        forEachIndexed { index, event ->
            if (index > 1) {
                require(event.coords.no == get(index - 1).coords.no + 1) {
                    "coords.no should be sequential: $event"
                }
            }
            require(event.coords.source == source) {
                "coords.source should be $source: $event"
            }
        }
    }

    private fun List<Event<ActualVersionContent>>.paged(): List<FileContent> {
        val paged = mutableListOf<FileContent>()
        var pos = 0
        while (pos < size) {
            val content = subList(pos, min(pos + maxEventsPerFile, size))
            paged.add(FileContent(content as List<Event<StorableContent>>))
            pos += maxEventsPerFile
        }
        return paged
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun FileContent.writeToStream(stream: OutputStream) {
        Json.encodeToStream(this, stream)
    }

    private fun getTargetDirectory(): Path {
        val date = dateProvider()
        return rootPath
            .resolve(source.code)
            .resolve(String.format("%04d", date.year))
            .resolve(String.format("%02d", date.month.value))
            .resolve(String.format("%02d", date.dayOfMonth))
    }

    private fun FileContent.fileName(): String =
        String.format("%08d-%0${eventCountDigits}d.json", events.first().coords.no, events.size)
}