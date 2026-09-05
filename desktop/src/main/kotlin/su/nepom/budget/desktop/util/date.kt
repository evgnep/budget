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
private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val clipboardDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")

fun LocalDate.toStartOfDayInstant(): Instant =
    atStartOfDay(zone).toInstant().toKotlinInstant()

fun LocalDate.toEndOfDayInstant(): Instant =
    atTime(LocalTime.MAX).atZone(zone).toInstant().toKotlinInstant()

fun Instant.toLocalDate(): LocalDate =
    toJavaInstant().atZone(zone).toLocalDate()

fun Instant.formatDateTime(): String =
    dateTimeFormat.format(toJavaInstant().atZone(zone))

fun Instant.formatTime(): String =
    timeFormat.format(toJavaInstant().atZone(zone))

// used for the transactions table's "Дата" column clipboard copy - the on-screen cell shows only
// the time, but pasting into Excel should still carry the date
fun Instant.formatDateForClipboard(): String =
    clipboardDateFormat.format(toJavaInstant().atZone(zone))
