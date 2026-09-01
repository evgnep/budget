package su.nepom.budget.events.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import su.nepom.budget.event.ActualEvent
import su.nepom.budget.events.EventStoreReader
import su.nepom.budget.events.EventsSequence
import su.nepom.budget.events.model.FileContent
import su.nepom.budget.events.model.toActualVersion
import su.nepom.budget.exception.LogicException
import su.nepom.budget.model.EventCoords
import su.nepom.budget.model.Place
import java.io.FileInputStream
import java.nio.file.Path

private val logger = KotlinLogging.logger { }

internal class EventsSequenceImpl(storeReader: EventStoreReader, from: Map<Place, Int>) :
    EventsSequence, Iterator<ActualEvent> {

    private var iteratorWasReturned = false

    private val ourPlace = storeReader.ourPlace

    private val unreadFilesByPlace: MutableMap<Place, ArrayDeque<EventStoreReader.EventFile>> =
        storeReader.getFilesSequence()
            .filter { file -> from[file.place].let { it == null || it <= file.endEventNo } }
            .sortedBy { it.startEventNo }
            .groupingBy { it.place }
            .aggregateTo(mutableMapOf()) { _, acc, element, _ -> (acc ?: ArrayDeque()).apply { add(element) } }


    private val nextEventNoByPlace: MutableMap<Place, Int> = (from.keys + unreadFilesByPlace.keys)
        .associateWithTo(mutableMapOf()) { from[it] ?: 1 }

    private val readingErrors = mutableMapOf<Place, EventsSequence.ReadingError>()

    private var currentPlace: Place? = null

    private var currentEvent: ActualEvent? = null

    private val readFiles = mutableMapOf<Place, ReadFile>()

    init {
        logger.debug {
            "Read events in ${storeReader.rootPath} from $from"
        }
        logger.debug {
            "Unread files: " +
                    unreadFilesByPlace.values.asSequence().flatten().joinToString("\n") { it.path.toString() }

        }
        logger.debug { "Next event no by place: $nextEventNoByPlace" }
    }

    override fun iterator(): Iterator<ActualEvent> {
        if (iteratorWasReturned) throw IllegalStateException("Iterator was already returned")
        iteratorWasReturned = true
        return this
    }

    override fun getNextEventNoByPlace(): Map<Place, Int> = nextEventNoByPlace.toMap()

    override fun getReadingErrorsByPlace(): Map<Place, EventsSequence.ReadingError> = readingErrors.toMap()

    override fun next(): ActualEvent {
        do {
            currentEvent?.also {
                currentEvent = null
                logger.debug { "Returning event: ${it.coords}" }
                return it
            }
        } while (hasNext())
        throw NoSuchElementException()
    }

    override fun hasNext(): Boolean {
        if (currentEvent != null) return true
        val restPlaces = HashSet(unreadFilesByPlace.keys).apply { addAll(readFiles.keys) }
        while (readFiles.isNotEmpty() || unreadFilesByPlace.isNotEmpty()) {
            val place = selectPlace(restPlaces) ?: return false
            val readFile = tryReadFileFromPlace(place)
            if (readFile == null) {
                currentPlace = null
                continue
            }
            val nextEventNo = nextEventNoByPlace[place] ?: throw LogicException("No nextEventNo for $place")
            if (nextEventNo !in readFile) {
                addErrorForPlace(
                    place,
                    nextEventNo,
                    "Event no $nextEventNo is out of range. File ${readFile.path}"
                )
                currentPlace = null
                continue
            }
            val nextEvent = readFile.events[nextEventNo - readFile.startEventNo]
            if (!isAncestorsRead(nextEvent)) {
                currentPlace = null
                continue
            }
            currentEvent = nextEvent
            if (nextEventNo == readFile.endEventNo) {
                readFiles.remove(place)
            }
            nextEventNoByPlace[place] = nextEventNo + 1
            return true
        }
        return false
    }

    private fun selectPlace(restPlaces: MutableSet<Place>): Place? {
        val place = currentPlace ?: restPlaces.firstOrNull()
        if (place == null) {
            addPlaceCycleError()
            return null
        }
        currentPlace = place
        restPlaces.remove(place)
        return place
    }

    private fun isAncestorsRead(event: ActualEvent): Boolean =
        event.basedOn.all { (source, no) -> source == ourPlace || nextEventNoByPlace[source]?.let { it > no } ?: false }

    private fun addPlaceCycleError() {
        readFiles.forEach { (place, file) ->
            val eventNo = nextEventNoByPlace[place] ?: return@forEach
            if (eventNo !in file) return@forEach
            val event = file.events[eventNo - file.startEventNo]
            event.basedOn.forEach { base ->
                if (base.source !in readFiles && base.source !in readingErrors) {
                    addErrorForPlace(
                        place,
                        eventNo,
                        "Event in $place needs $base"
                    )
                }
            }
        }
        if (readingErrors.isEmpty()) {
            if (readFiles.isNotEmpty()) {
                val firstFilePlace = readFiles.keys.first()
                addErrorForPlace(firstFilePlace, nextEventNoByPlace[firstFilePlace]!!, "Cycle with events: " +
                readFiles.values.drop(1).joinToString {
                    val place = it.events.first().coords.source
                    EventCoords(place, nextEventNoByPlace[place]!!).toString()
                })
            } else addErrorForPlace(Place.NULL, 0, "Cycle without files...")
        }
    }

    private fun tryReadFileFromPlace(from: Place): ReadFile? {
        var readFile = readFiles[from]
        if (readFile != null) return readFile
        val unreadFiles = unreadFilesByPlace[from] ?: return null
        val fileToRead = unreadFiles.removeFirstOrNull() ?: throw LogicException("Empty files in $from")
        if (unreadFiles.isEmpty()) unreadFilesByPlace.remove(from)
        try {
            val content = fileToRead.path.readContent()
            readFile = ReadFile(content.events.map { it.toActualVersion() }, fileToRead.startEventNo, fileToRead.path)
            readFiles[from] = readFile
            return readFile

        } catch (e: Exception) {
            addErrorForPlace(from, 0, "Can't read file ${fileToRead.path}: ${e.message}", e)
            return null
        }
    }

    private fun addErrorForPlace(place: Place, eventNo: Int, message: String, exception: Exception? = null) {
        logger.debug { "Adding error for place $place: $eventNo: $message" }
        readingErrors[place] = EventsSequence.ReadingError(place, eventNo, message, exception)
        unreadFilesByPlace.remove(place)
        readFiles.remove(place)
        if (place == currentPlace) {
            currentPlace = null
        }
    }

    private data class ReadFile(
        val events: List<ActualEvent>,
        val startEventNo: Int,
        val path: Path
    ) {
        init {
            validate()
        }
        val endEventNo: Int get() = startEventNo + events.size - 1

        operator fun contains(eventNo: Int): Boolean = eventNo in startEventNo until startEventNo + events.size

        private fun validate() {
            if (events.isEmpty()) throw IllegalStateException("Empty events in file $path")
            if (events.first().coords.no != startEventNo) throw IllegalStateException("Bad start event no in file $path")
            if (events.last().coords.no != endEventNo) throw IllegalStateException("Bad end event no in file $path")
            events.forEachIndexed { index, event ->
                if (index > 0) {
                    require(event.coords.no == events[index - 1].coords.no + 1) {
                        "Bad event no in file $path: ${events[index].coords.no} != ${events[index - 1].coords.no + 1}"
                    }
                }
            }
        }

    }
}

internal fun Path.readContent(): FileContent =
    FileInputStream(toFile()).use { stream ->
        Json.decodeFromStream<FileContent>(stream)
    }
