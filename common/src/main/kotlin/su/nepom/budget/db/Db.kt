package su.nepom.budget.db

interface Db: AutoCloseable {
    /**
     * @param name Session identifier for logging, thread name, etc
     * @param blockingMode Only this session can write to Db and only one such session can exist
     * @param createEvents Events on data change will be created and saved
     * @param autoCommit Each modifying operation will be auto-commited
     */
    fun createSession(
        name: String,
        blockingMode: Boolean = false,
        createEvents: Boolean = true,
        autoCommit: Boolean = false
    ): Session
}