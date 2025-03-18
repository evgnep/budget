package su.nepom.budget.events

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import su.nepom.budget.model.Place
import java.nio.file.Path

@Disabled
class EventStoreReaderIntegrationTest {
    @Test
    fun readEvents() {
        val reader = EventStoreReader(Path.of("../_data"), Place.NULL)
        val list = reader.createEventsSequence(mapOf()).toList()
        println(list.size)
    }
}