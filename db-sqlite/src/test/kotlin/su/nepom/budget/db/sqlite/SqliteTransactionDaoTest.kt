package su.nepom.budget.db.sqlite

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import su.nepom.budget.Global
import su.nepom.budget.db.dao.TransactionDao
import su.nepom.budget.db.dao.TransactionDao.Filter
import su.nepom.budget.db.dao.TransactionDao.Query
import su.nepom.budget.db.model.AccountRest
import kotlinx.datetime.LocalDate
import su.nepom.budget.event.AccountContent
import su.nepom.budget.event.Event
import su.nepom.budget.event.EventType
import su.nepom.budget.event.TransactionContent
import su.nepom.budget.event.TransactionContentItem
import su.nepom.budget.model.AccountId
import su.nepom.budget.model.AccountKind
import su.nepom.budget.model.RawMoney
import su.nepom.budget.model.RawTurnover
import su.nepom.budget.model.Uuid
import su.nepom.budget.model.no
import su.nepom.budget.model.rawMoney
import java.util.function.Consumer
import kotlin.time.Duration.Companion.seconds

private val currency1 = createCurrency("rub", "rubles")
private val currency2 = createCurrency("usd", "dollars")

private val accountC1Money = createAccount("account1C1Money", currency1, AccountKind.MONEY)
private val accountC1Budget = createAccount("account2C1Budget", currency1, AccountKind.BUDGET)
private val account2C1Money = createAccount("account3C1Money", currency1, AccountKind.MONEY)
private val account2C1Budget = createAccount("account4C1Budget", currency1, AccountKind.BUDGET)
private val accountC2Money = createAccount("account5C2Money", currency2, AccountKind.MONEY)
private val accountC2Budget = createAccount("account6C2Budget", currency2, AccountKind.BUDGET)

private val allAccounts: Map<Uuid, AccountContent> =
    listOf(accountC1Money, accountC1Budget, account2C1Money, account2C1Budget, accountC2Money, accountC2Budget)
        .associateBy { it.uuid }

internal class SqliteTransactionDaoTest : AbstractDbTest() {
    private lateinit var dao: TransactionDao

    private var eventNo = 0

    @BeforeEach
    fun setUp() {
        dao = session.transactionDao
        session.save(
            currency1,
            currency2,
            accountC1Money,
            accountC1Budget,
            account2C1Money,
            account2C1Budget,
            accountC2Money,
            accountC2Budget
        )
        session.commit()
        eventNo = session.eventDao.getLastEventCoords()[Global.currentPlace]!!
        receivedEvents.clear()
    }

    @Test
    fun `sumReservedByAccount only counts items reserved past today`() {
        val reservedTx = TransactionContent(
            Uuid.generate(),
            TIME_MOMENT,
            "reserved",
            listOf(
                TransactionContentItem(accountC1Money.id, RawMoney(1000)),
                TransactionContentItem(accountC1Budget.id, RawMoney(1000), reservedUntil = LocalDate(2026, 9, 10)),
            ),
        )
        val plainTx = TransactionContent(
            Uuid.generate(),
            TIME_MOMENT,
            "plain",
            listOf(
                TransactionContentItem(accountC1Money.id, RawMoney(500)),
                TransactionContentItem(accountC1Budget.id, RawMoney(500)),
            ),
        )
        session.save(reservedTx, plainTx)
        session.commit()
        // then
        assertThat(dao.sumReservedByAccount(setOf(accountC1Budget.id), LocalDate(2026, 9, 1)))
            .containsExactlyInAnyOrderEntriesOf(mapOf(accountC1Budget.id to RawMoney(1000)))
        // strict comparison: on the reserved date it no longer counts
        assertThat(dao.sumReservedByAccount(setOf(accountC1Budget.id), LocalDate(2026, 9, 10))).isEmpty()
    }

