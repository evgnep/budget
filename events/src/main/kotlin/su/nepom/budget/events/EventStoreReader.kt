package su.nepom.budget.events

import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.walk

private val eventFilenameRegex = Regex("(\\d+)-(\\d+)\\.json")

class EventStoreReader(
    private val rootPath: Path,
) {
    fun getAllSources(): List<String> =
        rootPath.listDirectoryEntries().filter { it.isDirectory() }.map { it.fileName.toString() }

    fun getMaxEventNoForSource(source: String): Int? =
        rootPath.resolve(source).walk()
            .mapNotNull {
                it.fileName.toString().getFirstEventNoAndCount()?.let {  (first, count) -> first + count - 1 }
            }
            .maxOrNull()
}

fun String.getFirstEventNoAndCount(): Pair<Int, Int>? =
    eventFilenameRegex.matchEntire(this)?.let {
        it.groupValues[1].toInt() to it.groupValues[2].toInt()
    }