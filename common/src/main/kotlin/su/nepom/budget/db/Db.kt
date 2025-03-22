package su.nepom.budget.db

interface Db: AutoCloseable {
    fun createSession(): Session

    fun createSessionInBlockingMode(createEvents: Boolean): Session
}