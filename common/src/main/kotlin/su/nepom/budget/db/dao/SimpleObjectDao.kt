package su.nepom.budget.db.dao

import su.nepom.budget.event.ActualVersionContent

interface SimpleObjectDao<T: ActualVersionContent> : CrudDao<T> {
  fun getAll(withHidden: Boolean): List<T>

  override fun getAll(): List<T> = getAll(withHidden = false)
}