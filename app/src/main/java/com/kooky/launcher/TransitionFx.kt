package com.kooky.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

// アプリを開くとき・ホームに戻ってきたときの演出（ホームの上に重ねて描く。触っても下に届く）
// 開く：1=襖がひらく / 2=墨がにじむ / 3=波紋 / 4=落款
// 戻る：2=線が引かれる（線と枠を描く）/ 4=戻った跡（開いていた四角に輪）
// 戻る 1=余白から現れる・3=静かに戻る は MainActivity 側でホームの部品を動かす
class TransitionFx(context: Context) : View(context) {

    companion object {
        val OPEN_NAMES = listOf("なし", "襖がひらく", "墨がにじむ", "波紋", "落款")
        val OPEN_DESC = listOf("すぐに開く", "四角が広がり、真ん中から左右に開く", "アイコンから墨が円く広がる",
            "アイコンから輪が広がり、その中から開く", "差し色の印を押してから広がる")
        val BACK_NAMES = listOf("なし", "余白から現れる", "線が引かれる", "静かに戻る", "戻った跡")
        val BACK_DESC = listOf("何もしない", "時計・日付・アイコンの順に浮かぶ", "検索の線が伸び、四角の枠が描かれる",
            "全体がふわっと現れる", "開いていたアプリの四角に、小さな輪が残る")
    }

    var lightMode = false
    var accent = Wa.BENIHI
    var font: Typeface = Typeface.SERIF

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()

    private var kind = ""            // 今の演出
    private var start = 0L
    private var dur = 0f
    private var src = RectF()        // アイコンの四角（この View の座標）
    private var lineY = 0f; private var lineL = 0f; private var lineR = 0f
    private var frames: List<RectF> = emptyList()
    private var hold = false         // 開く演出の最後の状態のまま止めておく（アプリが前に出るまで）
    private var onEnd: (() -> Unit)? = null

    init {
        isClickable = false; isFocusable = false
        visibility = INVISIBLE
    }

    private fun ease(x: Float) = 1f - (1f - x.coerceIn(0f, 1f)).pow(3)

    private fun begin(k: String, seconds: Float, end: (() -> Unit)?) {
        kind = k; dur = seconds; onEnd = end; hold = false
        start = SystemClock.uptimeMillis()
        visibility = VISIBLE
        postInvalidateOnAnimation()
    }

    // ===== 開く =====
    fun playOpen(style: Int, iconRect: RectF, then: () -> Unit) {
        src = RectF(iconRect)
        when (style) {
            1 -> begin("fusuma", 0.30f, then)
            2 -> begin("sumi", 0.34f, then)
            3 -> begin("hamon", 0.52f, then)
            4 -> begin("rakkan", 0.34f, then)
            else -> then()
        }
    }

    // ===== 戻る（線・跡） =====
    fun playLines(y: Float, l: Float, r: Float, icons: List<RectF>) {
        lineY = y; lineL = l; lineR = r; frames = icons
        begin("sen", 0.75f, null)
    }

    fun playTrace(iconRect: RectF) {
        src = RectF(iconRect)
        begin("ato", 1.1f, null)
    }

    fun clear() {
        kind = ""; hold = false; onEnd = null
        visibility = INVISIBLE
        invalidate()
    }

    fun isBusy() = visibility == VISIBLE && kind.isNotEmpty()

