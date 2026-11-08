package com.kooky.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

// 通知を横に流す掲示板
class TickerView(context: Context) : View(context) {

    private class Seg(val text: String, val bold: Boolean, val itemIndex: Int)

    var lightMode: Boolean = false
        set(v) { field = v; invalidate() }
    // 文字のフォント（流れる通知）
    var font: Typeface = Typeface.SERIF
        set(v) {
            field = v
            normal.typeface = v
            bold.typeface = Typeface.create(v, Typeface.BOLD)
            if (items.isNotEmpty()) rebuild()   // 文字の幅が変わるので並びを測り直す
            invalidate()
        }
    var onItemTap: ((NotificationService.Item) -> Unit)? = null

    private var items: List<NotificationService.Item> = emptyList()
    private val segs = ArrayList<Seg>()
    private val segX = ArrayList<Float>()      // 各セグメントの開始位置（ループ1周内）
    private val segW = ArrayList<Float>()      // 各セグメントの幅（毎フレーム測らないよう覚えておく）
    private var loopWidth = 0f
    private var offset = 0f
    private var running = false

    private val d = resources.displayMetrics.density
    private val speedPerSec = 66f * d          // 1秒で進む量（時間基準なので画面の速さに左右されない）
    private var lastFrameNs = 0L
    private val orange get() = Wa.accent   // 差し色（設定で選んだ色。初期値は紅緋）
    private val normal = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * d }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * d; typeface = Typeface.DEFAULT_BOLD
    }

    private val step = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.nanoTime()
            // 前のフレームからの経過時間ぶん進める（止まっていた分は最大0.1秒まで）
            val dt = if (lastFrameNs == 0L) 0f
                     else ((now - lastFrameNs) / 1_000_000_000f).coerceAtMost(0.1f)
            lastFrameNs = now
            offset += speedPerSec * dt
            if (loopWidth > 0f && offset >= loopWidth) offset -= loopWidth
            invalidate()
            postOnAnimation(this)
        }
    }

    fun setItems(list: List<NotificationService.Item>) {
        val changed = list.map { it.pkg + it.time } != items.map { it.pkg + it.time }
        items = list
        if (changed) rebuild()
        visibility = if (items.isEmpty()) GONE else VISIBLE
        if (items.isEmpty()) stop() else start()
    }

    private fun rebuild() {
        segs.clear(); segX.clear(); segW.clear()
        val gap = "　　　"
        for ((i, it) in items.withIndex()) {
            segs.add(Seg(it.app, true, i))
            val body = when {
                it.title.isNotEmpty() && it.text.isNotEmpty() -> "　${it.title}：${it.text}"
                it.title.isNotEmpty() -> "　${it.title}"
                else -> "　${it.text}"
            }
            segs.add(Seg(body + gap, false, i))
        }
        var x = 0f
        for (s in segs) {
            segX.add(x)
            val w = (if (s.bold) bold else normal).measureText(s.text)
            segW.add(w)
            x += w
        }
        // 通知が増減しても今の位置から続けて流す（先頭に戻さない）
        offset = if (loopWidth > 0f && x > 0f) offset % x else 0f
        loopWidth = x
    }

    fun start() {
        if (running || items.isEmpty()) return
        running = true
        lastFrameNs = 0L
        postOnAnimation(step)
    }

    fun stop() {
        running = false
        removeCallbacks(step)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    // 並べ替え（画面の回転など）で一度外れても、戻ったら流し直す
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (items.isNotEmpty() && visibility == VISIBLE && windowVisibility == VISIBLE) start()
    }

    // ホームが隠れている間は止め、見えたら再開する
    override fun onWindowVisibilityChanged(v: Int) {
        super.onWindowVisibilityChanged(v)
        if (v == VISIBLE) { if (items.isNotEmpty()) start() } else stop()
    }

    override fun onDraw(canvas: Canvas) {
        if (segs.isEmpty() || loopWidth <= 0f) return
        normal.color = Wa.ink(lightMode)
        bold.color = orange
        val base = height / 2f - (normal.descent() + normal.ascent()) / 2f
        // 画面を埋めるまでループを繰り返し描く
        var start = -offset
        while (start > 0f) start -= loopWidth
        while (start < width) {
            for (i in segs.indices) {
                val x = start + segX[i]
                val p = if (segs[i].bold) bold else normal
                if (x > width) break
                if (x + segW[i] < 0f) continue
                canvas.drawText(segs[i].text, x, base, p)
            }
            start += loopWidth
        }
    }

    // 1回タップ：その位置に流れている通知を開く／ダブルタップ：通知パネルを下ろす
    var onDoubleTap: (() -> Unit)? = null

    // 押した瞬間の位置で選ぶ（離すまでの間に文字が流れても、押した通知を開く）
    private var downOffset = 0f

    private fun itemAt(x: Float): NotificationService.Item? {
        if (loopWidth <= 0f || segs.isEmpty()) return null
        var pos = (x + downOffset) % loopWidth
        if (pos < 0) pos += loopWidth
        var idx = 0
        for (i in segX.indices) if (segX[i] <= pos) idx = i
        return items.getOrNull(segs[idx].itemIndex)
    }

    private val gesture = android.view.GestureDetector(context,
        object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean { downOffset = offset; return true }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                itemAt(e.x)?.let { onItemTap?.invoke(it) }
                performClick()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                onDoubleTap?.invoke()
                return true
            }
        })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gesture.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
