package su.nepom.budget.db.dao

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import su.nepom.budget.event.TransactionContent
import su.nepom.budget.event.TransactionContextItemAndTransaction
import su.nepom.budget.model.AccountId
import su.nepom.budget.model.CurrencyId
import su.nepom.budget.model.RawMoney
import su.nepom.budget.model.RawTurnover

interface TransactionDao : CrudDao<TransactionContent> {
    override fun getAll(): List<TransactionContent> {
        throw UnsupportedOperationException("getAll is not supported for TransactionDao, use getByQuery instead")
    }

    fun getByQuery(query: Query): List<TransactionContent>

    fun countByFilter(filter: Filter): Int

    fun getItemsByQuery(query: Query): List<TransactionContextItemAndTransaction>

    fun countItemsByFilter(filter: Filter): Int

    fun accountRest(accounts: Set<AccountId>, forDate: Instant? = null): Map<AccountId, RawMoney>

    /**
     * Sum of transaction item amounts reserved past [today] (item.reservedUntil > today), per account.
     * See docs/budget.md.
     */
    fun sumReservedByAccount(accounts: Set<AccountId>, today: LocalDate): Map<AccountId, RawMoney>

    fun accountTurnover(accounts: Set<AccountId>, dateRange: ClosedRange<Instant>? = null): Map<AccountId, RawTurnover>

    fun currencyRest(currencies: Set<CurrencyId>, forDate: Instant? = null): Map<CurrencyId, RawMoney>

    fun currencyTurnover(
        currencies: Set<CurrencyId>,
        dateRange: ClosedRange<Instant>? = null
    ): Map<CurrencyId, RawTurnover>

    data class Filter(
        val from: Instant? = null,
        val to: Instant? = null,
        val accounts: Set<AccountId> = setOf(), // if empty, then no filter
        // you should not use this filter with accounts, those currencies have different digitsAfterPoint
        val amount: AmountFilter? = null,
        val deleted: Boolean? = false,
        val descriptionLike: String? = null,
        val flag: Boolean? = null,
        val currency: CurrencyId? = null,
    )

    data class AmountFilter (
        val min: RawMoney? = null,
        val max: RawMoney? = null,
    )

    data class Query(
        val filter: Filter = Filter(),
        val offset: Int = 0,
        val limit: Int = 100,
        val sortByDateAsc: Boolean = false,
    )
}
