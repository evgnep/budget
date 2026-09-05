package su.nepom.budget.model

import su.nepom.budget.db.Session
import su.nepom.budget.event.ActualVersionContent
import su.nepom.budget.event.StorableContent
import su.nepom.budget.event.SubaccountContent
import su.nepom.budget.event.validate

enum class ObjectKind(
  val toActualVersionConverter: (StorableContent) -> ActualVersionContent =
    { throw UnsupportedOperationException("No converter for ${it::class}") },
  /**
   * Validate given object
   */
  val validator: ActualVersionContent.(Session) -> Unit = { _, _ -> },
  /**
   * If true, this kind has no own DAO/table. It is stored in the shared `simple_object` table
   * (see `SqliteSimpleObjectDao`), keyed by [ObjectKind] name, with content as polymorphic JSON.
   * Adding a new simple kind needs no schema change - just a new `StorableContent` subtype.
   * Also, it uses universal conflict resolver (see `JsonDetailController`).
   */
  val simpleObject: Boolean = false,
  /**
   * If true, sync conflicts for this kind are resolved automatically (any variant is fine),
   * without asking the user.
   */
  val autoResolve: Boolean = false,
) {
  CURRENCY,
  ACCOUNT,
  TRANSACTION,
  SUBACCOUNT(simpleObject = true, validator = { (this as SubaccountContent).validate(it) }),
  CURRENCY_EXCHANGE_RATE(simpleObject = true, autoResolve = true),
  ;
}