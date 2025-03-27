package su.nepom.budget.desktop.util.fx

import javafx.stage.Stage

interface Controller

interface StageOwnerAwareController : Controller {
    /**
     * owner.stage is not accessible in initialize method itself. If you need stage in initialize, use
     * [StageAwareController.initialize]
     */
    fun initialize(owner: StageOwner)
}

interface StageAwareController : Controller {
    fun initialize(stage: Stage)
}