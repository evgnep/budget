package su.nepom.budget.db

interface Db: AutoCloseable {
    fun createSession(name: String): Session

    /**
     * Only this session can write to Db and only one such session can exist
     */
    fun createSessionInBlockingMode(name: String, createEvents: Boolean): Session
}