package su.nepom.budget.desktop.ui

import jakarta.inject.Inject
import jakarta.inject.Singleton
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Menu
import javafx.scene.control.MenuBar
import javafx.scene.control.MenuItem
import javafx.scene.layout.BorderPane
import javafx.stage.Stage
import su.nepom.budget.desktop.service.WindowStateService
import su.nepom.budget.desktop.ui.history.HistoryController
import su.nepom.budget.desktop.ui.subaccount.SubaccountsController
import su.nepom.budget.desktop.ui.transaction.TransactionController
import su.nepom.budget.desktop.util.fx.Controller
import su.nepom.budget.desktop.util.fx.Disposable
import su.nepom.budget.desktop.util.fx.FxmlService
import su.nepom.budget.desktop.util.fx.setIcon
import su.nepom.budget.model.AccountId
import su.nepom.budget.model.ObjectKind
import su.nepom.budget.model.Uuid

/**
 * Opens standalone windows. Every call creates a new instance, so the same screen can be
 * open several times at once. Windows have no owner - each one is independent in z-order.
 */
@Singleton
class WindowManager @Inject constructor(
    private val fxmlService: FxmlService,
    private val windowStateService: WindowStateService,
) {
    private class OpenWindow(val stage: Stage, val titleBase: String)

    private val openWindows = mutableListOf<OpenWindow>()

    fun openTransactions(initialFilter: TransactionController.InitialFilter? = null) {
        open("Операции", "transactions") { stage, collect ->
            stage.setIcon("operations")
            val content = fxmlService.load<Parent>("transaction/transactions.fxml", stage, null) { controller ->
                collect(controller)
                if (initialFilter != null && controller is TransactionController) {
                    controller.setInitialFilter(initialFilter)
                }
            }
            BorderPane().apply {
                top = MenuBar(
                    Menu(
                        "Окно", null,
                        MenuItem("Новое окно").apply { setOnAction { openTransactions() } },
                        MenuItem("Закрыть").apply { setOnAction { stage.close() } },
                    )
                )
                center = content
            }
        }
    }

    fun openBalances() {
        open("Остатки и обороты", "balances") { stage, collect ->
            stage.setIcon("accounts")
            val content = fxmlService.load<Parent>("balance/balances.fxml", stage, null, collect)
            BorderPane().apply {
                top = MenuBar(
                    Menu(
                        "Окно", null,
                        MenuItem("Новое окно").apply { setOnAction { openBalances() } },
                        MenuItem("Закрыть").apply { setOnAction { stage.close() } },
                    )
                )
                center = content
            }
        }
    }

    fun openMoney() {
        open("Деньги", "money") { stage, collect ->
            stage.setIcon("accounts")
            val content = fxmlService.load<Parent>("money/money.fxml", stage, null, collect)
            BorderPane().apply {
                top = MenuBar(
                    Menu(
                        "Окно", null,
                        MenuItem("Новое окно").apply { setOnAction { openMoney() } },
                        MenuItem("Закрыть").apply { setOnAction { stage.close() } },
                    )
                )
                center = content
            }
        }
    }

    fun openSubaccounts(accountId: AccountId, accountName: String) {
        open("Субсчета: $accountName", null) { stage, collect ->
            stage.setIcon("accounts")
            fxmlService.load("subaccount/subaccounts.fxml", stage, null) { controller ->
                collect(controller)
                if (controller is SubaccountsController) controller.configure(accountId)
            }
        }
    }

    fun openHistory(uuid: Uuid, kind: ObjectKind, title: String) {
        open(title, null) { stage, collect ->
            stage.setIcon("history")
            fxmlService.load("history/history.fxml", stage, null) { controller ->
                collect(controller)
                if (controller is HistoryController) controller.configure(uuid, kind)
            }
        }
    }

    private fun open(
        titleBase: String,
        stateKey: String?,
        buildRoot: (Stage, (Controller) -> Unit) -> Parent,
    ) {
        val disposables = mutableListOf<Disposable>()
        val stage = Stage()
        stage.title = nextTitle(titleBase)
        stage.scene = Scene(buildRoot(stage) { if (it is Disposable) disposables += it })
        if (stateKey != null) windowStateService.bind(stage, stateKey)
        val window = OpenWindow(stage, titleBase)
        openWindows += window
        stage.setOnHidden {
            openWindows -= window
            disposables.forEach(Disposable::dispose)
        }
        stage.show()
    }

    private fun nextTitle(titleBase: String): String {
        val same = openWindows.count { it.titleBase == titleBase }
        return if (same == 0) titleBase else "$titleBase (${same + 1})"
    }
}
