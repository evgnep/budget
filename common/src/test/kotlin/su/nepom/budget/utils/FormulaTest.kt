package su.nepom.budget.utils

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class FormulaTest {

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(
        delimiter = ';', value = [
            "10                 ; 10",
            "'  10  '           ; 10",
            "-10                ; -10",
            "+5                 ; 5",
            "-10.2              ; -10.2",
            "-10,23             ; -10.23",
            "1 000,50           ; 1000.50",
            "1 0                ; 10",
            ".5                 ; 0.5",
            "5.                 ; 5",
            "2,5*2              ; 5",
            "1+2                ; 3",
            "1+2*3              ; 7",
            "(1+2)*3            ; 9",
            "3*(1+2)            ; 9",
            "3 * (7+4)          ; 33",
            "2*(3+(4-1))        ; 12",
            "10-2-3             ; 5",
            "10/4               ; 2.5",
            "0.1+0.2            ; 0.3",
            "--5                ; 5",
            "-+-5               ; 5",
            "3*-2               ; -6",
            "-2*-3              ; 6",
        ]
    )
    fun `evaluates valid formulas`(input: String, expected: String) {
        assertThat(evalMoneyFormula(input)).isEqualByComparingTo(expected)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "   ", "abc", "1a", "1+", "1-", "*5", "/5", "1+*2", "1//2", "2**3",
            "(1+2", "1+2)", "()", "( )", "1+()", ",", ".", "1..2", "1,,2", "1 2 +",
        ]
    )
    fun `rejects invalid formulas`(input: String) {
        assertThat(evalMoneyFormula(input)).isNull()
    }

    @Test
    fun `comma and dot are the same decimal separator`() {
        assertThat(evalMoneyFormula("1,5")).isEqualByComparingTo(evalMoneyFormula("1.5"))
        assertThat(evalMoneyFormula("1,5+2,5")).isEqualByComparingTo("4")
    }

    @Test
    fun `division uses a 20 digit scale with half-up rounding`() {
        assertThat(evalMoneyFormula("1/3")).isEqualByComparingTo("0.33333333333333333333")
        assertThat(evalMoneyFormula("2/3")).isEqualByComparingTo("0.66666666666666666667")
    }

    @Test
    fun `division by zero is rejected`() {
        assertThat(evalMoneyFormula("1/0")).isNull()
        assertThat(evalMoneyFormula("5/(3-3)")).isNull()
    }

    @Test
    fun `result can be rounded to the currency scale`() {
        assertThat(evalMoneyFormula("10/3")!!.toRawMoney(2).value).isEqualTo(333L)
        assertThat(evalMoneyFormula("10/8")!!.toRawMoney(1).value).isEqualTo(13L)
        assertThat(evalMoneyFormula("1.005")!!.toRawMoney(2).value).isEqualTo(101L)
        assertThat(evalMoneyFormula("1+2")!!.toRawMoney(2).value).isEqualTo(300L)
    }
}
