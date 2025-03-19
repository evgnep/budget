package su.nepom.budget.db

interface Db {
    fun getSession(): Session

    fun getSessionInBlockingMode(dontCreateEvents: Boolean): Session
}