package su.nepom.budget.desktop.service

import jakarta.inject.Inject
import jakarta.inject.Singleton
import su.nepom.budget.Global
import su.nepom.budget.desktop.util.CheckError
import su.nepom.budget.desktop.util.CheckOk
import su.nepom.budget.desktop.util.db.CheckableDatabaseStringProperty
import su.nepom.budget.model.Place

private val placePattern = Regex("[A-Za-z0-9]{3,20}")

@Singleton
class SettingsService @Inject constructor(
    dbService: DbService,
) {
    val creator =
        CheckableDatabaseStringProperty("creator", dbService.sessionProperty, "Имя пользователя не задано") {
            if (it.isBlank()) CheckError("Имя пользователя не может быть пустым") else CheckOk
        }

    val place = CheckableDatabaseStringProperty("place", dbService.sessionProperty, "Название места не задано") {
        if (!placePattern.matches(it)) CheckError("Название места должно быть от 3 до 20 латинских символов и цифр")
        else CheckOk
    }

    init {
        creator.addListenerAndCallItNow { _, _, newValue -> Global.setCurrentUser(newValue) }
        place.addListenerAndCallItNow { _, _, newValue -> Global.setCurrentPlace(Place(newValue)) }
    }
}