package su.nepom.budget.utils

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Evaluate a simple arithmetic formula built from numbers, the operators + - * /
 * and parentheses. Both '.' and ',' are treated as the decimal separator and
 * spaces are ignored, so "3 * (7+4)" and "-10,23" are both valid.
 * Returns null when the text is not a valid formula.
 */
fun evalMoneyFormula(input: String): BigDecimal? {
    val text = input.trim().replace(" ", "").replace(',', '.')
    if (text.isEmpty()) return null
    return try {
        val parser = FormulaParser(text)
        val result = parser.parseExpression()
        if (parser.atEnd()) result else null
    } catch (e: FormulaError) {
        null
    } catch (e: ArithmeticException) {
        null
    } catch (e: NumberFormatException) {
        null
    }
}

private class FormulaError : Exception()

// recursive descent: expr -> term (('+'|'-') term)*, term -> factor (('*'|'/') factor)*,
// factor -> ('+'|'-') factor | primary, primary -> number | '(' expr ')'
private class FormulaParser(private val text: String) {
    private var pos = 0

    fun atEnd() = pos >= text.length

    fun parseExpression(): BigDecimal {
        var value = parseTerm()
        while (!atEnd()) {
            val op = text[pos]
            if (op != '+' && op != '-') break
            pos++
            val rhs = parseTerm()
            value = if (op == '+') value.add(rhs) else value.subtract(rhs)
        }
        return value
    }

    private fun parseTerm(): BigDecimal {
        var value = parseFactor()
        while (!atEnd()) {
            val op = text[pos]
            if (op != '*' && op != '/') break
            pos++
            val rhs = parseFactor()
            value = if (op == '*') value.multiply(rhs) else value.divide(rhs, 20, RoundingMode.HALF_UP)
        }
        return value
    }

    private fun parseFactor(): BigDecimal {
        if (atEnd()) throw FormulaError()
        return when (text[pos]) {
            '+' -> { pos++; parseFactor() }
            '-' -> { pos++; parseFactor().negate() }
            else -> parsePrimary()
        }
    }

    private fun parsePrimary(): BigDecimal {
        if (atEnd()) throw FormulaError()
        if (text[pos] == '(') {
            pos++
            val value = parseExpression()
            if (atEnd() || text[pos] != ')') throw FormulaError()
            pos++
            return value
        }
        return parseNumber()
    }

    private fun parseNumber(): BigDecimal {
        val start = pos
        var seenDot = false
        while (!atEnd()) {
            val c = text[pos]
            if (c.isDigit()) pos++
            else if (c == '.' && !seenDot) { seenDot = true; pos++ }
            else break
        }
        if (pos == start) throw FormulaError()
        return BigDecimal(text.substring(start, pos))
    }
}
