package com.udi.calculator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/** A reflective LCD with 7-segment digits, faint "ghost" segments and annunciators. */
class LcdView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var mantissa = "0."
    private var exponent: String? = null
    private var shiftOn = false
    private var hypOn = false
    private var memOn = false
    private var parenOn = false
    private var angle = "DEG"

    private val density = resources.displayMetrics.density
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#A4B190") }
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#22000000") }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1A2016") }
    private val ghost = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#98A585") }
    private val annInk = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A2016")
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val annGhost = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#98A585")
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val rect = RectF()

    private data class Cell(val ch: Char, val dot: Boolean)

    fun update(
        mantissa: String, exponent: String?,
        shift: Boolean, hyp: Boolean, memory: Boolean, paren: Boolean, angle: String,
    ) {
        this.mantissa = mantissa
        this.exponent = exponent
        shiftOn = shift
        hypOn = hyp
        memOn = memory
        parenOn = paren
        this.angle = angle
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val corner = 6 * density
        rect.set(0f, 0f, w, h)
        c.drawRoundRect(rect, corner, corner, bgPaint)
        rect.set(0f, 0f, w, 3 * density)
        c.drawRect(rect, shadePaint)

        val pad = 12 * density

        // Annunciators
        val annSize = 11 * density
        annInk.textSize = annSize
        annGhost.textSize = annSize
        val annY = pad + annSize
        var ax = pad
        ax = drawAnn(c, "S", shiftOn, ax, annY)
        ax = drawAnn(c, "hyp", hypOn, ax, annY)
        ax = drawAnn(c, "M", memOn, ax, annY)
        drawAnn(c, "( )", parenOn, ax, annY)
        var rx = w - pad
        for (a in listOf("GRA", "RAD", "DEG")) {
            rx -= annInk.measureText(a)
            drawAnn(c, a, angle == a, rx, annY)
            rx -= 10 * density
        }

        // Digits
        val top = annY + 10 * density
        val bottom = h - pad
        val mantCells = 11
        val expScale = 0.62f
        val pitch = (w - 2 * pad) / (mantCells + 3 * expScale + 0.7f)
        var dw = pitch * 0.72f
        var dh = dw * 1.8f
        if (dh > bottom - top) {
            dh = bottom - top
            dw = dh / 1.8f
        }
        val digitTop = bottom - dh
        val skew = 0.08f

        c.save()
        c.translate(skew * bottom, 0f)
        c.skew(-skew, 0f)

        val cells = toCells(mantissa, mantCells)
        for (i in 0 until mantCells) {
            drawCell(c, pad + i * pitch, digitTop, dw, dh, cells[i], pitch, true)
        }
        val ex = pad + (mantCells + 0.7f) * pitch
        val ecells = toCells(exponent ?: "", 3)
        val ep = pitch * expScale
        for (i in 0 until 3) {
            drawCell(c, ex + i * ep, digitTop, dw * expScale, dh * expScale, ecells[i], ep, false)
        }
        c.restore()
    }

    private fun drawAnn(c: Canvas, text: String, on: Boolean, x: Float, y: Float): Float {
        c.drawText(text, x, y, if (on) annInk else annGhost)
        return x + annInk.measureText(text) + 12 * density
    }

    private fun toCells(text: String, n: Int): List<Cell> {
        val list = ArrayList<Cell>()
        for (ch in text) {
            if (ch == '.') {
                if (list.isEmpty()) list.add(Cell(' ', true))
                else list[list.size - 1] = list[list.size - 1].copy(dot = true)
            } else {
                list.add(Cell(ch, false))
            }
        }
        val trimmed = if (list.size > n) list.subList(list.size - n, list.size).toList() else list
        return List(n - trimmed.size) { Cell(' ', false) } + trimmed
    }

    private fun drawCell(c: Canvas, x: Float, y: Float, w: Float, h: Float, cell: Cell, pitch: Float, withDot: Boolean) {
        val t = w * 0.17f
        val g = t * 0.18f
        val half = t / 2
        val mask = MASKS[cell.ch] ?: 0
        for (seg in 0..6) {
            when (seg) {
                0 -> rect.set(x + half + g, y, x + w - half - g, y + t)
                1 -> rect.set(x + w - t, y + half + g, x + w, y + h / 2 - g)
                2 -> rect.set(x + w - t, y + h / 2 + g, x + w, y + h - half - g)
                3 -> rect.set(x + half + g, y + h - t, x + w - half - g, y + h)
                4 -> rect.set(x, y + h / 2 + g, x + t, y + h - half - g)
                5 -> rect.set(x, y + half + g, x + t, y + h / 2 - g)
                else -> rect.set(x + half + g, y + h / 2 - half, x + w - half - g, y + h / 2 + half)
            }
            c.drawRoundRect(rect, half, half, if (mask and (1 shl seg) != 0) ink else ghost)
        }
        if (withDot) {
            val dx = x + w + (pitch - w) * 0.3f
            rect.set(dx, y + h - t, dx + t, y + h)
            c.drawRoundRect(rect, half, half, if (cell.dot) ink else ghost)
        }
    }

    companion object {
        // bits: a=1 b=2 c=4 d=8 e=16 f=32 g=64
        private val MASKS = mapOf(
            '0' to 63, '1' to 6, '2' to 91, '3' to 79, '4' to 102,
            '5' to 109, '6' to 125, '7' to 39, '8' to 127, '9' to 111,
            '-' to 64, 'E' to 121,
        )
    }
}
