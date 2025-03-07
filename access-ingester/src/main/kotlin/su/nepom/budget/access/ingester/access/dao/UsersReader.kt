package su.nepom.budget.access.ingester.access.dao

import su.nepom.budget.access.ingester.access.UserAccess
import su.nepom.budget.access.ingester.access.utils.readAll

class UsersReader {
    fun read(): List<UserAccess> = readAll("SELECT ИД, Пользователь FROM Пользователи") {
        UserAccess(getInt("ИД"), getString("Пользователь"))
    }
}