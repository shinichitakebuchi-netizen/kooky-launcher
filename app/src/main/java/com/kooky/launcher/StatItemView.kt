package com.kooky.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

// 日付の下の「アイコン＋数字＋単位＋ゲージ」1個分
class StatItemView(context: Context) : View(context) {

    // 0=通信量 / 1=内部ストレージ
    var iconType: Int = 0
        set(v) { field = v; invalidate() }
    var value: String = "--"
        set(v) { field = v; invalidate() }
    var unit: String = ""
        set(v) { field = v; invalidate() }
    var ratio: Float = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var alert: Boolean = false
        set(v) { field = v; invalidate() }
    var lightMode: Boolean = false
        set(v) { field = v; invalidate() }

    private val orange = Color.parseColor("#FF8C00")
    private val red = Color.parseColor("#FF3B30")
    private val ink get() = if (lightMode) Color.parseColor("#1C1C1E") else Color.WHITE
    private val sub get() = if (lightMode) Color.parseColor("#6E6E73") else Color.parseColor("#CCCCCC")
    private val track get() = if (lightMode) Color.parseColor("#1A000000") else Color.parseColor("#29FFFFFF")

    private val d = resources.displayMetrics.density
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 16f * d; typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f * d }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val icon = 17f * d
        val gap = 5f * d
        val accent = if (alert) red else orange

        valuePaint.color = ink
        unitPaint.color = sub
        val vw = valuePaint.measureText(value)
        val uw = unitPaint.measureText(unit)
        val total = icon + gap + vw + gap * 0.5f + uw
        var x = (w - total) / 2f
        val rowMid = 11f * d

        drawIcon(canvas, x, rowMid - icon / 2f, icon, accent)
        x += icon + gap
        val base = rowMid - (valuePaint.descent() + valuePaint.ascent()) / 2f
        canvas.drawText(value, x, base, valuePaint)
        x += vw + gap * 0.5f
        canvas.drawText(unit, x, base, unitPaint)

        // ゲージ
        val gw = 84f * d
        val gh = 3.5f * d
        val gx = (w - gw) / 2f
        val gy = 27f * d
        fill.color = track
        canvas.drawRoundRect(RectF(gx, gy, gx + gw, gy + gh), gh / 2f, gh / 2f, fill)
        if (ratio > 0f) {
            fill.color = accent
            canvas.drawRoundRect(RectF(gx, gy, gx + gw * ratio, gy + gh), gh / 2f, gh / 2f, fill)
        }
    }

    private fun drawIcon(canvas: Canvas, x: Float, y: Float, s: Float, col: Int) {
        val u = s / 14f
        stroke.color = col
        stroke.strokeWidth = 1.6f * u
        if (iconType == 0) {
            // ↓↑ 矢印
            canvas.drawLine(x + 4 * u, y + 2 * u, x + 4 * u, y + 11 * u, stroke)
            canvas.drawLine(x + 1.5f * u, y + 8.5f * u, x + 4 * u, y + 11 * u, stroke)
            canvas.drawLine(x + 6.5f * u, y + 8.5f * u, x + 4 * u, y + 11 * u, stroke)
            canvas.drawLine(x + 10 * u, y + 12 * u, x + 10 * u, y + 3 * u, stroke)
            canvas.drawLine(x + 7.5f * u, y + 5.5f * u, x + 10 * u, y + 3 * u, stroke)
            canvas.drawLine(x + 12.5f * u, y + 5.5f * u, x + 10 * u, y + 3 * u, stroke)
        } else {
            // ストレージの箱
            canvas.drawRoundRect(RectF(x + 2 * u, y + 2 * u, x + 12 * u, y + 12 * u), 2 * u, 2 * u, stroke)
            stroke.strokeWidth = 1.4f * u
            canvas.drawLine(x + 5 * u, y + 5.5f * u, x + 9 * u, y + 5.5f * u, stroke)
            canvas.drawLine(x + 5 * u, y + 8.5f * u, x + 9 * u, y + 8.5f * u, stroke)
        }
    }
}
