package com.kooky.launcher

import android.content.Context
import android.graphics.Canvas
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
    // モバイル電波の強さ 0〜4（下の4つの点）
    var signalLevel: Int = 0
        set(value) { field = value.coerceIn(0, 4); invalidate() }
    var showBatteryNumber: Boolean = true
        set(value) { field = value; invalidate() }
    // 充電状態：0=非充電 / 1=通常充電(緑) / 2=急速充電(赤)
    var chargeMode: Int = 0
        set(value) { field = value; invalidate() }
    var lightMode: Boolean = false
        set(value) { field = value; invalidate() }

    private val orange = Wa.BENIHI        // 残量・電波：紅緋
    private val green = Wa.WAKATAKE       // 充電中：若竹色
    private val red = Wa.KARAKURENAI      // 20%以下・急速充電：韓紅花

    private val trackColor get() = Wa.line(lightMode)   // 輪っか：利休鼠
    private val subColor get() = Wa.sub(lightMode)
    private val offColor get() = Wa.line(lightMode)
    private val bgColor get() = Wa.bg(lightMode)

    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private var blinkAlpha = 1f
    private var blinkTick = 0

    private val blinker = object : Runnable {
        override fun run() {
            blinkTick++
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
        val dd = resources.displayMetrics.density
        val sw = 1.5f * dd          // アプリ枠と同じくらいの細い線
        val start = 135f
        val sweep = 270f
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)

        // タップ時は円のてっぺんに数字。数字の幅＋余白ぶん輪っかを空ける
        val low = ratio <= 0.2f
        val chargeColor = if (chargeMode == 2) red else green
        val pct = (ratio * 100).toInt().toString()
        textPaint.textSize = u * 0.20f
        val gapDeg = if (showBatteryNumber)
            Math.toDegrees(((textPaint.measureText(pct) + 7f * dd) / r).toDouble()).toFloat()
        else 0f
        val topA = 270f
        val gapL = topA - gapDeg / 2f
        val gapR = topA + gapDeg / 2f
        val end = start + sweep

        // 背景の輪っか
        arc.strokeWidth = sw
        arc.color = trackColor
        if (gapDeg > 0f) {
            canvas.drawArc(rect, start, gapL - start, false, arc)
            canvas.drawArc(rect, gapR, end - gapR, false, arc)
        } else {
            canvas.drawArc(rect, start, sweep, false, arc)
        }

        // 残量の輪っか：充電中は若竹色/韓紅花（点滅）、20%以下は韓紅花、それ以外は紅緋
        if (chargeMode != 0) {
            arc.color = chargeColor
            arc.alpha = (blinkAlpha * 255).toInt().coerceIn(0, 255)
        } else {
            arc.color = if (low) red else orange
            arc.alpha = 255
        }
        if (ratio > 0f) {
            if (gapDeg > 0f) {
                // 数字のすき間を飛ばして、見えている輪っかだけで0〜100%を表す
                val leftLen = gapL - start
                val rightLen = end - gapR
                val fillLen = (leftLen + rightLen) * ratio
                canvas.drawArc(rect, start, minOf(fillLen, leftLen), false, arc)
                if (fillLen > leftLen) canvas.drawArc(rect, gapR, fillLen - leftLen, false, arc)
            } else {
                canvas.drawArc(rect, start, sweep * ratio, false, arc)
            }
        }
        arc.alpha = 255

        // てっぺんの数字
        if (showBatteryNumber) {
            textPaint.color = if (chargeMode != 0) chargeColor else if (low) red else orange
            val ty = (cy - r) - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(pct, cx, ty, textPaint)
        }

        // 真ん中
        val iy = u * 0.5f
        when {
            chargeMode != 0 -> drawBolt(canvas, cx, iy, u, chargeColor, blinkAlpha)
            wifiOn -> drawWifi(canvas, cx, iy, u)
            mobileOn -> drawBars(canvas, cx, iy, u)
            else -> drawNoNet(canvas, cx, iy, u)
        }

        // 下のすき間に、輪っかと同じ円に沿って4つの点＝モバイル電波の強さ（左→右）
        val angs = floatArrayOf(117f, 99f, 81f, 63f)
        for (k in 0 until 4) {
            val a = Math.toRadians(angs[k].toDouble())
            fill.color = if (mobileOn && k < signalLevel) orange else offColor
            canvas.drawCircle(cx + (r * Math.cos(a)).toFloat(), cy + (r * Math.sin(a)).toFloat(),
                u * 0.026f, fill)
        }
    }

    private fun drawWifi(canvas: Canvas, cx: Float, iy: Float, u: Float) {
        val apexY = iy + u * 0.11f
        line.color = orange
        line.strokeWidth = 1.3f * resources.displayMetrics.density
        for (i in 1..3) {
            val rr = u * 0.085f * i
            canvas.drawArc(RectF(cx - rr, apexY - rr, cx + rr, apexY + rr), 225f, 90f, false, line)
        }
        fill.color = orange
        canvas.drawCircle(cx, apexY, u * 0.024f, fill)
    }

    private fun drawBars(canvas: Canvas, cx: Float, iy: Float, u: Float) {
        val bw = 1.6f * resources.displayMetrics.density
        val base = iy + u * 0.12f
        for (i in 0 until 4) {
            val h = u * (0.07f + i * 0.055f)
            val left = cx - u * 0.15f + i * u * 0.085f
            fill.color = if (i < signalLevel) orange else offColor
            canvas.drawRoundRect(RectF(left, base - h, left + bw, base), bw / 2f, bw / 2f, fill)
        }
    }

    private fun drawNoNet(canvas: Canvas, cx: Float, iy: Float, u: Float) {
        val gr = u * 0.13f
        line.color = subColor
        line.strokeWidth = 1.2f * resources.displayMetrics.density
        canvas.drawCircle(cx, iy, gr, line)
        line.strokeWidth = 1.0f * resources.displayMetrics.density
        canvas.drawOval(RectF(cx - gr * 0.45f, iy - gr, cx + gr * 0.45f, iy + gr), line)
        canvas.drawLine(cx - gr, iy, cx + gr, iy, line)
        // 斜線（背景色で縁取り）
        val d = gr * 1.15f
        line.color = bgColor
        line.strokeWidth = 3.2f * resources.displayMetrics.density
        canvas.drawLine(cx - d, iy - d, cx + d, iy + d, line)
        line.color = subColor
        line.strokeWidth = 1.2f * resources.displayMetrics.density
        canvas.drawLine(cx - d, iy - d, cx + d, iy + d, line)
    }

    private fun drawBolt(canvas: Canvas, cx: Float, cy: Float, u: Float, color: Int, alpha: Float) {
        fill.color = color
        fill.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        val s = u * 0.30f
        val path = Path()
        path.moveTo(cx + s * 0.12f, cy - s * 0.55f)
        path.lineTo(cx - s * 0.40f, cy + s * 0.08f)
        path.lineTo(cx - s * 0.05f, cy + s * 0.08f)
        path.lineTo(cx - s * 0.16f, cy + s * 0.55f)
        path.lineTo(cx + s * 0.44f, cy - s * 0.12f)
        path.lineTo(cx + s * 0.08f, cy - s * 0.12f)
        path.close()
        canvas.drawPath(path, fill)
        fill.alpha = 255
    }
}
