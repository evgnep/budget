package su.nepom.budget.events.impl

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.ListAssert
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import su.nepom.budget.events.EventStoreReader
import su.nepom.budget.events.EventsSequence
import su.nepom.budget.model.Place
import java.nio.file.Path

/*
    a1 a2 a3(b2, c1)
    b1 b2(a1)
    c1
 */


class EventsSequenceImplTest {
    abstract class AbstractSequenceTest(folder: String, ourPlace: Place) {
        private val reader = EventStoreReader(Path.of("src/test/resources/reader/$folder"), ourPlace)

        protected lateinit var sequence: EventsSequence

        protected  fun readEvents(from: Map<Place, Int>): List<String> =
            reader.createEventsSequence(from)
                .also { sequence = it }
                .map { it.coords.source.code + "-" + it.coords.no }
                .toList()
    }

    @Nested
    inner class Valid: AbstractSequenceTest("valid", Place.NULL)  {
        @Test
        fun readAll() {
            val read = readEvents(mapOf())
            assertThat(read).containsExactlyInAnyOrder("a-1", "a-2", "a-3", "b-1", "b-2", "c-1")
            assertThat(read).ordered(listOf("a-1", "b-1"), listOf("b-2"))
            assertThat(read).ordered(listOf("a-1", "a-2", "b-2", "c-1"), listOf("a-3"))
            assertThat(read).ordered("a-1", "a-2", "a-3")
            assertThat(read).ordered("b-1", "b-2")
            assertThat(sequence.getReadingErrorsByPlace()).isEmpty()
            assertThat(sequence.getNextEventNoByPlace()).containsExactlyInAnyOrderEntriesOf(
                mapOf(Place("a") to 4, Place("b") to 3, Place("c") to 2)
            )
        }

        @Test
        fun readFromA2B3() {
            val read = readEvents(mapOf(Place("a") to 2, Place("b") to 3))
            assertThat(read).containsExactlyInAnyOrder("a-2", "a-3", "c-1")
        }

        @Test
        fun readFromA4B3C2() {
            val read = readEvents(mapOf(Place("a") to 4, Place("b") to 3, Place("c") to 2))
            assertThat(read).isEmpty()
        }
    }

    @Nested
    inner class Cycle: AbstractSequenceTest("cycle", Place.NULL)  {
        @Test
        fun readAll() {
            val read = readEvents(mapOf())
            assertThat(read).containsExactlyInAnyOrder("a-1")
            assertThat(sequence.getReadingErrorsByPlace().values.first().message).contains("Cycle with events: b-1")
        }
    }

    @Nested
    inner class Empty: AbstractSequenceTest("one", Place("b"))  {
        @Test
        fun readAll() {
            val read = readEvents(mapOf())
            assertThat(read).isEmpty()
        }
    }

    @Nested
    inner class DependsOn: AbstractSequenceTest("one", Place("a"))  {
        @Test
        fun readAll() {
            val read = readEvents(mapOf())
            assertThat(read).containsExactlyInAnyOrder("b-1")
        }
    }

    @Nested
    inner class Missed: AbstractSequenceTest("one", Place.NULL)  {
        @Test
        fun readAll() {
            val read = readEvents(mapOf())
            assertThat(read).isEmpty()
            assertThat(sequence.getReadingErrorsByPlace().values.first().message).contains("Event in b needs a-2")
        }
    }

    @Nested
    inner class BadJson: AbstractSequenceTest("bad", Place.NULL)  {
        @Test
        fun readAll() {
            val read = readEvents(mapOf())
            assertThat(read).isEmpty()
            assertThat(sequence.getReadingErrorsByPlace().values.first().message)
                .contains("Can't read file")
                .contains("Field 'coords' is required")
        }
    }
}

private fun ListAssert<String>.ordered(vararg groups: List<String>) {
    val indexes = mutableMapOf<String, Int>()
    actual().forEachIndexed { index, s ->  indexes[s] = index }
    groups.forEachIndexed { index, elems ->
        val previousMax = (0 ..<index).flatMap { groups[it] }.map { it to indexes[it]!! }.maxByOrNull { it.second }
        val currentMin = elems.map { it to indexes[it]!! }.minByOrNull { it.second }
        if (previousMax != null && currentMin != null) {
            assertThat(previousMax.second)
                .describedAs { "Element ${currentMin.first} is before ${previousMax.first}" }
                .isLessThan(currentMin.second)
        }
    }
}

private fun ListAssert<String>.ordered(vararg groups: String) {
    ordered(*groups.map { listOf(it) }.toTypedArray())
}