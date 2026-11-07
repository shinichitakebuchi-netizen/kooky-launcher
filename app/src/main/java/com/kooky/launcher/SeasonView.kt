package com.kooky.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// 四季のカラクリ：ホームの上に数秒だけ、季節のものを舞わせる（触っても下のホームに届く）
// 春＝桜の花びら / 夏＝蛍 / 秋＝紅葉 / 冬＝六花（雪の結晶）
class SeasonView(context: Context) : View(context) {

    var lightMode = false

    private class P(var x: Float, var y: Float, val vy: Float, val sw: Float, val ph: Float,
                    val rot: Float, val vr: Float, val s: Float, val delay: Float, val alt: Boolean)

    private var season = -1
    private var parts = emptyList<P>()
    private var start = 0L
    private var dur = 0f
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    // 色（選択肢の18色から）
    private val TOKI = Color.parseColor("#F3A696")   // 鴇色：桜
    private val HIWA = Color.parseColor("#BDA928")   // 鶸色：蛍

    init {
        isClickable = false
        isFocusable = false
        visibility = GONE
    }

    // 0=春 1=夏 2=秋 3=冬（3〜5月・6〜8月・9〜11月・12〜2月）
    fun seasonNow(): Int = when (Calendar.getInstance().get(Calendar.MONTH) + 1) {
        in 3..5 -> 0
        in 6..8 -> 1
        in 9..11 -> 2
        else -> 3
    }

    fun isPlaying() = visibility == VISIBLE

    fun play(s: Int = seasonNow()) {
        if (width == 0 || height == 0) { post { play(s) }; return }
        season = s
        val w = width.toFloat(); val h = height.toFloat()
        val r = Random(SystemClock.uptimeMillis())
        fun rf(a: Float, b: Float) = a + r.nextFloat() * (b - a)
        if (s == 1) {
            // 蛍：画面の下半分あたりで、ふわっと灯っては消える
            dur = 9f
            parts = List(9) { P(rf(20f * d, w - 20f * d), rf(h * 0.40f, h * 0.92f), 0f, 0f, rf(0f, 6.3f),
                0f, 0f, rf(0.8f, 1.2f), 0f, false) }
        } else {
            dur = if (s == 3) 11f else 9f
            val n = if (s == 3) 16 else 14
            parts = List(n) {
                P(rf(0f, w), rf(-h * 0.5f, -10f * d), rf(46f, 82f) * d * (if (s == 3) 0.55f else 1f),
                    rf(0.6f, 1.4f), rf(0f, 6.3f), rf(0f, 6.3f), rf(-1.5f, 1.5f), rf(0.8f, 1.3f),
                    rf(0f, 2.5f), it % 3 == 0)
            }
        }
        start = SystemClock.uptimeMillis()
        visibility = VISIBLE
        postInvalidateOnAnimation()
    }

    fun stop() {
        visibility = GONE
        parts = emptyList()
    }

    override fun onDraw(canvas: Canvas) {
        if (visibility != VISIBLE) return
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        if (t > dur) { stop(); return }
        val h = height.toFloat()
        // 終わりの1.5秒で全体をうすくして消す
        val endFade = min(1f, max(0f, (dur - t) / 1.5f))
        if (season == 1) {
            val inFade = min(1f, t / 2f)
            for (p in parts) {
                val g = max(0f, sin(t * 1.3f + p.ph))
                val k = g * inFade * endFade
                val x = p.x + sin(t * 0.4f + p.ph) * 12f * d
                val y = p.y + cos(t * 0.3f + p.ph) * 8f * d
                firefly(canvas, x, y, k, p.s)
            }
        } else {
            for (p in parts) {
                val tt = t - p.delay * 0.4f
                if (tt < 0f) continue
                val y = p.y + 10f * d + tt * p.vy
                if (y > h + 20f * d) continue
                val sway = if (season == 3) 8f else 18f
                val x = p.x + sin(tt * p.sw + p.ph) * sway * d
                val rot = p.rot + tt * p.vr
                val a = (min(1f, max(0f, (h - y) / (60f * d))) * endFade * 255).toInt()
                when (season) {
                    0 -> petal(canvas, x, y, rot, p.s, a)
                    2 -> momiji(canvas, x, y, rot, p.s, if (p.alt) Wa.UKON else Wa.KARAKURENAI, a)
                    else -> rikka(canvas, x, y, rot * 0.3f, p.s, a)
                }
            }
        }
        postInvalidateOnAnimation()
    }

