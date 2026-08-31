package su.nepom.budget.desktop

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

        createAndLoadComponents(stage)

        stage.title = "Budget!"
        stage.setIcon("app")
        stage.scene = Scene(buildRoot())
        stage.width = 900.0
        stage.height = 640.0
        // closing the main window quits the app, even if detached windows are still open
        stage.setOnHidden { Platform.exit() }
        stage.show()

        budgetComponent.configurationDialog().showOnStartIfNeed()
        showAccounts()
    }

    override fun stop() {
        budgetComponent.eventStoreService().onStop()
    }

    private fun buildRoot(): BorderPane {
        val nav = ToggleGroup()
        val accountsButton = navButton("Счета", nav) { showAccounts() }
        val currenciesButton = navButton("Валюты", nav) { showCurrencies() }
        accountsButton.isSelected = true

        val transactionsButton = Button("Операции").apply {
            maxWidth = Double.MAX_VALUE
            setOnAction { budgetComponent.windowManager().openTransactions() }
        }

        val balancesButton = Button("Остатки").apply {
            maxWidth = Double.MAX_VALUE
            setOnAction { budgetComponent.windowManager().openBalances() }
        }

        rootPane = BorderPane().apply {
            top = buildMenuBar()
            left = ToolBar(
                transactionsButton, balancesButton, Separator(), accountsButton, currenciesButton, Separator(),
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
        val windowsMenu = Menu("Окна", null, openTransactionsItem, openBalancesItem)

        return MenuBar(fileMenu, windowsMenu)
    }

    private fun showAccounts() = setContent(budgetComponent.accountView().root)

    private fun showCurrencies() = setContent(budgetComponent.currencyView().root)

    private fun setContent(node: Node) {
        rootPane.center = node
    }

    private fun createAndLoadComponents(mainStage: Stage) {
        budgetComponent = DaggerBudgetComponent.builder().mainStage(mainStage).build()
        budgetComponent.dbService()
        budgetComponent.eventStoreService()
        budgetComponent.settingsService()
    }
}

fun main(args: Array<String>) {
    Global.setCurrentUser("")
    Application.launch(BudgetApplication::class.java, *args)
}
