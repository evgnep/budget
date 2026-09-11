package su.nepom.budget.desktop.ui.money

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Inject
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.collections.ListChangeListener
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.chart.LineChart
import javafx.scene.chart.XYChart
import javafx.scene.control.Alert
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.SelectionMode
import javafx.scene.control.SplitPane
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.stage.Stage
import org.controlsfx.control.CheckComboBox
import org.controlsfx.control.MaskerPane
import su.nepom.budget.db.Db
import su.nepom.budget.db.Session
import su.nepom.budget.desktop.service.AccountService
import su.nepom.budget.desktop.service.CurrencyService
import su.nepom.budget.desktop.service.DbService
import su.nepom.budget.desktop.service.WindowStateService
import su.nepom.budget.desktop.util.fx.Controller
import su.nepom.budget.desktop.util.fx.Disposable
import su.nepom.budget.desktop.util.fx.StageAwareController
import su.nepom.budget.desktop.util.fx.WeakListeners
import su.nepom.budget.desktop.util.fx.enableCopySelectionToClipboard
import su.nepom.budget.desktop.util.fx.setClipboardValue
import su.nepom.budget.desktop.util.toEndOfDayInstant
import su.nepom.budget.event.CurrenciesExchangeRatesContent
import su.nepom.budget.model.AccountId
import su.nepom.budget.model.AccountKind
import su.nepom.budget.model.CurrencyId
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.RawMoney
import su.nepom.budget.utils.format
import su.nepom.budget.utils.toBigDecimal
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URL
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.ResourceBundle
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger { }

