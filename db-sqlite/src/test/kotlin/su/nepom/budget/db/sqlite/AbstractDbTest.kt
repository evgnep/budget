package su.nepom.budget.db.sqlite

import kotlinx.datetime.Instant
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.ktorm.database.Database
import org.ktorm.dsl.deleteAll
import org.ktorm.entity.clear
import org.sqlite.SQLiteDataSource
import su.nepom.budget.Global
import su.nepom.budget.db.Db
import su.nepom.budget.db.sqlite.mapping.AccountRests
import su.nepom.budget.db.sqlite.mapping.Transactions
import su.nepom.budget.db.sqlite.mapping.accounts
import su.nepom.budget.db.sqlite.mapping.currencies
import su.nepom.budget.db.sqlite.mapping.events
import su.nepom.budget.db.sqlite.mapping.properties
import su.nepom.budget.db.sqlite.mapping.simpleObjects
import su.nepom.budget.event.AccountContent
import su.nepom.budget.event.CurrencyContent
import su.nepom.budget.event.Event
import su.nepom.budget.event.SubaccountContent
import su.nepom.budget.event.TransactionContent
import su.nepom.budget.event.TransactionContentItem
import su.nepom.budget.model.AccountCode
import su.nepom.budget.model.AccountId
import su.nepom.budget.model.AccountKind
import su.nepom.budget.model.CurrencyCode
import su.nepom.budget.model.CurrencyId
import su.nepom.budget.model.Place
import su.nepom.budget.model.RawMoney
import su.nepom.budget.model.Uuid
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.time.Duration.Companion.seconds

private val dbPath = Path.of("test-db.sqlite")

internal abstract class AbstractDbTest {
    private var sessionWasOpen = false
    lateinit var db: SqliteDatabase
    val session by lazy {
        sessionWasOpen = true
        db.createSession("AbstractDbTest")
    }
    val eventDao by lazy { session.eventDao }
    val receivedEvents = mutableListOf<Event<*>>()

    @BeforeEach
    fun clearDb() {
        Global.setCurrentUser("user")
        Global.setCurrentPlace(Place("test"))
        val datasource = SQLiteDataSource().apply {
            url = "jdbc:sqlite:$dbPath"
            setEnforceForeignKeys(true)
        }
        val database = Database.connect(datasource)
        database.useTransaction {
            database.deleteAll(Transactions)
            database.deleteAll(AccountRests)
            database.events.clear()
            database.accounts.clear()
            database.currencies.clear()
            database.properties.clear()
            database.simpleObjects.clear()
        }
        db = SqliteDatabase(dbPath)

        db.subscribe(Db.SubscribeKind.ALL) { receivedEvents.addAll(it) }
    }

    @AfterEach
    fun closeDb() {
        if (sessionWasOpen) session.close()
        db.close()
    }

    companion object {
        init {
            dbPath.deleteIfExists()
            SqliteDatabase(dbPath).close()
        }
    }
}

fun createCurrency(code: String, name: String) = CurrencyContent(
    CurrencyId(CurrencyCode(code)),
    name,
    2,
    code,
    false
)

fun createAccount(name: String, currency: CurrencyContent, kind: AccountKind = AccountKind.MONEY) = AccountContent(
    AccountId(AccountCode(name)),
    name,
    "some description",
    currency.id,
    kind,
    setOf("tag1", "tag2"),
    4242
)

fun createSubaccount(account: AccountContent, name: String = "sub", rest: Int = 0, hidden: Boolean = false) =
    SubaccountContent(
        Uuid.generate(),
        account.id,
        name,
        RawMoney(rest),
        hidden
    )

val TIME_MOMENT = Instant.fromEpochSeconds(365*24*60*60*50)

fun createTransaction(
    vararg items: Pair<AccountContent, Int>,
    description: String = "",
    secondsDiff: Int = 0,
    deleted: Boolean = false,
    flag: Boolean = false
) = TransactionContent(
    Uuid.generate(),
    TIME_MOMENT + secondsDiff.seconds,
    description,
    items.map { (account, money) -> TransactionContentItem(account.id, RawMoney(money)) },
    flag = flag,
    deleted = deleted
)