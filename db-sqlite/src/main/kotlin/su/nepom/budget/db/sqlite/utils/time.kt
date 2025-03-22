package su.nepom.budget.db.sqlite.utils

import kotlinx.datetime.Instant
import java.sql.Timestamp

fun Instant.toTimestamp() = Timestamp(this.toEpochMilliseconds())
