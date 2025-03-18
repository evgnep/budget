package su.nepom.budget.events.model

import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import su.nepom.budget.event.AccountContentV1
import su.nepom.budget.event.CurrencyContentV1
import su.nepom.budget.event.Event
import su.nepom.budget.event.EventType
import su.nepom.budget.event.StorableContent
import su.nepom.budget.event.TransactionContentV1
import su.nepom.budget.model.AccountCode
import su.nepom.budget.model.AccountId
import su.nepom.budget.model.AccountKind
import su.nepom.budget.model.CurrencyCode
import su.nepom.budget.model.CurrencyId
import su.nepom.budget.model.EventCoords
import su.nepom.budget.model.Place
import su.nepom.budget.model.Uuid
import su.nepom.budget.utils.SecondsClock

private val CURRENCY_CONTENT = CurrencyContentV1(CurrencyId(CurrencyCode("RUB")), "рубли", 2, "RUB")

private val ACCOUNT_CONTENT = AccountContentV1(
    AccountId(AccountCode("account")),
    "some account",
    "some description",
    CURRENCY_CONTENT.id,
    AccountKind.BUDGET,
    setOf("tag1", "tag2"),
    42
)

private val TRANSACTION_CONTENT = TransactionContentV1(
    Uuid.generate(),
    SecondsClock.now(),
    "some transaction",
    listOf(
        TransactionContentV1.Item(AccountId(AccountCode("acc1")), 1020),
        TransactionContentV1.Item(AccountId(AccountCode("acc2")), -1020, "cool", true)
    )
)

private val BASE_EVENT: Event<StorableContent> = Event(
    coords = EventCoords(Place("place"), 1),
    created = SecondsClock.now(),
    creator = "Ivan",
    basedOn = listOf(EventCoords(Place("somewhere"), 2), EventCoords(Place("somewhere else"), 3)),
    type = EventType.NEW,
    content = CURRENCY_CONTENT
)

class FileContentSerializationTest {
    @ParameterizedTest
    @MethodSource("fileContentToSerialize")
    fun `serialize and deserialize`(arg: SerdeArg) {
        val json = Json.encodeToString(arg.value)
        println(json)
        arg.jsonCheck(json)
        val actual = Json.decodeFromString<FileContent>(json)
        assertThat(actual).isEqualTo(arg.value)
    }

    data class SerdeArg(val name: String, val value: FileContent, val jsonCheck: (String) -> Unit = {}) {
        constructor(name: String, events: List<Event<StorableContent>>, jsonCheck: (String) -> Unit = {}) :
                this(name, FileContent(events), jsonCheck)

        constructor(name: String, event: Event<StorableContent>, jsonCheck: (String) -> Unit = {}) :
                this(name, FileContent(listOf(event)), jsonCheck)

        override fun toString() = name
    }

    companion object {
        @JvmStatic
        fun fileContentToSerialize() = listOf(
            SerdeArg("currencyV1 one event", BASE_EVENT),
            SerdeArg("accountV1 one event", BASE_EVENT.copy(content = ACCOUNT_CONTENT)),
            SerdeArg("transactionV1 one event", BASE_EVENT.copy(content = TRANSACTION_CONTENT)),
            SerdeArg("two event", listOf(BASE_EVENT, BASE_EVENT.copy(content = TRANSACTION_CONTENT))),
        )
    }
}
