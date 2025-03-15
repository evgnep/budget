package su.nepom.budget.access.ingester

data class Config(
    val accessPath: String,
    val eventStore: String,
    val processedDb: String,
    val currencies: Map<String, CurrencyInfo>,
) {
    data class CurrencyInfo(
        val code: String,
        val name: String,
        val digitsAfterPoint: Int = 2,
        val divider: Int = 1,
    )
}