    // 桜の花びら（先に小さな切れ込み）
    private fun petal(c: Canvas, x: Float, y: Float, r: Float, s: Float, a: Int) {
        c.save(); c.translate(x, y); c.rotate(Math.toDegrees(r.toDouble()).toFloat()); c.scale(s * d, s * d)
        path.reset()
        path.moveTo(0f, -6f)
        path.cubicTo(5f, -4f, 5f, 4f, 0f, 6f)
        path.cubicTo(-5f, 4f, -5f, -4f, 0f, -6f)
        path.close()
        // 切れ込み
        path.moveTo(-1.2f, -6.5f); path.lineTo(0f, -4.3f); path.lineTo(1.2f, -6.5f); path.close()
        path.fillType = Path.FillType.EVEN_ODD
        fill.color = TOKI; fill.alpha = a
        c.drawPath(path, fill)
        c.restore()
    }

    // もみじ（5つの葉先）
    private fun momiji(c: Canvas, x: Float, y: Float, r: Float, s: Float, col: Int, a: Int) {
        c.save(); c.translate(x, y); c.rotate(Math.toDegrees(r.toDouble()).toFloat()); c.scale(s * d, s * d)
        path.reset(); path.fillType = Path.FillType.WINDING
        for (i in 0 until 5) {
            val ang = (-Math.PI / 2 + (i - 2) * 0.62).toFloat()
            val tip = if (i == 2) 8f else 6.5f
            path.moveTo(0f, 0f)
            path.lineTo(cos(ang - 0.22f) * 3.5f, sin(ang - 0.22f) * 3.5f)
            path.lineTo(cos(ang) * tip, sin(ang) * tip)
            path.lineTo(cos(ang + 0.22f) * 3.5f, sin(ang + 0.22f) * 3.5f)
            path.close()
        }
        fill.color = col; fill.alpha = a
        c.drawPath(path, fill)
        line.color = col; line.alpha = a; line.strokeWidth = 0.8f
        c.drawLine(0f, 0f, 0f, 4f, line)
        c.restore()
    }

    // 六花（雪の結晶）：細い線。墨のときは白練、白練のときは利休鼠
    private fun rikka(c: Canvas, x: Float, y: Float, r: Float, s: Float, a: Int) {
        c.save(); c.translate(x, y); c.rotate(Math.toDegrees(r.toDouble()).toFloat())
        val rr = 6f * d * s
        line.color = if (lightMode) Wa.RIKYU else Wa.SHIRONERI
        line.alpha = a
        line.strokeWidth = 0.9f * d
        for (i in 0 until 6) {
            val ang = (i * Math.PI / 3).toFloat()
            val ex = cos(ang) * rr; val ey = sin(ang) * rr
            c.drawLine(0f, 0f, ex, ey, line)
            val bx = cos(ang) * rr * 0.55f; val by = sin(ang) * rr * 0.55f
            c.drawLine(bx, by, bx + cos(ang + 0.7f) * rr * 0.3f, by + sin(ang + 0.7f) * rr * 0.3f, line)
            c.drawLine(bx, by, bx + cos(ang - 0.7f) * rr * 0.3f, by + sin(ang - 0.7f) * rr * 0.3f, line)
        }
        c.restore()
    }

    // 蛍：墨のときは光のにじみ＋芯、白練のときは細い輪＋芯（どちらも鶸色）
    private fun firefly(c: Canvas, x: Float, y: Float, k: Float, s: Float) {
        if (k <= 0.01f) return
        if (lightMode) {
            line.color = HIWA; line.alpha = (k * 0.5f * 255).toInt(); line.strokeWidth = 0.8f * d
            c.drawCircle(x, y, 5f * d * s, line)
        } else {
            fill.color = HIWA; fill.alpha = (k * 0.18f * 255).toInt()
            c.drawCircle(x, y, 7f * d * s, fill)
        }
        fill.color = HIWA; fill.alpha = (k * 255).toInt()
        c.drawCircle(x, y, 1.8f * d * s, fill)
    }
}
