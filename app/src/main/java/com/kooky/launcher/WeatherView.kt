package com.kooky.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

class WeatherView(context: Context) : View(context) {

    // 0=はれ 1=くもり 2=あめ 3=かみなり 4=ゆき 5=はれのよる
    var weatherType: Int = 0
        set(value) { field = value; invalidate() }
    // ライトモード：白い雲・雪・星をグレーに、光条をオレンジに
    var lightMode: Boolean = false
        set(value) { field = value; invalidate() }

    private val orange = Color.parseColor("#FF8C00")
    private val blue = Color.parseColor("#4AA3FF")
    private val lightMain = Color.parseColor("#A1A1A6")
    private val lightSoft = Color.parseColor("#D1D1D6")

    private var t = 0f
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val soft = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CCCCCC") }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeCap = Paint.Cap.ROUND
    }

    private data class Particle(var x: Float, var y: Float, var phase: Float)
    private val drops = ArrayList<Particle>()
    private val flakes = ArrayList<Particle>()

    private val anim = object : Runnable {
        override fun run() {
            t += 0.05f
            invalidate()
            postDelayed(this, 33)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(anim); post(anim)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(anim)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val u = minOf(w, h)
        white.color = if (lightMode) lightMain else Color.WHITE
        soft.color = if (lightMode) lightSoft else Color.parseColor("#CCCCCC")
        when (weatherType) {
            0 -> drawSun(canvas, cx, cy, u)
            1 -> drawCloud(canvas, cx, cy, u)
            2 -> drawRain(canvas, cx, cy, u)
            3 -> drawThunder(canvas, cx, cy, u)
            4 -> drawSnow(canvas, cx, cy, u)
            5 -> drawNight(canvas, cx, cy, u)
        }
    }

    private fun drawSun(canvas: Canvas, cx: Float, cy: Float, u: Float) {
        val r = u * 0.16f
        // 太陽本体＝オレンジ（中心を明るく）
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx, cy, r, Color.parseColor("#FFB84D"), orange, Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(cx, cy, r, body)
        // 光条＝白（ライトモードはオレンジ）
        stroke.color = if (lightMode) orange else Color.WHITE
        stroke.strokeWidth = u * 0.03f
        val breathe = (Math.sin(t.toDouble()) * u * 0.02f).toFloat()
        val inner = r + u * 0.06f + breathe
        val outer = inner + u * 0.09f
        for (i in 0 until 12) {
            val a = i / 12f * Math.PI.toFloat() * 2f
            val sx = cx + Math.cos(a.toDouble()).toFloat() * inner
            val sy = cy + Math.sin(a.toDouble()).toFloat() * inner
            val ex = cx + Math.cos(a.toDouble()).toFloat() * outer
            val ey = cy + Math.sin(a.toDouble()).toFloat() * outer
            canvas.drawLine(sx, sy, ex, ey, stroke)
        }
    }

    private fun cloudPath(cx: Float, cy: Float, s: Float): Path {
        val p = Path()
        p.addCircle(cx - s * 1.1f, cy + s * 0.2f, s * 0.7f, Path.Direction.CW)
        p.addCircle(cx, cy - s * 0.4f, s * 0.95f, Path.Direction.CW)
        p.addCircle(cx + s * 1.1f, cy + s * 0.1f, s * 0.75f, Path.Direction.CW)
        p.addRoundRect(RectF(cx - s * 1.6f, cy + s * 0.1f, cx + s * 1.6f, cy + s * 0.9f),
            s * 0.4f, s * 0.4f, Path.Direction.CW)
        return p
    }

    private fun drawCloud(canvas: Canvas, cx: Float, cy: Float, u: Float) {
        val s = u * 0.22f
        val o1 = (Math.sin(t.toDouble()) * u * 0.03f).toFloat()
        val o2 = (Math.cos(t * 0.9) * u * 0.03f).toFloat()
        canvas.drawPath(cloudPath(cx + o2 + u * 0.05f, cy + u * 0.12f, s * 0.75f), soft)
        canvas.drawPath(cloudPath(cx + o1, cy - u * 0.05f, s), white)
    }

    private fun drawRain(canvas: Canvas, cx: Float, cy: Float, u: Float) {
        val s = u * 0.2f
        val cloudY = cy - u * 0.14f
        canvas.drawPath(cloudPath(cx, cloudY, s), white) // 雲＝白
        if (drops.isEmpty()) {
            for (i in 0 until 6) {
                drops.add(Particle(
                    cx - u * 0.28f + Math.random().toFloat() * u * 0.56f,
                    cloudY + u * 0.15f + Math.random().toFloat() * u * 0.5f, 0f))
            }
        }
        // 雨粒＝青
        stroke.color = blue
        stroke.strokeWidth = u * 0.024f
        val bottom = cloudY + u * 0.7f
        for (d in drops) {
            canvas.drawLine(d.x, d.y, d.x - u * 0.02f, d.y + u * 0.09f, stroke)
            d.y += u * 0.035f
            if (d.y > bottom) {
                d.y = cloudY + u * 0.12f
                d.x = cx - u * 0.28f + Math.random().toFloat() * u * 0.56f
            }
        }
    }

    private fun drawThunder(canvas: Canvas, cx: Float, cy: Float, u: Float) {
        val s = u * 0.2f
        canvas.drawPath(cloudPath(cx, cy - u * 0.16f, s), white) // 雲＝白
        val flash = Math.sin(t * 2.0) > -0.3
        if (flash) {
            // 稲妻＝オレンジ
            val bolt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = orange }
            val p = Path()
            p.moveTo(cx + u*0.04f, cy + u*0.02f)
            p.lineTo(cx - u*0.06f, cy + u*0.18f)
            p.lineTo(cx + u*0.01f, cy + u*0.18f)
            p.lineTo(cx - u*0.08f, cy + u*0.4f)
            p.lineTo(cx + u*0.12f, cy + u*0.12f)
            p.lineTo(cx + u*0.03f, cy + u*0.12f)
            p.close()
            canvas.drawPath(p, bolt)
        }
    }

    private fun drawSnow(canvas: Canvas, cx: Float, cy: Float, u: Float) {
        val s = u * 0.2f
        val cloudY = cy - u * 0.14f
        canvas.drawPath(cloudPath(cx, cloudY, s), white) // 雲＝白
        if (flakes.isEmpty()) {
            for (i in 0 until 6) {
                flakes.add(Particle(
                    cx - u * 0.28f + Math.random().toFloat() * u * 0.56f,
                    cloudY + u * 0.15f + Math.random().toFloat() * u * 0.5f,
                    Math.random().toFloat() * 6f))
            }
        }
        // 雪＝白
        val bottom = cloudY + u * 0.72f
        for (f in flakes) {
            canvas.drawCircle(f.x, f.y, u * 0.022f, white)
            f.y += u * 0.016f
            f.x += (Math.sin(f.phase.toDouble()) * u * 0.008f).toFloat()
            f.phase += 0.15f
            if (f.y > bottom) {
                f.y = cloudY + u * 0.12f
                f.x = cx - u * 0.28f + Math.random().toFloat() * u * 0.56f
            }
        }
    }

    private fun drawNight(canvas: Canvas, cx: Float, cy: Float, u: Float) {
        val r = u * 0.2f
        // 三日月＝オレンジ
        val moon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = orange }
        val save = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawCircle(cx - u*0.03f, cy, r, moon)
        val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        }
        canvas.drawCircle(cx + u*0.08f, cy - u*0.05f, r * 0.95f, cut)
        canvas.restoreToCount(save)
        // 星＝白
        val stars = arrayOf(floatArrayOf(-0.28f,-0.28f), floatArrayOf(0.3f,-0.18f), floatArrayOf(-0.2f,0.28f), floatArrayOf(0.28f,0.26f))
        for ((i, st) in stars.withIndex()) {
            val tw = (Math.sin(t * 1.5 + i) * 0.5 + 0.5).toFloat()
            white.alpha = (120 + tw * 135).toInt()
            val sx = cx + st[0]*u; val sy = cy + st[1]*u
            val sr = u * 0.018f * (0.6f + tw)
            canvas.drawCircle(sx, sy, sr, white)
        }
        white.alpha = 255
    }
}