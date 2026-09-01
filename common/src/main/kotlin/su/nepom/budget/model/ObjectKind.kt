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
  val simpleObject: Boolean = false
) {
  CURRENCY,
  ACCOUNT,
  TRANSACTION,
  SUBACCOUNT(simpleObject = true, validator = { (this as SubaccountContent).validate(it) }),
  ;
}