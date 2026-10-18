package com.udi.calculator

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.acosh
import kotlin.math.asin
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.atanh
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

class CalcError : Exception()

/**
 * Classic algebraic-logic scientific calculator engine, in the style of the
 * 1980s pocket scientifics: functions act immediately on the displayed value,
 * binary operators follow precedence (xʸ > ×÷ > +−), parentheses up to 15 levels,
 * 10-digit mantissa + 2-digit exponent display, one memory register.
 */
class Engine {
    enum class Angle { DEG, RAD, GRA }
    enum class Trig { SIN, COS, TAN }

    var angle = Angle.DEG
    var memory = 0.0
    var shift = false
    var hyp = false
    var error = false
        private set

    private var x = 0.0

    // number entry state
    private var entering = false
    private val mant = StringBuilder()
    private var mantNeg = false
    private var inExp = false
    private val expDigits = StringBuilder()
    private var expNeg = false

    private var lastWasOp = false

    private class Level {
        val vals = ArrayList<Double>()
        val ops = ArrayList<Char>()
    }

    private val levels = arrayListOf(Level())

    val openParens: Int get() = levels.size - 1

    private inline fun guard(block: () -> Unit) {
        if (error) return
        try {
            block()
        } catch (e: CalcError) {
            setError()
        } catch (e: ArithmeticException) {
            setError()
        } catch (e: NumberFormatException) {
            setError()
        }
    }

    private fun setError() {
        error = true
        entering = false
        resetStack()
    }

    private fun resetStack() {
        levels.clear()
        levels.add(Level())
        lastWasOp = false
    }

    private fun check(v: Double): Double {
        if (v.isNaN() || v.isInfinite() || abs(v) >= 1e100) throw CalcError()
        return if (abs(v) < 1e-99) 0.0 else v
    }

    // ---------- entry ----------

    private fun startEntry() {
        entering = true
        mant.setLength(0)
        mantNeg = false
        inExp = false
        expDigits.setLength(0)
        expNeg = false
        lastWasOp = false
    }

    private fun commitEntry() {
        if (!entering) return
        entering = false
        val s = buildString {
            if (mantNeg) append('-')
            append(if (mant.isEmpty()) "0" else mant)
            append('E')
            if (expNeg) append('-')
            append(if (expDigits.isEmpty()) "0" else expDigits)
        }
        x = check(s.toDouble())
    }

    fun digit(d: Char) = guard {
        if (!entering) startEntry()
        if (inExp) {
            if (expDigits.length >= 2) expDigits.deleteCharAt(0)
            expDigits.append(d)
        } else {
            if (mant.count { it.isDigit() } >= 10) return@guard
            if (mant.toString() == "0") mant.setLength(0)
            mant.append(d)
        }
    }

    fun dot() = guard {
        if (!entering) startEntry()
        if (inExp || mant.contains('.')) return@guard
        if (mant.isEmpty()) mant.append('0')
        mant.append('.')
    }

    fun expEntry() = guard {
        if (!entering) {
            startEntry()
            mant.append('1')
        }
        inExp = true
    }

    fun negate() = guard {
        when {
            entering -> if (inExp) expNeg = !expNeg else mantNeg = !mantNeg
            lastWasOp -> { startEntry(); mantNeg = true }
            else -> x = if (x == 0.0) 0.0 else -x
        }
    }

    // ---------- operators ----------

    private fun prec(op: Char) = when (op) {
        '+', '-' -> 1
        '*', '/' -> 2
        else -> 3 // '^' power, 'r' root
    }

    private fun apply(a: Double, op: Char, b: Double): Double = check(
        when (op) {
            '+' -> a + b
            '-' -> a - b
            '*' -> a * b
            '/' -> if (b == 0.0) throw CalcError() else a / b
            '^' -> {
                if (a == 0.0 && b <= 0.0) throw CalcError()
                a.pow(b)
            }
            'r' -> {
                if (b == 0.0) throw CalcError()
                if (a < 0) {
                    val odd = b == floor(b) && abs(b % 2.0) == 1.0
                    if (!odd) throw CalcError()
                    -(-a).pow(1.0 / b)
                } else {
                    a.pow(1.0 / b)
                }
            }
            else -> throw CalcError()
        }
    )

