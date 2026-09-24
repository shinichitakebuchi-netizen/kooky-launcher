package com.kooky.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

class StatusArcView(context: Context) : View(context) {

    var ratio: Float = 1f          // バッテリー残量 0..1
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    var wifiOn: Boolean = true
        set(value) { field = value; invalidate() }
    var mobileOn: Boolean = true
        set(value) { field = value; invalidate() }

    private val orange = Color.parseColor("#FF8C00")
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val u = minOf(w, h)
        val r = u * 0.40f
        val start = 135f; val sweep = 270f

        // バッテリー背景の弧（薄い白）
        arc.strokeWidth = u * 0.09f
        arc.color = Color.parseColor("#33FFFFFF")
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(rect, start, sweep, false, arc)

        // バッテリー残量の弧：普段オレンジ、20%以下で白
        arc.color = if (ratio <= 0.2f) Color.WHITE else orange
        canvas.drawArc(rect, start, sweep * ratio, false, arc)

        // 中央WiFi（弧3本＋点）
        val wc = if (wifiOn) Color.WHITE else Color.parseColor("#40FFFFFF")
        line.color = wc; line.strokeWidth = u * 0.035f
        dot.color = wc
        val wcy = cy + u * 0.05f
        for (i in 1..3) {
            val rr = u * 0.075f * i
            val wr = RectF(cx - rr, wcy - rr, cx + rr, wcy + rr)
            canvas.drawArc(wr, 225f, 90f, false, line)
        }
        canvas.drawCircle(cx, wcy, u * 0.016f, dot)

        // 下モバイル（3ドット）
        dot.color = if (mobileOn) Color.WHITE else Color.parseColor("#40FFFFFF")
        for (k in -1..1) {
            canvas.drawCircle(cx + k * u * 0.10f, cy + u * 0.24f, u * 0.022f, dot)
        }
    }
}