package com.udi.calculator

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.Html
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private val engine = Engine()
    private lateinit var lcd: LcdView

    private enum class Style { SHIFT, FUNC, NUM, OP, CLEAR }

    private class Key(
        val label: String,
        val second: String?,
        val style: Style,
        val keepsHyp: Boolean = false,
        val shifted: (() -> Unit)? = null,
        val primary: () -> Unit,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        lcd = findViewById(R.id.lcd)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        engine.memory = prefs.getString(PREF_MEMORY, "0")?.toDoubleOrNull() ?: 0.0
        engine.angle = runCatching { Engine.Angle.valueOf(prefs.getString(PREF_ANGLE, "DEG")!!) }
            .getOrDefault(Engine.Angle.DEG)

        buildPad(findViewById(R.id.fnPad), functionRows(), withLabels = true)
        buildPad(findViewById(R.id.numPad), numberRows(), withLabels = false)
        render()
    }

    override fun onPause() {
        super.onPause()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(PREF_MEMORY, engine.memory.toString())
            .putString(PREF_ANGLE, engine.angle.name)
            .apply()
    }

    // ---------- Key layout ----------

    private fun sup(base: String, s: String) = "$base<sup><small>$s</small></sup>"

    private fun functionRows(): List<List<Key>> = listOf(
        listOf(
            Key("SHIFT", null, Style.SHIFT, keepsHyp = true) { engine.shift = !engine.shift },
            Key("DRG", null, Style.FUNC) { engine.cycleAngle() },
            Key("hyp", null, Style.FUNC, keepsHyp = true) { engine.hyp = !engine.hyp },
            Key("sin", sup("sin", "-1"), Style.FUNC, shifted = { engine.trig(Engine.Trig.SIN, true) }) {
                engine.trig(Engine.Trig.SIN, false)
            },
            Key("cos", sup("cos", "-1"), Style.FUNC, shifted = { engine.trig(Engine.Trig.COS, true) }) {
                engine.trig(Engine.Trig.COS, false)
            },
            Key("tan", sup("tan", "-1"), Style.FUNC, shifted = { engine.trig(Engine.Trig.TAN, true) }) {
                engine.trig(Engine.Trig.TAN, false)
            },
        ),
        listOf(
            Key("√", sup("", "3") + "√", Style.FUNC, shifted = { engine.cbrtKey() }) { engine.sqrtKey() },
            Key(sup("x", "2"), sup("x", "3"), Style.FUNC, shifted = { engine.cube() }) { engine.square() },
            Key("log", sup("10", "x"), Style.FUNC, shifted = { engine.pow10() }) { engine.log() },
            Key("ln", sup("e", "x"), Style.FUNC, shifted = { engine.expKey() }) { engine.lnKey() },
            Key(sup("x", "y"), sup("x", "1/y"), Style.FUNC, shifted = { engine.op('r') }) { engine.op('^') },
            Key(sup("x", "-1"), "x!", Style.FUNC, shifted = { engine.factorial() }) { engine.reciprocal() },
        ),
        listOf(
            Key("±", null, Style.FUNC) { engine.negate() },
            Key("(", null, Style.FUNC) { engine.openParen() },
            Key(")", null, Style.FUNC) { engine.closeParen() },
            Key("π", null, Style.FUNC) { engine.pi() },
            Key("Min", "X↔M", Style.FUNC, shifted = { engine.swapM() }) { engine.min() },
            Key("M+", "M−", Style.FUNC, shifted = { engine.mMinus() }) { engine.mPlus() },
        ),
    )

    private fun numberRows(): List<List<Key>> {
        fun d(ch: Char) = Key(ch.toString(), null, Style.NUM) { engine.digit(ch) }
        return listOf(
            listOf(d('7'), d('8'), d('9'),
                Key("C", null, Style.CLEAR) { engine.clear() },
                Key("AC", null, Style.CLEAR) { engine.allClear() }),
            listOf(d('4'), d('5'), d('6'),
                Key("×", null, Style.OP) { engine.op('*') },
                Key("÷", null, Style.OP) { engine.op('/') }),
            listOf(d('1'), d('2'), d('3'),
                Key("+", null, Style.OP) { engine.op('+') },
                Key("−", null, Style.OP) { engine.op('-') }),
            listOf(d('0'),
                Key(".", null, Style.NUM) { engine.dot() },
                Key("EXP", null, Style.NUM) { engine.expEntry() },
                Key("MR", null, Style.NUM) { engine.mr() },
                Key("=", null, Style.OP) { engine.calc() }),
        )
    }

    private fun buildPad(pad: LinearLayout, rows: List<List<Key>>, withLabels: Boolean) {
        val gapH = dp(4)
        val gapV = dp(4)
        for (row in rows) {
            val rowLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pad.addView(rowLayout, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            for (key in row) {
                val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                if (withLabels) {
                    val label = TextView(this).apply {
                        text = if (key.second != null) html(key.second) else ""
                        setTextColor(Color.parseColor("#E2AE45"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        gravity = Gravity.CENTER
                        maxLines = 1
                        includeFontPadding = false
                    }
                    cell.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(17)))
                }
                cell.addView(keyView(key), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                lp.setMargins(gapH, gapV, gapH, gapV)
                rowLayout.addView(cell, lp)
            }
        }
    }

    private class Look(val face: Int, val edge: Int, val text: Int, val sizeSp: Float)

    private fun lookFor(key: Key): Look = when (key.style) {
        Style.SHIFT -> Look(0xFFD9A441.toInt(), 0xFF7E5C1C.toInt(), 0xFF1B1406.toInt(), 13f)
        Style.FUNC -> Look(0xFF3A3E44.toInt(), 0xFF17181B.toInt(), 0xFFEDEEEF.toInt(), 16f)
        Style.NUM -> Look(0xFF2B2E32.toInt(), 0xFF111214.toInt(), 0xFFF4F4F2.toInt(),
            if (key.label.length > 1) 17f else 25f)
        Style.OP -> Look(0xFF4B5057.toInt(), 0xFF222428.toInt(), 0xFFF4F4F2.toInt(), 25f)
        Style.CLEAR -> Look(0xFFD8643A.toInt(), 0xFF7F361C.toInt(), 0xFFFFFFFF.toInt(), 18f)
    }

    private fun keyView(key: Key): TextView {
        val look = lookFor(key)
        val r = dp(9).toFloat()
        val edge = GradientDrawable().apply { cornerRadius = r; setColor(look.edge) }
        val face = GradientDrawable().apply { cornerRadius = r; setColor(look.face) }
        val layers = LayerDrawable(arrayOf<Drawable>(edge, face))
        layers.setLayerInset(1, 0, 0, 0, dp(3))
        val mask = GradientDrawable().apply { cornerRadius = r; setColor(Color.WHITE) }

        return TextView(this).apply {
            text = html(key.label)
            gravity = Gravity.CENTER
            setTextColor(look.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, look.sizeSp)
            setPadding(0, 0, 0, dp(3))
            background = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), layers, mask)
            isClickable = true
            isFocusable = true
            contentDescription = key.label
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                press(key)
            }
        }
    }

    private fun press(key: Key) {
        val shifted = key.shifted
        when {
            key.style == Style.SHIFT -> key.primary()
            engine.shift && shifted != null -> { engine.shift = false; shifted() }
            else -> { engine.shift = false; key.primary() }
        }
        if (!key.keepsHyp) engine.hyp = false
        render()
    }

    private fun render() {
        val (m, e) = engine.display()
        lcd.update(
            m, e,
            shift = engine.shift,
            hyp = engine.hyp,
            memory = engine.memory != 0.0,
            paren = engine.openParens > 0,
            angle = engine.angle.name,
        )
    }

    private fun html(s: String) = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val PREFS = "calc"
        private const val PREF_MEMORY = "memory"
        private const val PREF_ANGLE = "angle"
    }
}
