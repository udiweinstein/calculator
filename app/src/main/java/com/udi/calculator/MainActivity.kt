package com.udi.calculator

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var expressionView: TextView
    private lateinit var resultView: TextView

    /** The expression as typed, using the symbols + − × ÷ % ( ) . */
    private var expr = ""
    /** True right after '=' — the next digit starts a new calculation. */
    private var justEvaluated = false
    /** Error to show once on the result line. */
    private var pendingError: Int? = null

    private enum class Kind { DIGIT, FUNC, OP, EQUALS }
    private class Key(val label: String, val kind: Kind, val action: () -> Unit)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        expressionView = findViewById(R.id.expression)
        resultView = findViewById(R.id.result)

        val backspace = findViewById<ImageView>(R.id.backspace)
        backspace.setOnClickListener { haptic(it); backspace(); refresh() }
        backspace.setOnLongClickListener { haptic(it); clear(); refresh(); true }

        expressionView.setOnLongClickListener {
            if (expr.isNotEmpty()) {
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("result", expr.replace('−', '-')))
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
            true
        }

        savedInstanceState?.let {
            expr = it.getString(STATE_EXPR, "")
            justEvaluated = it.getBoolean(STATE_EVALUATED, false)
        }

        buildKeypad(findViewById(R.id.keypad))
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_EXPR, expr)
        outState.putBoolean(STATE_EVALUATED, justEvaluated)
    }

    // ---------- Keypad ----------

    private fun buildKeypad(keypad: LinearLayout) {
        val rows = listOf(
            listOf(
                Key("C", Kind.FUNC) { clear() },
                Key("( )", Kind.FUNC) { paren() },
                Key("%", Kind.FUNC) { percent() },
                Key("÷", Kind.OP) { operator('÷') },
            ),
            listOf(digitKey('7'), digitKey('8'), digitKey('9'), Key("×", Kind.OP) { operator('×') }),
            listOf(digitKey('4'), digitKey('5'), digitKey('6'), Key("−", Kind.OP) { operator('−') }),
            listOf(digitKey('1'), digitKey('2'), digitKey('3'), Key("+", Kind.OP) { operator('+') }),
            listOf(
                Key("±", Kind.FUNC) { negate() },
                digitKey('0'),
                Key(".", Kind.DIGIT) { dot() },
                Key("=", Kind.EQUALS) { equals() },
            ),
        )
        val gap = dp(5)
        for (row in rows) {
            val rowLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            keypad.addView(rowLayout, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            for (key in row) {
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                lp.setMargins(gap, gap, gap, gap)
                rowLayout.addView(makeKeyView(key), lp)
            }
        }
    }

    private fun digitKey(d: Char) = Key(d.toString(), Kind.DIGIT) { inputDigit(d) }

    private fun makeKeyView(key: Key): View {
        val bg: Int
        val fg: Int
        when (key.kind) {
            Kind.DIGIT -> { bg = getColor(R.color.key_digit); fg = getColor(R.color.fg) }
            Kind.FUNC -> { bg = getColor(R.color.key_func); fg = getColor(R.color.func_text) }
            Kind.OP -> { bg = getColor(R.color.key_func); fg = getColor(R.color.accent) }
            Kind.EQUALS -> { bg = getColor(R.color.accent); fg = getColor(R.color.on_accent) }
        }
        val radius = dp(48).toFloat()
        val shape = GradientDrawable().apply { cornerRadius = radius; setColor(bg) }
        val mask = GradientDrawable().apply { cornerRadius = radius; setColor(Color.WHITE) }

        return TextView(this).apply {
            text = key.label
            gravity = Gravity.CENTER
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (key.kind == Kind.DIGIT) 30f else 27f)
            background = RippleDrawable(ColorStateList.valueOf(getColor(R.color.ripple)), shape, mask)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                haptic(it)
                key.action()
                refresh()
            }
        }
    }

    // ---------- Input logic ----------

    private fun isOperator(c: Char?) = c != null && c in "+−×÷"
    private fun endsValue(c: Char?) = c != null && (c.isDigit() || c == ')' || c == '%' || c == '.')

    /** Index where the number at the end of the expression starts. */
    private fun currentNumberStart(): Int {
        var i = expr.length
        while (i > 0) {
            val c = expr[i - 1]
            if (c.isDigit() || c == '.' || c == 'e') i--
            else if (c == '−' && i >= 2 && expr[i - 2] == 'e') i--
            else break
        }
        return i
    }

    private fun currentNumber() = expr.substring(currentNumberStart())

    private fun inputDigit(d: Char) {
        if (justEvaluated) { expr = ""; justEvaluated = false }
        val last = expr.lastOrNull()
        if (last == ')' || last == '%') expr += "×"
        val num = currentNumber()
        if (!num.contains('e') && num.count { it.isDigit() } >= MAX_DIGITS) {
            Toast.makeText(this, R.string.max_digits, Toast.LENGTH_SHORT).show()
            return
        }
        expr = if (num == "0") expr.dropLast(1) + d else expr + d
    }

    private fun dot() {
        if (justEvaluated) { expr = ""; justEvaluated = false }
        val last = expr.lastOrNull()
        if (last == ')' || last == '%') expr += "×"
        val num = currentNumber()
        if (num.contains('.') || num.contains('e')) return
        expr += if (num.isEmpty()) "0." else "."
    }

    private fun operator(op: Char) {
        justEvaluated = false
        val last = expr.lastOrNull()
        when {
            last == null -> if (op == '−') expr = "−"
            last == '(' -> if (op == '−') expr += "−"
            last == 'e' -> return
            isOperator(last) -> {
                val unary = expr.length == 1 || expr[expr.length - 2] == '('
                if (!unary) expr = expr.dropLast(1) + op
            }
            else -> expr += op
        }
    }

    private fun percent() {
        justEvaluated = false
        if (endsValue(expr.lastOrNull()) && expr.lastOrNull() != '.') expr += "%"
    }

    private fun paren() {
        justEvaluated = false
        val open = expr.count { it == '(' } - expr.count { it == ')' }
        val last = expr.lastOrNull()
        if (open > 0 && endsValue(last)) {
            expr += ")"
        } else {
            if (endsValue(last)) expr += "×"
            expr += "("
        }
    }

    /** ± : wraps the current number as (−n), or unwraps it. */
    private fun negate() {
        justEvaluated = false
        val start = currentNumberStart()
        val num = expr.substring(start)
        when {
            start >= 2 && expr.substring(start - 2, start) == "(−" ->
                expr = expr.substring(0, start - 2) + num
            start == 1 && expr[0] == '−' ->
                expr = num
            num.isEmpty() -> {
                val last = expr.lastOrNull()
                if (last == ')' || last == '%') expr += "×"
                expr += "(−"
            }
            else -> expr = expr.substring(0, start) + "(−" + num
        }
    }

    private fun backspace() {
        justEvaluated = false
        expr = expr.dropLast(1)
    }

    private fun clear() {
        expr = ""
        justEvaluated = false
    }

    private fun equals() {
        if (expr.isEmpty() || justEvaluated) return
        try {
            expr = Evaluator.format(Evaluator.evaluate(prepare(expr)))
            justEvaluated = true
        } catch (e: CalcException) {
            pendingError = if (e.kind == CalcException.Kind.DIV_ZERO) R.string.error_div0 else R.string.error_syntax
        } catch (e: Exception) {
            pendingError = R.string.error_syntax
        }
    }

    /** Drops a dangling operator / '(' and closes open parentheses. */
    private fun prepare(e: String): String {
        var s = e
        while (s.isNotEmpty() && (isOperator(s.last()) || s.last() == '(' || s.last() == 'e')) s = s.dropLast(1)
        val open = s.count { it == '(' } - s.count { it == ')' }
        return s + ")".repeat(maxOf(open, 0))
    }

    private fun hasOperation(e: String) = e.any { it in "+×÷%(" } || e.drop(1).contains('−')

    // ---------- Display ----------

    private fun refresh() {
        expressionView.text = expr
        val error = pendingError
        if (error != null) {
            pendingError = null
            resultView.setTextColor(getColor(R.color.error))
            resultView.setText(error)
            return
        }
        resultView.setTextColor(getColor(R.color.muted))
        resultView.text = if (justEvaluated || !hasOperation(expr)) "" else preview()
    }

    private fun preview(): String = try {
        Evaluator.format(Evaluator.evaluate(prepare(expr)))
    } catch (e: Exception) {
        ""
    }

    private fun haptic(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val STATE_EXPR = "expr"
        private const val STATE_EVALUATED = "evaluated"
        private const val MAX_DIGITS = 15
    }
}