    @MethodSource
    @ParameterizedTest(name = "{0}")
    fun saveNewValid(name: String, transaction: TransactionContent) {
        session.save(transaction)
        session.commit()
        // then
        assertThat(dao.getById(transaction.id))
            .usingRecursiveComparison()
            .ignoringFields("items.account.readable")
            .isEqualTo(transaction)
        assertThat(dao.count()).isEqualTo(1)
        assertThat(eventDao.getLastEventCoords())
            .containsExactlyInAnyOrderEntriesOf(mapOf(Global.currentPlace to eventNo + 1))
        assertThat(eventDao.getEventsForSourceFrom(Global.currentPlace, eventNo + 1))
            .singleElement()
            .satisfies(Consumer {
                assertThat(it.coords).isEqualTo(Global.currentPlace no eventNo + 1)
                assertThat(it.creator).isEqualTo(Global.currentUser)
                assertThat(it.type).isEqualTo(EventType.NEW)
                assertThat(it.basedOn).isEmpty()
                assertThat(it.content).isEqualTo(transaction)
            })
    }

    @MethodSource
    @ParameterizedTest(name = "{0}")
    fun saveInvalid(name: String, transaction: TransactionContent) {
        assertThatThrownBy { session.save(transaction) }.satisfies(Consumer {
            println(it.message)
        })
    }

    @MethodSource
    @ParameterizedTest
    fun update(args: UpdateArgs) {
        val new = args.new.copy(id = args.old.id)
        session.save(args.old)
        session.commit()
        receivedEvents.clear()
        // when
        session.save(new)
        session.commit()
        // then
        assertThat(dao.count()).isEqualTo(1)
        assertThat(dao.getById(new.id))
            .usingRecursiveComparison()
            .ignoringFields("items.account.readable")
            .isEqualTo(new)
        assertThat(eventDao.getLastEventCoords())
            .containsExactlyInAnyOrderEntriesOf(mapOf(Global.currentPlace to eventNo + 2))
        assertThat(dao.accountRest(setOf())).containsExactlyInAnyOrderEntriesOf(
            args.rests.associate { AccountId(it.first.uuid) to RawMoney(it.second) }
        )
        assertThat(receivedEvents.filter { it.content is AccountRest }).satisfiesExactlyInAnyOrder(
            *args.rests.map {
                Consumer<Event<*>> { event ->
                    val content = event.content as AccountRest
                    assertThat(content.id).isEqualTo(AccountId(it.first.uuid))
                    assertThat(content.rest).isEqualTo(it.second.rawMoney)
                }
            }.toTypedArray()
        )
    }

    data class UpdateArgs(
        val name: String,
        val old: TransactionContent,
        val new: TransactionContent,
        val rests: List<Pair<AccountContent, Int>>
    ) {
        constructor(
            name: String,
            old: TransactionContent,
            new: TransactionContent,
            vararg rest: Pair<AccountContent, Int>
        ) : this(name, old, new, rest.toList())

        override fun toString(): String = name
    }

    @Nested
    inner class StatisticTest {
        private val items = arrayOf(
            accountC1Money to 10, account2C1Budget to 3, accountC1Budget to 7,
            accountC2Money to -4, accountC2Budget to -4
        )

        @BeforeEach
        fun createTransactions() {
            repeat(50) {
                session.save(createTransaction(*items, secondsDiff = -it * 2))
            }
            session.save(createTransaction(*items, secondsDiff = -200, deleted = true))
            session.commit()
        }

        @Test
        fun accountRest() {
            assertThat(dao.accountRest(setOf())).containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    accountC1Money.id to (10 * 50).rawMoney,
                    account2C1Budget.id to (3 * 50).rawMoney,
                    accountC1Budget.id to (7 * 50).rawMoney,
                    accountC2Money.id to (-4 * 50).rawMoney,
                    accountC2Budget.id to (-4 * 50).rawMoney,
                )
            )

