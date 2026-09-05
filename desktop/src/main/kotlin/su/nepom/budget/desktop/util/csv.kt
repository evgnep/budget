package su.nepom.budget.desktop.util

// ";" instead of "," - Excel with a Russian locale uses "," as the decimal separator, so "," can't
// also be the column separator
private const val CSV_SEPARATOR = ';'

private fun String.csvCell(): String =
    if (any { it == CSV_SEPARATOR || it == '"' || it == '\n' || it == '\r' }) "\"${replace("\"", "\"\"")}\"" else this

private fun csvRow(cells: List<String>): String = cells.joinToString(CSV_SEPARATOR.toString(), postfix = "\r\n") { it.csvCell() }

// leading BOM so Excel detects UTF-8 instead of guessing the system codepage
fun buildCsvText(header: List<String>, rows: List<List<String>>): String = buildString {
    append('﻿')
    append(csvRow(header))
    rows.forEach { append(csvRow(it)) }
}
