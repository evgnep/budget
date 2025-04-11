package su.nepom.budget.desktop

import io.github.oshai.kotlinlogging.KotlinLogging
import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.image.Image
import javafx.scene.layout.HBox
import javafx.stage.Stage
import su.nepom.budget.Global

private val logger = KotlinLogging.logger {}

class BudgetApplication : Application() {

    private lateinit var budgetComponent: BudgetComponent

    override fun start(stage: Stage) {
        logger.info { "Starting budget desktop application" }

        createAndLoadComponents(stage)

        stage.title = "Budget!"
        stage.setIcon()
        stage.scene = Scene(HBox(5.0).apply {
            children.addAll(
                Button("Settings").apply { setOnMouseClicked { budgetComponent.configurationDialog().show() } },
                Button("Валюты").apply { setOnMouseClicked { budgetComponent.createAndShowCurrencyDialog() } },
            )
        })
        stage.width = 300.0
        stage.height = 250.0
        stage.show()

        budgetComponent.configurationDialog().showOnStartIfNeed()
    }

    private fun createAndLoadComponents(mainStage: Stage) {
        budgetComponent = DaggerBudgetComponent.builder().mainStage(mainStage).build()
        budgetComponent.dbService()
        budgetComponent.eventStoreService()
        budgetComponent.settingsService()
    }

    private fun Stage.setIcon() {
        icons.add(Image(this::class.java.getResourceAsStream("/icons/budget.png")))
    }
}

fun main(args: Array<String>) {
    Global.setCurrentUser("")
    Application.launch(BudgetApplication::class.java, *args)
}