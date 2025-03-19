package su.nepom.budget.db.dao

import su.nepom.budget.model.Uuid

interface CrudDao<T> {
    fun getAll(): List<T>

    fun getById(id: Uuid): T?

    fun save(entity: T): T

    fun delete(id: Uuid): Boolean
}