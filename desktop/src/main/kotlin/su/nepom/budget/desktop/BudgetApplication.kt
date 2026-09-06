package su.nepom.budget.desktop

import atlantafx.base.theme.PrimerLight
import io.github.oshai.kotlinlogging.KotlinLogging
import javafx.application.Application
import javafx.application.Platform
import javafx.geometry.Orientation
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Menu
import javafx.scene.control.MenuBar
import javafx.scene.control.MenuItem
import javafx.scene.control.Separator
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.control.ToolBar
import javafx.scene.image.Image
import javafx.scene.layout.BorderPane
import javafx.stage.Stage
import su.nepom.budget.Global
import su.nepom.budget.desktop.util.fx.setIcon

private val logger = KotlinLogging.logger {}

class BudgetApplication : Application() {

    private lateinit var budgetComponent: BudgetComponent
    private lateinit var rootPane: BorderPane

    override fun start(stage: Stage) {
        logger.info { "Starting budget desktop application" }

        Application.setUserAgentStylesheet(PrimerLight().userAgentStylesheet)
        createAndLoadComponents(stage)

        stage.title = "Budget!"
        stage.setIcon("app")
        stage.scene = Scene(buildRoot())
        stage.width = 900.0
        stage.height = 640.0
        budgetComponent.windowStateService().bind(stage, "main")
        // closing the main window quits the app, even if detached windows are still open
        stage.setOnHidden { Platform.exit() }
        stage.show()

        budgetComponent.configurationDialog().showOnStartIfNeed()
        showMainAccounts()
    }

    override fun stop() {
        budgetComponent.eventStoreService().onStop()
        budgetComponent.currenciesExchangeRatesAppService().onStop()
    }

    private fun buildRoot(): BorderPane {
        val nav = ToggleGroup()
        val mainAccountsButton = navButton("Основные счета", nav) { showMainAccounts() }
        val accountsButton = navButton("Счета", nav) { showAccounts() }
        val currenciesButton = navButton("Валюты", nav) { showCurrencies() }
        val syncButton = navButton("Синхронизация", nav) { showSync() }
        mainAccountsButton.isSelected = true

        val eventStoreService = budgetComponent.eventStoreService()
        fun refreshSyncBold() {
            syncButton.style = if (eventStoreService.hasEventsToSync.get()) "-fx-font-weight: bold;" else ""
        }
        eventStoreService.hasEventsToSync.addListener { _, _, _ -> Platform.runLater(::refreshSyncBold) }
        refreshSyncBold()

        val transactionsButton = Button("Операции").apply {
            maxWidth = Double.MAX_VALUE
            setOnAction { budgetComponent.windowManager().openTransactions() }
        }

        val balancesButton = Button("Остатки").apply {
            maxWidth = Double.MAX_VALUE
            setOnAction { budgetComponent.windowManager().openBalances() }
        }

        val moneyButton = Button("Деньги").apply {
            maxWidth = Double.MAX_VALUE
            setOnAction { budgetComponent.windowManager().openMoney() }
        }

        val eventsButton = Button("События").apply {
            maxWidth = Double.MAX_VALUE
            setOnAction { budgetComponent.windowManager().openEvents() }
        }

        rootPane = BorderPane().apply {
            top = buildMenuBar()
            left = ToolBar(
                mainAccountsButton, Separator(),
                transactionsButton, balancesButton, moneyButton, eventsButton, Separator(),
                syncButton, Separator(),
                accountsButton, currenciesButton, Separator(),
            ).apply {
                orientation = Orientation.VERTICAL
            }
        }
        return rootPane
    }

    private fun navButton(text: String, group: ToggleGroup, action: () -> Unit): ToggleButton =
        ToggleButton(text).apply {
            toggleGroup = group
            maxWidth = Double.MAX_VALUE
            setOnAction {
                if (!isSelected) isSelected = true else action()
            }
        }

    private fun buildMenuBar(): MenuBar {
        val settingsItem = MenuItem("Настройки...").apply {
            setOnAction { budgetComponent.configurationDialog().show() }
        }
        val exitItem = MenuItem("Выход").apply {
            setOnAction { Platform.exit() }
        }
        val fileMenu = Menu("Файл", null, settingsItem, SeparatorMenuItem(), exitItem)

        val openTransactionsItem = MenuItem("Список операций").apply {
            setOnAction { budgetComponent.windowManager().openTransactions() }
        }
        val openBalancesItem = MenuItem("Остатки и обороты").apply {
            setOnAction { budgetComponent.windowManager().openBalances() }
        }
        val openMoneyItem = MenuItem("Деньги").apply {
            setOnAction { budgetComponent.windowManager().openMoney() }
        }
        val openEventsItem = MenuItem("События").apply {
            setOnAction { budgetComponent.windowManager().openEvents() }
        }
        val windowsMenu = Menu("Окна", null, openTransactionsItem, openBalancesItem, openMoneyItem, openEventsItem)

        val aboutItem = MenuItem("О программе").apply {
            setOnAction { budgetComponent.aboutDialog().show() }
        }
        val helpMenu = Menu("Справка", null, aboutItem)

        return MenuBar(fileMenu, windowsMenu, helpMenu)
    }

    private fun showMainAccounts() = setContent(budgetComponent.mainAccountsView().root)

    private fun showAccounts() = setContent(budgetComponent.accountView().root)

    private fun showCurrencies() = setContent(budgetComponent.currencyView().root)

    private fun showSync() = setContent(budgetComponent.syncView().root)

    private fun setContent(node: Node) {
        rootPane.center = node
    }

    private fun createAndLoadComponents(mainStage: Stage) {
        budgetComponent = DaggerBudgetComponent.builder().mainStage(mainStage).build()
        budgetComponent.dbService()
        budgetComponent.eventStoreService()
        budgetComponent.settingsService()
        budgetComponent.currenciesExchangeRatesAppService()
    }
}

fun main(args: Array<String>) {
    Global.setCurrentUser("")
    Application.launch(BudgetApplication::class.java, *args)
}