@Suppress("unused")
class MoneyController @Inject constructor(
    private val dbService: DbService,
    private val accountService: AccountService,
    private val currencyService: CurrencyService,
    private val windowStateService: WindowStateService,
) : Controller, Initializable, StageAwareController, Disposable {

    // plain snapshots of the account/currency data actually needed for the calculation - taken on the
    // FX thread so the background thread never touches the live JavaFX observables directly
    private class AccountSnapshot(val id: AccountId, val currency: CurrencyId, val tags: Set<String>, val kind: AccountKind)
    private class CurrencySnapshot(val name: String, val officialCode: String, val digitsAfterPoint: Int)

    private class CurrencyStats(
        val restRaw: RawMoney,
        val restUsd: Double,
        val changeUsd: Double,
        val percent: Double,
    )

    private class MoneyRow(
        val date: LocalDate,
        val totalUsd: Double,
        val totalChangeUsd: Double,
        val byCurrency: Map<CurrencyId, CurrencyStats>,
    )

    private class ComputationResult(val usedCurrencies: List<CurrencyId>, val rows: List<MoneyRow>)

    private val weakListeners = WeakListeners()
    private val rowsList = FXCollections.observableArrayList<MoneyRow>()

    // the calculation touches a Session, which is thread-bound - it always runs on this single
    // background thread so a dedicated Session can be safely bound to it
    private val calcExecutor = Executors.newSingleThreadExecutor { Thread(it, "money-calc").apply { isDaemon = true } }
    private var bgDb: Db? = null
    private var bgSession: Session? = null
    private val computationId = AtomicLong(0)

    @FXML private lateinit var fromYearCombo: ComboBox<Int>
    @FXML private lateinit var toYearCombo: ComboBox<Int>
    @FXML private lateinit var byYearsCheckBox: CheckBox
    @FXML private lateinit var excludeTagsCheckComboBox: CheckComboBox<String>
    @FXML private lateinit var chartSplitter: SplitPane
    @FXML private lateinit var table: TableView<MoneyRow>
    @FXML private lateinit var chart: LineChart<String, Number>
    @FXML private lateinit var maskerPane: MaskerPane

    override fun initialize(location: URL?, resources: ResourceBundle?) {
        val currentYear = LocalDate.now().year
        val years = (currentYear downTo currentYear - 20).toList()
        fromYearCombo.items.setAll(years)
        toYearCombo.items.setAll(years)

        fromYearCombo.valueProperty().addListener { _, _, _ -> reload() }
        toYearCombo.valueProperty().addListener { _, _, _ -> reload() }
        byYearsCheckBox.selectedProperty().addListener { _, _, _ -> reload() }

        excludeTagsCheckComboBox.items.setAll(accountService.tags)
        // accountService.tags outlives this controller (it's a singleton-scoped list) - a plain
        // listener here would keep this whole window reachable forever after it closes, so route
        // it through weakListeners like the DB subscription below
        accountService.tags.addListener(weakListeners(ListChangeListener { excludeTagsCheckComboBox.items.setAll(accountService.tags) }))
        excludeTagsCheckComboBox.checkModel.checkedItems.addListener(ListChangeListener { reload() })

        table.items = rowsList
        table.selectionModel.selectionMode = SelectionMode.MULTIPLE
        table.enableCopySelectionToClipboard()

        weakListeners.addListenerAndCallNow(dbService.sessionProperty) { _, _, session ->
            if (session != null) {
                weakListeners.subscribe(
                    session.db,
                    Db.SubscribeKind.TRANSACTION, Db.SubscribeKind.ACCOUNT, Db.SubscribeKind.SIMPLE_OBJECT
                ) {
                    Platform.runLater { reload() }
                }
            }
            reload()
        }
    }

    override fun initialize(stage: Stage) {
        windowStateService.bindSplitPane(stage, NAME, chartSplitter)
    }

    override fun dispose() {
        weakListeners.dispose()
        computationId.incrementAndGet()
        calcExecutor.execute { bgSession?.close() }
        calcExecutor.shutdown()
    }

    // start of each month in the range (or start of each year if "По годам" is checked), never in the future
    private fun periodDates(fromYear: Int, toYear: Int, byYears: Boolean): List<LocalDate> {
        val today = LocalDate.now()
        val start = minOf(fromYear, toYear)
        val end = maxOf(fromYear, toYear)
        val dates = mutableListOf<LocalDate>()
        for (year in start..end) {
            if (byYears) {
                val date = LocalDate.of(year, 1, 1)
                if (!date.isAfter(today)) dates += date
            } else {
                for (month in 1..12) {
                    val date = LocalDate.of(year, month, 1)
                    if (!date.isAfter(today)) dates += date
                }
            }
        }
        return dates
    }

    // cheap, FX-thread-only part: reads UI state and schedules the actual (possibly slow) calculation
    // in the background, cancelling whatever calculation is still running for a previous filter
    private fun reload() {
        val myId = computationId.incrementAndGet()
        val db = dbService.db
        if (db == null) {
            table.columns.clear()
            rowsList.clear()
            chart.data.clear()
            maskerPane.isVisible = false
            return
        }

        val today = LocalDate.now()
        val fromYear = fromYearCombo.value ?: today.year
        val toYear = toYearCombo.value ?: today.year
        val byYears = byYearsCheckBox.isSelected
        val dates = periodDates(fromYear, toYear, byYears)
        val excludedTags = excludeTagsCheckComboBox.checkModel.checkedItems.toSet()

        if (dates.isEmpty()) {
            table.columns.clear()
            rowsList.clear()
            chart.data.clear()
            maskerPane.isVisible = false
            return
        }

        val accounts = accountService.accounts.map {
            AccountSnapshot(it.content.id, it.content.currency, it.content.tags, it.content.kind)
        }
        val currencies: Map<CurrencyId, CurrencySnapshot> = currencyService.currencies.associate {
            it.content.id to CurrencySnapshot(it.content.name, it.content.officialCode, it.content.digitsAfterPoint)
        }

        maskerPane.isVisible = true
        calcExecutor.execute {
            val result = runCatching { compute(db, dates, excludedTags, accounts, currencies, myId) }
            if (computationId.get() != myId) return@execute
            Platform.runLater {
                if (computationId.get() != myId) return@runLater
                maskerPane.isVisible = false
                result.onSuccess { computed ->
                    if (computed != null) applyResult(computed, byYears, currencies)
                }.onFailure {
                    logger.error(it) { "Money calculation failed" }
                    Alert(Alert.AlertType.ERROR, "Проблемы: " + it.message, ButtonType.OK).showAndWait()
                }
            }
        }
    }

    // runs on calcExecutor's single background thread; returns null if superseded by a newer reload()
    private fun compute(
        db: Db,
        dates: List<LocalDate>,
        excludedTags: Set<String>,
        accounts: List<AccountSnapshot>,
        currencies: Map<CurrencyId, CurrencySnapshot>,
        myId: Long,
    ): ComputationResult? {
        if (bgDb !== db) {
            bgSession?.close()
            bgSession = db.createSession("money-calc", createEvents = false, autoCommit = true)
            bgDb = db
        }
        val session = bgSession!!

        val moneyAccounts = accounts.filter {
            it.kind == AccountKind.MONEY && (excludedTags.isEmpty() || it.tags.none { tag -> tag in excludedTags })
        }
        if (moneyAccounts.isEmpty()) return ComputationResult(emptyList(), emptyList())
        val accountIds = moneyAccounts.mapTo(mutableSetOf()) { it.id }
        val accountsByCurrency: Map<CurrencyId, List<AccountId>> =
            moneyAccounts.groupBy({ it.currency }, { it.id })

        // budget accounts carrying an excluded tag - their rest is netted out of the money accounts' rest
        val subtractAccounts = if (excludedTags.isEmpty()) emptyList()
        else accounts.filter { it.kind == AccountKind.BUDGET && it.tags.any { tag -> tag in excludedTags } }
        val subtractAccountsByCurrency: Map<CurrencyId, List<AccountId>> =
            subtractAccounts.groupBy({ it.currency }, { it.id })
        val subtractAccountIds = subtractAccounts.mapTo(mutableSetOf()) { it.id }

        val allRates = session.simpleObjectDao<CurrenciesExchangeRatesContent>(ObjectKind.CURRENCY_EXCHANGE_RATE)
            .getAll(withHidden = true)
            .sortedBy { it.date }

        fun rateFor(date: LocalDate): CurrenciesExchangeRatesContent? =
            allRates.lastOrNull { !it.date.isAfter(date) }

        // rests per currency for every date in the period
        val restByDate = dates.map { date ->
            if (computationId.get() != myId) return null
            val forDate = date.toEndOfDayInstant()
            val restMap = session.transactionDao.accountRest(accountIds + subtractAccountIds, forDate)
            val restByCurrency = accountsByCurrency.mapValues { (currencyId, ids) ->
                val moneyRest = ids.sumOf { restMap[it]?.value ?: 0L }
                val subtractRest = subtractAccountsByCurrency[currencyId]?.sumOf { restMap[it]?.value ?: 0L } ?: 0L
                RawMoney(moneyRest - subtractRest)
            }
            Triple(date, restByCurrency, rateFor(date))
        }
        if (computationId.get() != myId) return null

        val usedCurrencies = accountsByCurrency.keys
            .filter { currencyId -> restByDate.any { (_, restByCurrency, _) -> (restByCurrency[currencyId]?.value ?: 0L) != 0L } }
            .sortedBy { currencies[it]?.name?.lowercase() ?: "" }

        var prevTotal = 0.0
        val prevCurrencyUsd = mutableMapOf<CurrencyId, Double>()
        var first = true
        val moneyRows = restByDate.map { (date, restByCurrency, rates) ->
            val usdByCurrency = usedCurrencies.associateWith { currencyId ->
                usdValue(restByCurrency[currencyId] ?: RawMoney.ZERO, currencies[currencyId], rates)
            }
            val totalUsd = usdByCurrency.values.sum()
            val stats = usedCurrencies.associateWith { currencyId ->
                val usd = usdByCurrency.getValue(currencyId)
                val prevUsd = prevCurrencyUsd[currencyId] ?: usd
                val percent = if (totalUsd != 0.0) usd / totalUsd * 100.0 else 0.0
                CurrencyStats(
                    restRaw = restByCurrency[currencyId] ?: RawMoney.ZERO,
                    restUsd = usd,
                    changeUsd = if (first) 0.0 else usd - prevUsd,
                    percent = percent,
                )
            }
            usedCurrencies.forEach { currencyId -> prevCurrencyUsd[currencyId] = usdByCurrency.getValue(currencyId) }
            val totalChange = if (first) 0.0 else totalUsd - prevTotal
            prevTotal = totalUsd
            first = false
            MoneyRow(date, totalUsd, totalChange, stats)
        }

        return ComputationResult(usedCurrencies, moneyRows)
    }

    private fun usdValue(raw: RawMoney, currency: CurrencySnapshot?, rates: CurrenciesExchangeRatesContent?): Double {
        if (raw.value == 0L || currency == null || rates == null) return 0.0
        val rate = rates.rates[currency.officialCode.lowercase()] ?: return 0.0
        if (rate == 0.0) return 0.0
        return raw.toBigDecimal(currency.digitsAfterPoint).toDouble() / rate
    }

    private fun applyResult(result: ComputationResult, byYears: Boolean, currencies: Map<CurrencyId, CurrencySnapshot>) {
        table.columns.clear()
        setupColumns(result.usedCurrencies, byYears, currencies)
        rowsList.setAll(result.rows.asReversed())
        chart.data.clear()
        setupChart(result.rows, result.usedCurrencies, byYears, currencies)
    }

    private fun setupColumns(usedCurrencies: List<CurrencyId>, byYears: Boolean, currencies: Map<CurrencyId, CurrencySnapshot>) {
        val dateFormat = DateTimeFormatter.ofPattern(if (byYears) "yyyy" else "yyyy-MM")

        val dateColumn = TableColumn<MoneyRow, String>("Дата").apply {
            isSortable = false
            setCellValueFactory { SimpleStringProperty(it.value.date.format(dateFormat)) }
        }
        val totalColumn = TableColumn<MoneyRow, String>("Остаток \$").apply {
            isSortable = false
            setCellValueFactory { SimpleStringProperty(formatUsd(it.value.totalUsd)) }
            setClipboardValue { formatNumberForClipboard(it.totalUsd) }
        }
        val totalChangeColumn = TableColumn<MoneyRow, String>("Изменение \$").apply {
            isSortable = false
            setCellValueFactory { SimpleStringProperty(formatUsd(it.value.totalChangeUsd)) }
            setClipboardValue { formatNumberForClipboard(it.totalChangeUsd) }
        }
        table.columns.addAll(dateColumn, totalColumn, totalChangeColumn)

        for (currencyId in usedCurrencies) {
            val currency = currencies[currencyId]
            val digits = currency?.digitsAfterPoint ?: 2
            val group = TableColumn<MoneyRow, String>(currency?.name ?: "-").apply { isSortable = false }
            val restColumn = TableColumn<MoneyRow, String>("Остаток").apply {
                isSortable = false
                setCellValueFactory { SimpleStringProperty((it.value.byCurrency[currencyId]?.restRaw ?: RawMoney.ZERO).format(digits)) }
                setClipboardValue { (it.byCurrency[currencyId]?.restRaw ?: RawMoney.ZERO).toBigDecimal(digits).toPlainString().replace('.', decimalSeparator) }
            }
            val restUsdColumn = TableColumn<MoneyRow, String>("Остаток \$").apply {
                isSortable = false
                setCellValueFactory { SimpleStringProperty(formatUsd(it.value.byCurrency[currencyId]?.restUsd ?: 0.0)) }
                setClipboardValue { formatNumberForClipboard(it.byCurrency[currencyId]?.restUsd ?: 0.0) }
            }
            val changeUsdColumn = TableColumn<MoneyRow, String>("Изменение \$").apply {
                isSortable = false
                setCellValueFactory { SimpleStringProperty(formatUsd(it.value.byCurrency[currencyId]?.changeUsd ?: 0.0)) }
                setClipboardValue { formatNumberForClipboard(it.byCurrency[currencyId]?.changeUsd ?: 0.0) }
            }
            val percentColumn = TableColumn<MoneyRow, String>("%").apply {
                isSortable = false
                setCellValueFactory { SimpleStringProperty(formatPercent(it.value.byCurrency[currencyId]?.percent ?: 0.0)) }
                setClipboardValue { formatNumberForClipboard(it.byCurrency[currencyId]?.percent ?: 0.0) }
            }
            group.columns.addAll(restColumn, restUsdColumn, changeUsdColumn, percentColumn)
            table.columns.add(group)
        }
    }

    private fun setupChart(
        rows: List<MoneyRow>,
        usedCurrencies: List<CurrencyId>,
        byYears: Boolean,
        currencies: Map<CurrencyId, CurrencySnapshot>,
    ) {
        val dateFormat = DateTimeFormatter.ofPattern(if (byYears) "yyyy" else "yyyy-MM")

        val totalSeries = XYChart.Series<String, Number>()
        totalSeries.name = "Общий остаток"
        rows.forEach { row -> totalSeries.data.add(XYChart.Data(row.date.format(dateFormat), row.totalUsd)) }
        chart.data.add(totalSeries)

        for (currencyId in usedCurrencies) {
            val series = XYChart.Series<String, Number>()
            series.name = currencies[currencyId]?.name ?: "-"
            rows.forEach { row -> series.data.add(XYChart.Data(row.date.format(dateFormat), row.byCurrency[currencyId]?.restUsd ?: 0.0)) }
            chart.data.add(series)
        }
    }

    private fun formatUsd(value: Double): String = usdFormat.format(value)

    private fun formatPercent(value: Double): String = String.format(Locale.ROOT, "%.1f%%", value)

    // no grouping separator and the system decimal separator, so Excel / Google Sheets parse the
    // pasted value as a number instead of text
    private fun formatNumberForClipboard(value: Double): String =
        BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', decimalSeparator)

    companion object {
        const val NAME = "money"
        private val decimalSeparator = DecimalFormatSymbols.getInstance().decimalSeparator
        private val usdFormat = DecimalFormat().apply {
            decimalFormatSymbols = DecimalFormatSymbols(Locale.ROOT).apply {
                groupingSeparator = ' '
                decimalSeparator = '.'
            }
            isGroupingUsed = true
            groupingSize = 3
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
    }
}
