package su.nepom.budget.desktop.util

import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val zone: ZoneId = ZoneId.systemDefault()
private val dateTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

fun LocalDate.toStartOfDayInstant(): Instant =
    atStartOfDay(zone).toInstant().toKotlinInstant()

fun LocalDate.toEndOfDayInstant(): Instant =
    atTime(LocalTime.MAX).atZone(zone).toInstant().toKotlinInstant()

fun Instant.toLocalDate(): LocalDate =
    toJavaInstant().atZone(zone).toLocalDate()

fun Instant.formatDateTime(): String =
    dateTimeFormat.format(toJavaInstant().atZone(zone))
