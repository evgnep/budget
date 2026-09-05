package su.nepom.budget.desktop.util.fx

import javafx.scene.control.DatePicker
import javafx.util.StringConverter
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// dd-MM-yyyy display, with flexible short input: "1-2-22" (2-digit year -> 20xx), "1-2" (current
// year), "1" (current month and year)
fun DatePicker.setupFlexibleDateFormat() {
    val fmt = DateTimeFormatter.ofPattern("dd-MM-yyyy")
    promptText = "д[-м[-гг]]"
    converter = object : StringConverter<LocalDate>() {
        override fun toString(date: LocalDate?): String = date?.format(fmt) ?: ""

        override fun fromString(text: String?): LocalDate? {
            val currentDate = LocalDate.now()
            val parts = text?.trim()?.takeIf { it.isNotEmpty() }?.split('-') ?: return null
            if (parts.isEmpty()) return null
            val day = parts[0].toIntOrNull() ?: return null
            val month = if (parts.size < 2) currentDate.month.value else (parts[1].toIntOrNull() ?: return null)
            val year =
                if (parts.size < 3) currentDate.year
                else (parts[2].toIntOrNull() ?: return null).let { if (it < 100) 2000 + it else it }
            return runCatching { LocalDate.of(year, month, day) }.getOrNull()
        }
    }
}