    private fun reduce(lv: Level, p: Int, rightAssoc: Boolean) {
        while (lv.ops.isNotEmpty()) {
            val top = lv.ops.last()
            val tp = prec(top)
            if (tp > p || (tp == p && !rightAssoc)) {
                lv.ops.removeAt(lv.ops.size - 1)
                val b = lv.vals.removeAt(lv.vals.size - 1)
                val a = lv.vals.removeAt(lv.vals.size - 1)
                lv.vals.add(apply(a, top, b))
            } else {
                break
            }
        }
    }

    /** op is one of + - * / ^ (xʸ) r (x^(1/y)). */
    fun op(o: Char) = guard {
        commitEntry()
        val lv = levels.last()
        if (lastWasOp && lv.ops.isNotEmpty()) {
            lv.ops.removeAt(lv.ops.size - 1) // replace the pending operator
        } else {
            lv.vals.add(x)
        }
        reduce(lv, prec(o), o == '^' || o == 'r')
        x = lv.vals.last()
        lv.ops.add(o)
        lastWasOp = true
    }

    private fun finish(lv: Level) {
        if (lastWasOp && lv.ops.isNotEmpty()) lv.ops.removeAt(lv.ops.size - 1) else lv.vals.add(x)
        reduce(lv, 0, false)
        x = lv.vals.last()
        lv.vals.clear()
        lv.ops.clear()
    }

    fun openParen() = guard {
        if (levels.size > 15) throw CalcError()
        if (entering) op('*') // "2(" means 2×(
        levels.add(Level())
        x = 0.0
        lastWasOp = false
    }

    fun closeParen() = guard {
        if (levels.size == 1) return@guard
        commitEntry()
        finish(levels.last())
        levels.removeAt(levels.size - 1)
        lastWasOp = false
    }

    private fun doEquals() {
        commitEntry()
        while (levels.size > 1) {
            finish(levels.last())
            levels.removeAt(levels.size - 1)
            lastWasOp = false
        }
        finish(levels[0])
        lastWasOp = false
    }

    fun calc() = guard { doEquals() }

    // ---------- immediate functions ----------

    private inline fun unary(f: (Double) -> Double) = guard {
        commitEntry()
        x = check(f(x))
        lastWasOp = false
    }

    fun sqrtKey() = unary { if (it < 0) throw CalcError() else sqrt(it) }
    fun cbrtKey() = unary { cbrt(it) }
    fun square() = unary { it * it }
    fun cube() = unary { it * it * it }
    fun log() = unary { if (it <= 0) throw CalcError() else log10(it) }
    fun pow10() = unary { 10.0.pow(it) }
    fun lnKey() = unary { if (it <= 0) throw CalcError() else ln(it) }
    fun expKey() = unary { exp(it) }
    fun reciprocal() = unary { if (it == 0.0) throw CalcError() else 1.0 / it }
    fun factorial() = unary {
        if (it < 0 || it != floor(it) || it > 69) throw CalcError()
        var r = 1.0
        for (i in 2..it.toInt()) r *= i
        r
    }

    private fun toRad(v: Double) = when (angle) {
        Angle.DEG -> (v % 360.0) * PI / 180.0
        Angle.RAD -> v
        Angle.GRA -> (v % 400.0) * PI / 200.0
    }

    private fun fromRad(r: Double) = when (angle) {
        Angle.DEG -> r * 180.0 / PI
        Angle.RAD -> r
        Angle.GRA -> r * 200.0 / PI
    }

