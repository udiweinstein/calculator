package com.udi.calculator

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

class CalcException(val kind: Kind) : Exception(kind.name) {
    enum class Kind { SYNTAX, DIV_ZERO }
}

/**
 * Evaluates expressions written with the calculator's own symbols:
 * digits, '.', 'e' (exponent), '+', '−', '×', '÷', '%', '(' and ')'.
 * Uses BigDecimal, so 0.1 + 0.2 = 0.3 exactly.
 */
object Evaluator {
    private val MC = MathContext.DECIMAL128
    private val HUNDRED = BigDecimal(100)
    private val SCI_HIGH = BigDecimal("1e15")
    private val SCI_LOW = BigDecimal("1e-9")

    fun evaluate(expression: String): BigDecimal {
        val parser = Parser(expression)
        val value = parser.parseExpression()
        if (!parser.atEnd()) throw CalcException(CalcException.Kind.SYNTAX)
        return value
    }

    /** Formats a result to 12 significant digits, switching to e-notation for very large/small values. */
    fun format(value: BigDecimal): String {
        val r = value.round(MathContext(12, RoundingMode.HALF_UP)).stripTrailingZeros()
        if (r.signum() == 0) return "0"
        val abs = r.abs()
        val text = if (abs >= SCI_HIGH || abs < SCI_LOW) {
            val exp = r.precision() - r.scale() - 1
            val mantissa = r.movePointLeft(exp).stripTrailingZeros().toPlainString()
            "${mantissa}e$exp"
        } else {
            r.toPlainString()
        }
        return text.replace('-', '−')
    }

    private class Parser(private val s: String) {
        private var pos = 0

        fun atEnd() = pos >= s.length
        private fun peek(): Char? = if (pos < s.length) s[pos] else null

        // expression = term (('+' | '−') term)*
        fun parseExpression(): BigDecimal {
            var v = parseTerm()
            while (true) {
                when (peek()) {
                    '+' -> { pos++; v = v.add(parseTerm(), MC) }
                    '−' -> { pos++; v = v.subtract(parseTerm(), MC) }
                    else -> return v
                }
            }
        }

        // term = factor (('×' | '÷') factor)*
        private fun parseTerm(): BigDecimal {
            var v = parseFactor()
            while (true) {
                when (peek()) {
                    '×' -> { pos++; v = v.multiply(parseFactor(), MC) }
                    '÷' -> {
                        pos++
                        val d = parseFactor()
                        if (d.signum() == 0) throw CalcException(CalcException.Kind.DIV_ZERO)
                        v = v.divide(d, MC)
                    }
                    else -> return v
                }
            }
        }

        // factor = ('−' | '+') factor | postfix
        private fun parseFactor(): BigDecimal = when (peek()) {
            '−' -> { pos++; parseFactor().negate() }
            '+' -> { pos++; parseFactor() }
            else -> parsePostfix()
        }

        // postfix = primary '%'*
        private fun parsePostfix(): BigDecimal {
            var v = parsePrimary()
            while (peek() == '%') {
                pos++
                v = v.divide(HUNDRED, MC)
            }
            return v
        }

        // primary = number | '(' expression ')'
        private fun parsePrimary(): BigDecimal {
            val c = peek() ?: throw CalcException(CalcException.Kind.SYNTAX)
            if (c == '(') {
                pos++
                val v = parseExpression()
                if (peek() != ')') throw CalcException(CalcException.Kind.SYNTAX)
                pos++
                return v
            }
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            if (pos < s.length && s[pos] == 'e') {
                pos++
                if (pos < s.length && s[pos] == '−') pos++
                while (pos < s.length && s[pos].isDigit()) pos++
            }
            if (start == pos) throw CalcException(CalcException.Kind.SYNTAX)
            var token = s.substring(start, pos).replace('−', '-').replace('e', 'E')
            if (token.startsWith(".")) token = "0$token"
            token = token.replace(".E", ".0E")
            if (token.endsWith(".")) token += "0"
            return try {
                BigDecimal(token)
            } catch (e: NumberFormatException) {
                throw CalcException(CalcException.Kind.SYNTAX)
            }
        }
    }
}