            assertThat(
                dao.accountRest(
                    setOf(
                        account2C1Budget.id,
                        account2C1Money.id
                    )
                )
            ).containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    account2C1Budget.id to (3 * 50).rawMoney,
                )
            )

            assertThat(dao.accountRest(setOf(), TIME_MOMENT + 100.seconds)).containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    accountC1Money.id to (10 * 50).rawMoney,
                    account2C1Budget.id to (3 * 50).rawMoney,
                    accountC1Budget.id to (7 * 50).rawMoney,
                    accountC2Money.id to (-4 * 50).rawMoney,
                    accountC2Budget.id to (-4 * 50).rawMoney,
                )
            )

            assertThat(dao.accountRest(setOf(), TIME_MOMENT - 20.seconds)).containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    accountC1Money.id to (10 * 40).rawMoney,
                    account2C1Budget.id to (3 * 40).rawMoney,
                    accountC1Budget.id to (7 * 40).rawMoney,
                    accountC2Money.id to (-4 * 40).rawMoney,
                    accountC2Budget.id to (-4 * 40).rawMoney,
                )
            )

            assertThat(
                dao.accountRest(
                    setOf(accountC1Money.id, accountC1Budget.id),
                    TIME_MOMENT - 20.seconds
                )
            ).containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    accountC1Money.id to (10 * 40).rawMoney,
                    accountC1Budget.id to (7 * 40).rawMoney,
                )
            )
        }

        @Test
        fun accountTurnover() {
            assertThat(dao.accountTurnover(setOf())).containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    accountC1Money.id to RawTurnover.fromInt(10 * 50, 0),
                    account2C1Budget.id to RawTurnover.fromInt(3 * 50, 0),
                    accountC1Budget.id to RawTurnover.fromInt(7 * 50, 0),
                    accountC2Money.id to RawTurnover.fromInt(0, -4 * 50),
                    accountC2Budget.id to RawTurnover.fromInt(0, -4 * 50),
                )
            )

            assertThat(
                dao.accountTurnover(setOf(account2C1Budget.id, account2C1Money.id))
            ).containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    account2C1Budget.id to RawTurnover.fromInt(3 * 50, 0),
                )
            )

            assertThat(
                dao.accountTurnover(
                    setOf(accountC1Money.id, account2C1Budget.id),
                    TIME_MOMENT - 30.seconds..TIME_MOMENT - 20.seconds
                )
            ).containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    accountC1Money.id to RawTurnover.fromInt(10 * 6, 0),
                    account2C1Budget.id to RawTurnover.fromInt(3 * 6, 0),
                )
            )
        }

        @Test
        fun accountRestInTransaction() {
            session.save(createTransaction(*items, secondsDiff = -150))

            assertThat(dao.accountRest(setOf())).containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    accountC1Money.id to (10 * 51).rawMoney,
                    account2C1Budget.id to (3 * 51).rawMoney,
                    accountC1Budget.id to (7 * 51).rawMoney,
                    accountC2Money.id to (-4 * 51).rawMoney,
                    accountC2Budget.id to (-4 * 51).rawMoney,
                )
            )

            assertThat(dao.accountRest(setOf(), TIME_MOMENT - 20.seconds)).containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    accountC1Money.id to (10 * 41).rawMoney,
                    account2C1Budget.id to (3 * 41).rawMoney,
                    accountC1Budget.id to (7 * 41).rawMoney,
                    accountC2Money.id to (-4 * 41).rawMoney,
                    accountC2Budget.id to (-4 * 41).rawMoney,
                )
            )

            session.rollback()

            assertThat(dao.accountRest(setOf())).containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    accountC1Money.id to (10 * 50).rawMoney,
                    account2C1Budget.id to (3 * 50).rawMoney,
                    accountC1Budget.id to (7 * 50).rawMoney,
                    accountC2Money.id to (-4 * 50).rawMoney,
                    accountC2Budget.id to (-4 * 50).rawMoney,
                )
            )

            assertThat(dao.accountRest(setOf(), TIME_MOMENT - 20.seconds)).containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    accountC1Money.id to (10 * 40).rawMoney,
                    account2C1Budget.id to (3 * 40).rawMoney,
                    accountC1Budget.id to (7 * 40).rawMoney,
                    accountC2Money.id to (-4 * 40).rawMoney,
                    accountC2Budget.id to (-4 * 40).rawMoney,
                )
            )
        }

        @Test
        fun currencyRest() {
            assertThat(dao.currencyRest(setOf())).containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    currency1.id to (10 * 50).rawMoney,
                    currency2.id to (-4 * 50).rawMoney,
                )
            )

            assertThat(dao.currencyRest(setOf(currency2.id))).containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    currency2.id to (-4 * 50).rawMoney,
                )
            )

            assertThat(dao.currencyRest(setOf(), TIME_MOMENT + 100.seconds)).containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    currency1.id to (10 * 50).rawMoney,
                    currency2.id to (-4 * 50).rawMoney,
                )
            )

            assertThat(dao.currencyRest(setOf(), TIME_MOMENT - 20.seconds)).containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    currency1.id to (10 * 40).rawMoney,
                    currency2.id to (-4 * 40).rawMoney,
                )
            )

            assertThat(
                dao.currencyRest(setOf(currency1.id), TIME_MOMENT - 20.seconds)
            ).containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    currency1.id to (10 * 40).rawMoney,
                )
            )
        }

        @Test
        fun currencyTurnover() {
            assertThat(dao.currencyTurnover(setOf())).containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    currency1.id to RawTurnover.fromInt(10 * 50, 0),
                    currency2.id to RawTurnover.fromInt(0, -4 * 50),
                )
            )

            assertThat(dao.currencyTurnover(setOf(currency1.id))).containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    currency1.id to RawTurnover.fromInt(10 * 50, 0),
                )
            )

            assertThat(
                dao.currencyTurnover(setOf(currency2.id), TIME_MOMENT - 30.seconds..TIME_MOMENT - 20.seconds)
            ).containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    currency2.id to RawTurnover.fromInt(0, -4 * 6),
                )
            )
        }
    }

    @Nested
    inner class FilteringAndPagination {
        @BeforeEach
        fun createTransactions() {
            fun saveTransaction(
                description: String,
                secondsDiff: Int,
                deleted: Boolean,
                flag: Boolean,
                sum: Int,
                vararg items: AccountContent
            ) {
                session.save(
                    createTransaction(
                        *items.map { it to sum }.toTypedArray(),
                        description = description + " ${secondsDiff}s",
                        secondsDiff = secondsDiff,
                        deleted = deleted,
                        flag = flag
                    )
                )
            }

            fun saveTransaction(
                description: String,
                secondsDiff: Int,
                vararg items: AccountContent
            ) = saveTransaction(description, secondsDiff, false, false, 10, *items)

            saveTransaction("some", 10, accountC1Money, account2C1Budget)
            saveTransaction("some", 11, accountC1Money, accountC1Budget)
            saveTransaction("other some", 12, accountC1Money, account2C1Budget)
            saveTransaction("deleted", 13, true, false, 11, accountC1Money, account2C1Budget)
            saveTransaction("flagged", 14, false, true, 12, accountC1Money, account2C1Budget)
        }

        fun testGetAndCountTr(query: Query, expectedCount: Int, vararg expected: Int) {
            val actual = dao.getByQuery(query).map { (it.date - TIME_MOMENT).inWholeSeconds.toInt() }
            assertThat(actual).isEqualTo(expected.toList())
            val actualCount = dao.countByFilter(query.filter)
            assertThat(actualCount).isEqualTo(expectedCount)
        }

        fun testGetAndCountItem(query: Query, expectedCount: Int, vararg expected: Pair<Int, AccountContent>) {
            val actualRows = dao.getItemsByQuery(query)
            val actual = actualRows
                .map { (it.transaction.date - TIME_MOMENT).inWholeSeconds.toInt() to
                        allAccounts[it.item.account.uuid]?.name }
            assertThat(actual).isEqualTo(expected.map { it.first to it.second.name })
            // a row starts a new group whenever the transaction (its date, unique in these tests) changes
            val expectedIsFirstInGroup = expected.mapIndexed { index, (secondsDiff, _) ->
                index == 0 || expected[index - 1].first != secondsDiff
            }
            assertThat(actualRows.map { it.isFirstInGroup }).isEqualTo(expectedIsFirstInGroup)
            val actualCount = dao.countItemsByFilter(query.filter)
            assertThat(actualCount).isEqualTo(expectedCount)
        }

        @TestFactory
        fun transactions() = listOf(
            dynamicTest("default") { testGetAndCountTr(Query(), 4, 14, 12, 11, 10) },
            dynamicTest("from to") {
                testGetAndCountTr(
                    Query(Filter(from = TIME_MOMENT + 11.seconds, to = TIME_MOMENT + 13.seconds)),
                    2,
                    12, 11
                )
            },
            dynamicTest("account1") {
                testGetAndCountTr(Query(Filter(accounts = setOf(accountC1Budget.id))), 1, 11)
            },
            dynamicTest("account2") {
                testGetAndCountTr(
                    Query(Filter(accounts = setOf(accountC1Money.id, accountC1Budget.id))),
                    4,
                    14, 12, 11, 10
                )
            },
            dynamicTest("with deleted") {
                testGetAndCountTr(Query(Filter(deleted = null)), 5, 14, 13, 12, 11, 10)
            },
            dynamicTest("deleted only") {
                testGetAndCountTr(Query(Filter(deleted = true)), 1, 13)
            },
            dynamicTest("flagged") {
                testGetAndCountTr(Query(Filter(flag = true)), 1, 14)
            },
            dynamicTest(">=11") {
                testGetAndCountTr(Query(Filter(deleted = null, amount = TransactionDao.AmountFilter(11.rawMoney, null))),
                    2, 14, 13)
            },
            dynamicTest("<=10") {
                testGetAndCountTr(Query(Filter(deleted = null, amount = TransactionDao.AmountFilter(null, 10.rawMoney))),
                    3, 12, 11, 10)
            },
            dynamicTest(">=11 and <=11") {
                testGetAndCountTr(Query(Filter(deleted = null, amount = TransactionDao.AmountFilter(11.rawMoney, 11.rawMoney))),
                    1, 13)
            },
            dynamicTest("sort asc") {
                testGetAndCountTr(Query(sortByDateAsc = true), 4, 10, 11, 12, 14)
            },
            dynamicTest("page1") { testGetAndCountTr(Query(limit = 2), 4, 14, 12) },
            dynamicTest("page2") { testGetAndCountTr(Query(offset = 2, limit = 1), 4, 11) },
        )

        @TestFactory
        fun transactionItems() = listOf(
            dynamicTest("default") {
                testGetAndCountItem(Query(), 8,
                    14 to accountC1Money, 14 to account2C1Budget,
                    12 to accountC1Money, 12 to account2C1Budget,
                    11 to accountC1Money, 11 to accountC1Budget,
                    10 to accountC1Money, 10 to account2C1Budget) },
            dynamicTest("from to") {
                testGetAndCountItem(
                    Query(Filter(from = TIME_MOMENT + 11.seconds, to = TIME_MOMENT + 13.seconds)),
                    4,
                    12 to accountC1Money, 12 to account2C1Budget,
                    11 to accountC1Money, 11 to accountC1Budget,
                )
            },
            dynamicTest("account1") {
                testGetAndCountItem(Query(Filter(accounts = setOf(accountC1Budget.id))), 1,
                    11 to accountC1Budget)
            },
            dynamicTest("account2") {
                testGetAndCountItem(
                    Query(Filter(accounts = setOf(accountC1Money.id, accountC1Budget.id))),
                    5,
                    14 to accountC1Money,
                    12 to accountC1Money,
                    11 to accountC1Money, 11 to accountC1Budget,
                    10 to accountC1Money,
                )
            },
            dynamicTest("with deleted") {
                testGetAndCountItem(Query(Filter(deleted = null, accounts = setOf(account2C1Budget.id))), 4,
                    14 to account2C1Budget,
                    13 to account2C1Budget,
                    12 to account2C1Budget,
                    10 to account2C1Budget)
            },
            dynamicTest("deleted only") {
                testGetAndCountItem(Query(Filter(deleted = true)), 2,
                    13 to accountC1Money, 13 to account2C1Budget)
            },
            dynamicTest("flagged") {
                testGetAndCountItem(Query(Filter(flag = true)), 2,
                    14 to accountC1Money, 14 to account2C1Budget)
            },
            dynamicTest("sort asc") {
                testGetAndCountItem(Query(Filter(flag = false), sortByDateAsc = true), 6,
                    10 to accountC1Money, 10 to account2C1Budget,
                    11 to accountC1Money, 11 to accountC1Budget,
                    12 to accountC1Money, 12 to account2C1Budget,)
            },
            dynamicTest(">=11") {
                testGetAndCountItem(
                    Query(Filter(deleted = null, amount = TransactionDao.AmountFilter(11.rawMoney, null))), 4,
                    14 to accountC1Money, 14 to account2C1Budget,
                    13 to accountC1Money, 13 to account2C1Budget,
                )
            },
            dynamicTest("<=10") {
                testGetAndCountItem(
                    Query(Filter(deleted = null, amount = TransactionDao.AmountFilter(null, 10.rawMoney))), 6,
                    12 to accountC1Money, 12 to account2C1Budget,
                    11 to accountC1Money, 11 to accountC1Budget,
                    10 to accountC1Money, 10 to account2C1Budget,
                )
            },
            dynamicTest(">=11 and <=11") {
                testGetAndCountItem(
                    Query(Filter(deleted = null, amount = TransactionDao.AmountFilter(11.rawMoney, 11.rawMoney))), 2,
                    13 to accountC1Money, 13 to account2C1Budget,
                )
            },
            dynamicTest("page1") {
                testGetAndCountItem(Query(limit = 2), 8,
                    14 to accountC1Money, 14 to account2C1Budget,) },
            dynamicTest("page2") {
                testGetAndCountItem(Query(offset = 2, limit = 1), 8, 12 to accountC1Money) },
        )
    }

    companion object {
        @JvmStatic
        fun saveNewValid() = listOf(
            Arguments.of("zero", createTransaction(accountC1Money to 0)),
            Arguments.of("budget income", createTransaction(accountC1Money to 10, account2C1Budget to 10)),
            Arguments.of("budget expenditure", createTransaction(accountC1Money to -10, account2C1Budget to -10)),
            Arguments.of(
                "budget complex",
                createTransaction(accountC1Money to 10, account2C1Budget to 9, accountC1Budget to 1)
            ),
            Arguments.of("transfer money", createTransaction(accountC1Money to 10, account2C1Money to -10)),
            Arguments.of("transfer budget", createTransaction(accountC1Budget to 10, account2C1Budget to -10)),
            Arguments.of(
                "exchange",
                createTransaction(
                    accountC1Money to -10,
                    accountC1Budget to -10,
                    accountC2Money to 100,
                    accountC2Budget to 100
                )
            ),
        )

        @JvmStatic
        fun saveInvalid() = listOf(
            Arguments.of("budget", createTransaction(accountC1Money to 10, account2C1Budget to 9)),
            Arguments.of("transfer money", createTransaction(accountC1Money to 10, account2C1Money to -9)),
            Arguments.of("transfer budget", createTransaction(accountC1Budget to 10, account2C1Budget to -9)),
            Arguments.of(
                "exchange",
                createTransaction(
                    accountC1Money to -10,
                    accountC1Budget to -9,
                    accountC2Money to 100,
                    accountC2Budget to 100
                )
            ),
        )

        @JvmStatic
        fun update() = listOf(
            UpdateArgs(
                "transaction properties",
                createTransaction(accountC1Money to 10, account2C1Budget to 10),
                createTransaction(accountC1Money to 10, account2C1Budget to 10, description = "xxx", flag = true),
                accountC1Money to 10, account2C1Budget to 10
            ),
            UpdateArgs(
                "change values",
                createTransaction(accountC1Money to 10, account2C1Budget to 10),
                createTransaction(accountC1Money to 20, account2C1Budget to 20),
                accountC1Money to 20, account2C1Budget to 20
            ),
            UpdateArgs(
                "change accounts",
                createTransaction(accountC1Money to 10, account2C1Budget to 10),
                createTransaction(account2C1Money to 10, accountC1Budget to 10),
                accountC1Money to 0, account2C1Budget to 0, account2C1Money to 10, accountC1Budget to 10
            ),
        )
    }
}