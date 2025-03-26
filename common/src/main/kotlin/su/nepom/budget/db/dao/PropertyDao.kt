package su.nepom.budget.db.dao

interface PropertyDao {
    fun get(key: String): String?

    fun save(key: String, value: String)

    fun delete(key: String)
}
