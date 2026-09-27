package com.kooky.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

class StatusArcView(context: Context) : View(context) {

    var ratio: Float = 1f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    var wifiOn: Boolean = false
        set(value) { field = value; invalidate() }
    var mobileOn: Boolean = false
        set(value) { field = value; invalidate() }
    var showBatteryNumber: Boolean = false
        set(value) { field = value; invalidate() }
    // 充電状態：0=非充電 / 1=通常充電(緑) / 2=急速充電(赤)
    var chargeMode: Int = 0
        set(value) { field = value; invalidate() }

    private val orange = Color.parseColor("#FF8C00")
    private val green = Color.parseColor("#4CD964")
    private val red = Color.parseColor("#FF3B30")
    private val gray = Color.parseColor("#666666")
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
    }

    private var blinkAlpha = 1f
    private var blinkTick = 0

    private val blinker = object : Runnable {
        override fun run() {
            blinkTick++
            // ゆっくり点滅（デモ相当）
            blinkAlpha = 0.35f + Math.abs(Math.sin(blinkTick / 22.0)).toFloat() * 0.65f
            invalidate()
            if (chargeMode != 0) postDelayed(this, 40)
        }
    }

    fun startBlink() {
        removeCallbacks(blinker)
        if (chargeMode != 0) post(blinker)
    }

    fun stopBlink() {
        removeCallbacks(blinker)
        blinkAlpha = 1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val u = minOf(width, height).toFloat()
        val r = u * 0.40f

        val start = 135f; val sweep = 270f
        arc.strokeWidth = u * 0.05f
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)

        // バッテリー背景弧
        arc.color = Color.parseColor("#33FFFFFF")
        canvas.drawArc(rect, start, sweep, false, arc)

        // 残量弧の色：充電中は緑/赤（点滅）、通常はオレンジ（20%以下で白）
        val chargeColor = when (chargeMode) {
            1 -> green
            2 -> red
            else -> 0
        }
        if (chargeMode != 0) {
            arc.color = chargeColor
            arc.alpha = (blinkAlpha * 255).toInt().coerceIn(0, 255)
        } else {
            arc.color = if (ratio <= 0.2f) Color.WHITE else orange
            arc.alpha = 255
        }
        canvas.drawArc(rect, start, sweep * ratio, false, arc)
        arc.alpha = 255

        if (chargeMode != 0) {
            // 充電中：中央に大きい稲妻マーク（点滅）
            drawBolt(canvas, cx, cy, u, chargeColor, blinkAlpha)
        } else if (showBatteryNumber) {
            val pct = (ratio * 100).toInt().toString()
            textPaint.textSize = u * 0.42f
            textPaint.color = if (ratio <= 0.2f) Color.WHITE else orange
            val ty = cy - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(pct, cx, ty, textPaint)
        } else {
            // 中央WiFi
            val wc = if (wifiOn) orange else gray
            line.color = wc; line.strokeWidth = u * 0.035f
            dot.color = wc
            val apexY = cy + u * 0.10f
            for (i in 1..3) {
                val rr = u * 0.085f * i
                val wr = RectF(cx - rr, apexY - rr, cx + rr, apexY + rr)
                canvas.drawArc(wr, 225f, 90f, false, line)
            }
            canvas.drawCircle(cx, apexY, u * 0.018f, dot)
            // 下モバイル
            dot.color = if (mobileOn) orange else gray
            val my = cy + u * 0.26f
            for (k in -1..1) {
                canvas.drawCircle(cx + k * u * 0.11f, my, u * 0.022f, dot)
            }
        }
    }

    private fun drawBolt(canvas: Canvas, cx: Float, cy: Float, u: Float, color: Int, alpha: Float) {
        boltPaint.color = color
        boltPaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        val s = u * 0.30f  // 稲妻の大きさ
        val path = Path()
        path.moveTo(cx + s * 0.12f, cy - s * 0.55f)
        path.lineTo(cx - s * 0.40f, cy + s * 0.08f)
        path.lineTo(cx - s * 0.05f, cy + s * 0.08f)
        path.lineTo(cx - s * 0.16f, cy + s * 0.55f)
        path.lineTo(cx + s * 0.44f, cy - s * 0.12f)
        path.lineTo(cx + s * 0.08f, cy - s * 0.12f)
        path.close()
        canvas.drawPath(path, boltPaint)
        boltPaint.alpha = 255
    }
}
