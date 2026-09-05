package su.nepom.budget.event

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.Uuid
import java.time.LocalDate

const val BASE_CURRENCY_CODE = "usd"

@Serializable
@SerialName("CurrenciesExchangeRatesContentV1")
data class CurrenciesExchangeRatesContentV1(
    override val id: Uuid,
    // rate of BASE_CURRENCY_CODE to 1 unit of currency
    val rates: Map<String, Double>,
    override val isHidden: Boolean = false,
): StorableContent, ActualVersionContent {
    override val objectKind: ObjectKind get() = ObjectKind.CURRENCY_EXCHANGE_RATE

    val date: LocalDate get() = LocalDate.parse(id.id)

}

/**
 * Actual version of Currencies exchange rates content
 */
typealias CurrenciesExchangeRatesContent = CurrenciesExchangeRatesContentV1