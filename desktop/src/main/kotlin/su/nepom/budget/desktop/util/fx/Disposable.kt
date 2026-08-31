package su.nepom.budget.desktop.util.fx

// a controller that holds long-lived subscriptions; WindowManager calls dispose() when its window closes
interface Disposable {
    fun dispose()
}
