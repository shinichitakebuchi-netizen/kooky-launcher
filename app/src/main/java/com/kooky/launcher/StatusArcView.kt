package com.kooky.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

class StatusArcView(context: Context) : View(context) {

    var ratio: Float = 1f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    var wifiOn: Boolean = false
        set(value) { field = value; invalidate() }
    var mobileOn: Boolean = false
        set(value) { field = value; invalidate() }
    // true のとき中央にバッテリー残量の数字（数字のみ）を表示
    var showBatteryNumber: Boolean = false
        set(value) { field = value; invalidate() }

    private val orange = Color.parseColor("#FF8C00")
    private val gray = Color.parseColor("#666666")
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
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
        // バッテリー残量弧：普段オレンジ、20%以下で白
        arc.color = if (ratio <= 0.2f) Color.WHITE else orange
        canvas.drawArc(rect, start, sweep * ratio, false, arc)

        if (showBatteryNumber) {
            // 中央にバッテリー残量の数字（数字のみ）
            val pct = (ratio * 100).toInt().toString()
            textPaint.textSize = u * 0.34f
            textPaint.color = if (ratio <= 0.2f) Color.WHITE else orange
            val ty = cy - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(pct, cx, ty, textPaint)
            // モバイルのドットは通常どおり下に表示
            dot.color = if (mobileOn) orange else gray
            val my = cy + u * 0.26f
            for (k in -1..1) canvas.drawCircle(cx + k * u * 0.11f, my, u * 0.022f, dot)
        } else {
            // 中央WiFi：ON=オレンジ / OFF=グレー
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
            // 下モバイル：ON=オレンジ / OFF=グレー
            dot.color = if (mobileOn) orange else gray
            val my = cy + u * 0.26f
            for (k in -1..1) canvas.drawCircle(cx + k * u * 0.11f, my, u * 0.022f, dot)
        }
    }
}