    override fun onDraw(c: Canvas) {
        if (kind.isEmpty()) return
        val t = if (hold) dur else (SystemClock.uptimeMillis() - start) / 1000f
        val k = min(1f, t / dur)
        val w = width.toFloat(); val h = height.toFloat()
        val bg = Wa.bg(lightMode); val ink = Wa.ink(lightMode)
        when (kind) {
            "fusuma" -> {
                // 四角が画面いっぱいまで広がる（このあとアプリが真ん中から左右に開いて出る）
                val e = ease(k)
                val r = RectF(src.left * (1 - e), src.top * (1 - e),
                    src.right + (w - src.right) * e, src.bottom + (h - src.bottom) * e)
                fill.color = bg; c.drawRect(r, fill)
                line.color = ink; line.strokeWidth = 1f * d
                c.drawRect(r.left + d / 2, r.top + d / 2, r.right - d / 2, r.bottom - d / 2, line)
                // 最後に真ん中の合わせ目
                if (k > 0.6f) {
                    line.color = Wa.RIKYU; line.alpha = ((k - 0.6f) / 0.4f * 255).toInt()
                    c.drawLine(w / 2, 0f, w / 2, h, line); line.alpha = 255
                }
            }
            "sumi" -> {
                // ふちが少し揺らいだ円が、アイコンから画面いっぱいへ
                val e = ease(k)
                val far = hypot(max(src.centerX(), w - src.centerX()), max(src.centerY(), h - src.centerY()))
                val base = src.width() / 2f + far * e
                path.reset()
                var a = 0f
                while (a < 6.3f) {
                    val rr = base * (1f + 0.04f * sin(a * 5f + k * 6f))
                    val x = src.centerX() + cos(a) * rr; val y = src.centerY() + sin(a) * rr
                    if (a == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                    a += 0.15f
                }
                path.close()
                fill.color = if (lightMode) Wa.SUMI else Wa.SHIRONERI
                c.drawPath(path, fill)
            }
            "hamon" -> {
                // 波紋が画面いっぱいまで広がる。先頭の輪の内側は水面のように地の色で静まり、
                // ホームが波にのまれて消えたところでアプリが出る
                val far = hypot(max(src.centerX(), w - src.centerX()), max(src.centerY(), h - src.centerY()))
                val r0 = src.width() / 2f
                // 内側の静まり（いちばん後ろの輪を追いかける）
                val calm = ease(((k - 0.25f) / 0.75f).coerceIn(0f, 1f))
                if (calm > 0f) { fill.color = bg; c.drawCircle(src.centerX(), src.centerY(), r0 + (far + 20f * d) * calm, fill) }
                // 3本の輪（先に出た輪ほど遠くへ、うすく）
                for (j in 0 until 3) {
                    val kk = ease(((k * 1.25f) - j * 0.12f).coerceIn(0f, 1f))
                    if (kk <= 0f) continue
                    line.color = ink
                    line.strokeWidth = (1.4f - j * 0.3f) * d
                    line.alpha = ((1f - kk * 0.7f) * (if (k > 0.85f) (1f - k) / 0.15f else 1f) * 255).toInt().coerceIn(0, 255)
                    c.drawCircle(src.centerX(), src.centerY(), r0 + (far + 40f * d) * kk, line)
                }
                line.alpha = 255; line.strokeWidth = 1f * d
            }
            "rakkan" -> {
                // 差し色の四角い印に「庵」。大きめから押しつけるように縮む
                val e = ease(min(1f, k / 0.65f))
                val s = 1.6f - 0.6f * e
                val half = 12f * d * s
                fill.color = accent; fill.alpha = (min(1f, k / 0.4f) * 255).toInt()
                c.drawRect(src.centerX() - half, src.centerY() - half, src.centerX() + half, src.centerY() + half, fill)
                fill.alpha = 255
                text.color = bg; text.typeface = font; text.textSize = 15f * d * s
                text.alpha = fill.alpha
                c.drawText("庵", src.centerX(), src.centerY() - (text.descent() + text.ascent()) / 2f, text)
            }
            "sen" -> {
                // 検索の線が真ん中から伸び、アイコンの枠が一筆で描かれる
                line.color = ink; line.strokeWidth = 1f * d
                val e = ease(min(1f, k * 1.8f))
                val mid = (lineL + lineR) / 2f; val half = (lineR - lineL) / 2f * e
                c.drawLine(mid - half, lineY, mid + half, lineY, line)
                for ((i, r) in frames.withIndex()) {
                    val q = ease(k * 1.6f - 0.35f - i * 0.03f)
                    if (q <= 0f) continue
                    drawFramePart(c, r, q)
                }
            }
            "ato" -> {
                line.strokeWidth = 1f * d
                for (j in 0 until 2) {
                    val kk = (k * 1.3f - j * 0.3f).coerceIn(0f, 1f)
                    line.color = accent; line.alpha = (0.8f * (1f - kk) * 255).toInt()
                    val g = kk * 10f * d
                    c.drawRect(src.left - g, src.top - g, src.right + g, src.bottom + g, line)
                }
                line.alpha = 255
            }
        }
        if (k >= 1f) {
            val end = onEnd
            if (end != null) {
                // 開く演出：最後の絵のまま止めて、アプリを開く（ホームに戻ったら clear される）
                onEnd = null; hold = true
                end()
            } else if (!hold) {
                clear(); return
            }
        }
        if (!hold) postInvalidateOnAnimation()
    }

    // 四角の枠を、左上から時計回りに q（0〜1）の長さまで描く
    private fun drawFramePart(c: Canvas, r: RectF, q: Float) {
        val pts = arrayOf(floatArrayOf(r.left, r.top), floatArrayOf(r.right, r.top),
            floatArrayOf(r.right, r.bottom), floatArrayOf(r.left, r.bottom), floatArrayOf(r.left, r.top))
        val per = 2f * (r.width() + r.height())
        var left = per * q
        path.reset(); path.moveTo(pts[0][0], pts[0][1])
        for (s in 1 until 5) {
            if (left <= 0f) break
            val segLen = if (s % 2 == 1) r.width() else r.height()
            val f = min(1f, left / segLen)
            path.lineTo(pts[s - 1][0] + (pts[s][0] - pts[s - 1][0]) * f, pts[s - 1][1] + (pts[s][1] - pts[s - 1][1]) * f)
            left -= segLen
        }
        c.drawPath(path, line)
    }
}
