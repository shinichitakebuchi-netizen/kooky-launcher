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
    var onItemTap: ((NotificationService.Item) -> Unit)? = null

    private var items: List<NotificationService.Item> = emptyList()
    private val segs = ArrayList<Seg>()
    private val segX = ArrayList<Float>()      // 各セグメントの開始位置（ループ1周内）
    private var loopWidth = 0f
    private var offset = 0f
    private var running = false

    private val d = resources.displayMetrics.density
    private val speed = 1.1f * d               // 1フレームで進む量
    private val orange = Color.parseColor("#FF8C00")
    private val normal = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * d }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * d; typeface = Typeface.DEFAULT_BOLD
    }

    private val step = object : Runnable {
        override fun run() {
            if (!running) return
            offset += speed
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
        segs.clear(); segX.clear()
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
            x += (if (s.bold) bold else normal).measureText(s.text)
        }
        loopWidth = x
        offset = 0f
    }

    fun start() {
        if (running || items.isEmpty()) return
        running = true
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

    override fun onDraw(canvas: Canvas) {
        if (segs.isEmpty() || loopWidth <= 0f) return
        normal.color = if (lightMode) Color.parseColor("#1C1C1E") else Color.WHITE
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
                if (x + p.measureText(segs[i].text) < 0f) continue
                canvas.drawText(segs[i].text, x, base, p)
            }
            start += loopWidth
        }
    }

    // タップした位置に流れている通知を返す
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && loopWidth > 0f) {
            var pos = (event.x + offset) % loopWidth
            if (pos < 0) pos += loopWidth
            var idx = 0
            for (i in segX.indices) if (segX[i] <= pos) idx = i
            val item = items.getOrNull(segs[idx].itemIndex)
            if (item != null) onItemTap?.invoke(item)
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
