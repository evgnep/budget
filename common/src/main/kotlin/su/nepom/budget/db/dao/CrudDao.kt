package su.nepom.budget.db.dao

import su.nepom.budget.db.Session
import su.nepom.budget.model.Uuid

interface CrudDao<T> {
    fun getAll(): List<T>

    fun getById(id: Uuid): T?

    fun count(): Int

    fun save(entity: T): T

    val session: Session
}