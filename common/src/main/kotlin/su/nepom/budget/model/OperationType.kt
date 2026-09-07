package su.nepom.budget.model

import su.nepom.budget.event.AccountContent
import su.nepom.budget.event.TransactionContentItem
import kotlin.collections.map
import kotlin.math.sign

enum class OperationType {
  INCOME,
  EXPENSE,
  TRANSFER,
  CURRENCY_TRANSFER,
  CURRENCY_EXCHANGE,
  MIXED,
  ;

  companion object {
    fun calculate(
      items: List<TransactionContentItem>,
      accounts: Map<Uuid, ContentHolder<AccountContent>>
    ): OperationType = when (items.size) {
      2 -> {
        val one = items.first()
        val two = items.last()
        val accountOne = accounts[one.account.uuid]?.content
        val accountTwo = accounts[two.account.uuid]?.content
        return when {
          accountOne == null || accountTwo == null || accountOne.currency != accountTwo.currency -> MIXED
          accountOne.kind != accountTwo.kind && one.money.value == two.money.value ->
            if (one.money.value > 0) INCOME else EXPENSE

          accountOne.kind == accountTwo.kind && one.money.value == -two.money.value -> TRANSFER
          else -> MIXED
        }
      }

      4 -> {
        val currencies = items.map { accounts[it.account.uuid]?.content?.currency ?: return MIXED }.distinct()
        if (currencies.size != 2) return MIXED
        val itemsWithCurrencyOne = items.filter { accounts[it.account.uuid]?.content?.currency == currencies.first() }
        val itemsWithCurrencyTwo = items.filter { accounts[it.account.uuid]?.content?.currency == currencies.last() }
        if (itemsWithCurrencyOne.isExchangePart(accounts) && itemsWithCurrencyTwo.isExchangePart(accounts) &&
          itemsWithCurrencyOne.first().money.value.sign != itemsWithCurrencyTwo.first().money.value.sign
        ) CURRENCY_EXCHANGE
        else if (itemsWithCurrencyOne.isCurrencyTransferPart(accounts)
          && itemsWithCurrencyTwo.isCurrencyTransferPart(accounts)
        ) CURRENCY_TRANSFER
        else MIXED
      }

      else -> MIXED
    }

    private fun List<TransactionContentItem>.isExchangePart(accounts: Map<Uuid, ContentHolder<AccountContent>>) =
      size == 2 && first().money.value == last().money.value
              && accounts[first().account.uuid]?.content?.kind != accounts[last().account.uuid]?.content?.kind

    private fun List<TransactionContentItem>.isCurrencyTransferPart(accounts: Map<Uuid, ContentHolder<AccountContent>>) =
      size == 2 && first().money.value == -last().money.value
              && accounts[first().account.uuid]?.content?.kind == AccountKind.BUDGET
              && accounts[last().account.uuid]?.content?.kind == AccountKind.BUDGET
  }
}