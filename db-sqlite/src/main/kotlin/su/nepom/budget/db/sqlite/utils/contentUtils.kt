package su.nepom.budget.db.sqlite.utils

import su.nepom.budget.event.StorableContent

internal fun StorableContent.uuidCode(): String = id.uuid.id