    fun trig(t: Trig, inverse: Boolean) {
        val useHyp = hyp
        hyp = false
        unary { v ->
            if (useHyp) {
                when (t) {
                    Trig.SIN -> if (inverse) asinh(v) else sinh(v)
                    Trig.COS -> if (inverse) {
                        if (v < 1) throw CalcError()
                        acosh(v)
                    } else cosh(v)
                    Trig.TAN -> if (inverse) {
                        if (abs(v) >= 1) throw CalcError()
                        atanh(v)
                    } else tanh(v)
                }
            } else if (inverse) {
                when (t) {
                    Trig.SIN -> if (abs(v) > 1) throw CalcError() else fromRad(asin(v))
                    Trig.COS -> if (abs(v) > 1) throw CalcError() else fromRad(acos(v))
                    Trig.TAN -> fromRad(atan(v))
                }
            } else {
                if (abs(v) >= 1e10) throw CalcError()
                if (t == Trig.TAN && angle != Angle.RAD) {
                    val k = v / (if (angle == Angle.DEG) 90.0 else 100.0)
                    if (k == floor(k) && abs(k % 2.0) == 1.0) throw CalcError()
                }
                val rad = toRad(v)
                val raw = when (t) {
                    Trig.SIN -> sin(rad)
                    Trig.COS -> cos(rad)
                    Trig.TAN -> tan(rad)
                }
                if (abs(raw) < 1e-13) 0.0 else raw
            }
        }
    }

    fun pi() = guard {
        entering = false
        x = PI
        lastWasOp = false
    }

    // ---------- memory ----------

    fun mr() = guard {
        entering = false
        x = memory
        lastWasOp = false
    }

    fun min() = guard {
        commitEntry()
        memory = x
    }

    fun swapM() = guard {
        commitEntry()
        val t = memory
        memory = x
        x = t
        lastWasOp = false
    }

    fun mPlus() = guard {
        doEquals()
        memory = check(memory + x)
    }

    fun mMinus() = guard {
        doEquals()
        memory = check(memory - x)
    }

    // ---------- clearing / modes ----------

    fun clear() {
        if (error) { allClear(); return }
        entering = false
        x = 0.0
    }

    fun allClear() {
        error = false
        x = 0.0
        entering = false
        resetStack()
        shift = false
        hyp = false
    }

    fun cycleAngle() {
        angle = Angle.values()[(angle.ordinal + 1) % 3]
    }

    // ---------- display ----------

    /** Mantissa text (with '-' and '.') and optional 2-digit exponent text. */
    fun display(): Pair<String, String?> {
        if (error) return "-E-" to null
        if (entering) {
            var m = if (mant.isEmpty()) "0" else mant.toString()
            if (!m.contains('.')) m += "."
            if (mantNeg) m = "-$m"
            val e = if (inExp) (if (expNeg) "-" else "") + expDigits.toString().padStart(2, '0') else null
            return m to e
        }
        return format(x)
    }

    companion object {
        private val TEN_BILLION = BigDecimal("1e10")

        fun format(v: Double): Pair<String, String?> {
            if (v == 0.0) return "0." to null
            val bd = BigDecimal(v)
            val av = abs(v)
            if (av >= 1e-9 && av < 9999999999.5) {
                val intDigits = if (av >= 1) floor(log10(av)).toInt() + 1 else 1
                val scale = (10 - intDigits).coerceAtLeast(0)
                val r = bd.setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros()
                if (r.signum() != 0 && r.abs() < TEN_BILLION) {
                    var s = r.toPlainString()
                    if (!s.contains('.')) s += "."
                    return s to null
                }
            }
            val r = bd.round(MathContext(10, RoundingMode.HALF_UP))
            val e = r.precision() - r.scale() - 1
            var m = r.movePointLeft(e).stripTrailingZeros().toPlainString()
            if (!m.contains('.')) m += "."
            val es = (if (e < 0) "-" else "") + abs(e).toString().padStart(2, '0')
            return m to es
        }
    }
}
