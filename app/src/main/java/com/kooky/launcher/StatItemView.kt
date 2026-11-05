package com.kooky.launcher

import android.content.Context
import android.graphics.Canvas
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
    // 文字のフォント（設定の「文字」で選んだもの）
    var font: android.graphics.Typeface = android.graphics.Typeface.SERIF
        set(v) {
            field = v
            valuePaint.typeface = android.graphics.Typeface.create(v, android.graphics.Typeface.BOLD)
            unitPaint.typeface = v
            invalidate()
        }

    private val orange = Wa.BENIHI          // ゲージ：紅緋
    private val red = Wa.KARAKURENAI        // 上限近く：韓紅花
    private val ink get() = Wa.ink(lightMode)
    private val sub get() = Wa.sub(lightMode)
    private val track get() = Wa.line(lightMode)

    // 全体の大きさ（1.0=日付と同じくらい、0.6=その60%）
    private val k = 0.6f
    private val d = resources.displayMetrics.density * k
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
        // アイコンなし：数字と単位だけを真ん中に
        val total = vw + gap * 0.5f + uw
        var x = (w - total) / 2f
        val rowMid = 11f * d
        val base = rowMid - (valuePaint.descent() + valuePaint.ascent()) / 2f
        canvas.drawText(value, x, base, valuePaint)
        x += vw + gap * 0.5f
        canvas.drawText(unit, x, base, unitPaint)

        // ゲージ
        val gw = 84f * d
        val gh = 1f * resources.displayMetrics.density   // 細い一本線
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
