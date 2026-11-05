package com.kooky.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.audiofx.Visualizer
import android.os.SystemClock
import android.util.Log
import android.view.View
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// 音が出ている間に動くイコライザー（細い棒5本）
// ・マイク許可があれば、スマホから出ている音に合わせて動く（左＝低音 → 右＝高音）
// ・許可がない／端末が音を渡してくれないときは、再生中だけリズムっぽく揺れる
class EqualizerView(context: Context) : View(context) {

    var playing: Boolean = false
        set(v) { field = v; if (v) kick() }

    private val bands = 5
    private val target = FloatArray(bands) { 0.12f }
    private val level = FloatArray(bands) { 0.12f }
    private val orange = Wa.BENIHI
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = orange }
    private val d = resources.displayMetrics.density

    private var visualizer: Visualizer? = null
    private var gain = 1f                 // 自動で音量差をならす
    private var lastNonZero = 0L
    private var running = false
    private var t = 0f

    // 実際の音を使えているか（使えなければ揺れるだけ）
    private val realAudio get() = visualizer != null &&
        SystemClock.elapsedRealtime() - lastNonZero < 3000

    fun start() {
        if (running) return
        running = true
        lastNonZero = SystemClock.elapsedRealtime()
        tryOpenVisualizer()
        kick()
    }

    fun stop() {
        running = false
        try { visualizer?.enabled = false } catch (e: Exception) {}
        try { visualizer?.release() } catch (e: Exception) {}
        visualizer = null
        removeCallbacks(frame)
    }

    // 許可がもらえたあとに呼ぶ
    fun restart() { stop(); start() }

    private fun tryOpenVisualizer() {
        if (visualizer != null) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        try {
            val v = Visualizer(0)   // 0 = スマホ全体の出力音
            v.captureSize = Visualizer.getCaptureSizeRange()[0].coerceAtLeast(128)
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vz: Visualizer?, w: ByteArray?, rate: Int) {}
                override fun onFftDataCapture(vz: Visualizer?, fft: ByteArray?, rate: Int) {
                    if (fft != null) onFft(fft)
                }
            }, Visualizer.getMaxCaptureRate() / 2, false, true)
            v.enabled = true
            visualizer = v
        } catch (e: Exception) {
            Log.w("Kooky", "Visualizer not available: ${e.message}")
            visualizer = null
        }
    }

    // FFTを5つの帯に分ける（低音ほど細かく）
    private val edges = intArrayOf(1, 3, 6, 12, 24, 64)
    private fun onFft(fft: ByteArray) {
        val n = fft.size / 2
        var any = false
        val vals = FloatArray(bands)
        for (b in 0 until bands) {
            var sum = 0f; var cnt = 0
            var k = edges[b]
            val end = min(edges[b + 1], n - 1)
            while (k < end) {
                val re = fft[2 * k].toFloat(); val im = fft[2 * k + 1].toFloat()
                val m = hypot(re, im)
                if (m > 0f) any = true
                sum += m; cnt++
                k++
            }
            vals[b] = if (cnt > 0) ln(1f + sum / cnt) else 0f
        }
        if (!any) return
        lastNonZero = SystemClock.elapsedRealtime()
        val peak = vals.maxOrNull() ?: 0f
        gain = max(gain * 0.995f, peak)        // 大きい音に合わせて、ゆっくり戻す
        for (b in 0 until bands) target[b] = (vals[b] / max(gain, 0.001f)).coerceIn(0.1f, 1f)
    }

    private fun kick() { removeCallbacks(frame); if (running) postOnAnimation(frame) }

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            t += 0.016f
            if (!playing) {
                for (b in 0 until bands) target[b] = 0.12f
            } else if (!realAudio) {
                // 実際の音が取れないときは、リズムっぽく揺らす
                for (b in 0 until bands) target[b] = 0.25f + 0.75f * kotlin.math.abs(sin(t * 6f + b * 1.3f))
            }
            var moving = false
            for (b in 0 until bands) {
                val k = if (target[b] > level[b]) 0.55f else 0.18f   // すっと上がって、ゆっくり下がる
                level[b] += (target[b] - level[b]) * k
                if (kotlin.math.abs(target[b] - level[b]) > 0.005f) moving = true
            }
            invalidate()
            if (playing || moving) postOnAnimation(this)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = 3f * d
        val gap = 4f * d
        val h = height.toFloat()
        var x = 0f
        for (b in 0 until bands) {
            val bh = max(3f * d, h * level[b])
            canvas.drawRoundRect(RectF(x, h - bh, x + w, h), w / 2f, w / 2f, paint)
            x += w + gap
        }
    }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); stop() }
}
