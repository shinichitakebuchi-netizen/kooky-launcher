package com.kooky.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

// 再生バーのオリジナル操作アイコン（インジケーターと同じ細い線・丸い端）
// 24×24 の座標で描いて、Viewの大きさに合わせて拡大する
class MediaIconView(context: Context, private val kind: Kind) : View(context) {

    enum class Kind { PREV, REWIND, PLAY_PAUSE, FORWARD, NEXT }

    var color: Int = 0xFFFFFFFF.toInt()
        set(v) { field = v; invalidate() }
    // PLAY_PAUSE のときだけ使う：true=一時停止マーク／false=再生マーク
    var playing: Boolean = true
        set(v) { if (field != v) { field = v; invalidate() } }

    // アイコンの大きさ（px）。0ならViewいっぱい
    var iconPx: Float = 0f
        set(v) { field = v; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val s = (if (iconPx > 0f) iconPx else minOf(width, height).toFloat()) / 24f
        val ox = (width - 24f * s) / 2f
        val oy = (height - 24f * s) / 2f
        canvas.save()
        canvas.translate(ox, oy)
        canvas.scale(s, s)
        paint.color = color
        paint.strokeWidth = 1.9f
        path.reset()
        when (kind) {
            Kind.PREV -> {
                path.moveTo(6f, 6f); path.lineTo(6f, 18f)
                path.moveTo(18f, 6.5f); path.lineTo(10f, 12f); path.lineTo(18f, 17.5f); path.close()
            }
            Kind.NEXT -> {
                path.moveTo(18f, 6f); path.lineTo(18f, 18f)
                path.moveTo(6f, 6.5f); path.lineTo(14f, 12f); path.lineTo(6f, 17.5f); path.close()
            }
            Kind.REWIND -> {
                // 左回りの円の矢印
                path.addArc(RectF(5f, 5.5f, 19f, 19.5f), 207.4f, 328.5f)
                path.moveTo(5.6f, 5.5f); path.lineTo(5.6f, 9.8f); path.lineTo(9.9f, 9.8f)
            }
            Kind.FORWARD -> {
                // 右回りの円の矢印
                path.addArc(RectF(5f, 5.5f, 19f, 19.5f), 332.6f, -328.5f)
                path.moveTo(18.4f, 5.5f); path.lineTo(18.4f, 9.8f); path.lineTo(14.1f, 9.8f)
            }
            Kind.PLAY_PAUSE -> {
                // インジケーターと同じ、下が空いた輪っか
                path.addArc(RectF(3f, 3f, 21f, 21f), 135f, 270f)
                if (playing) {
                    path.moveTo(10.2f, 8.5f); path.lineTo(10.2f, 15.5f)
                    path.moveTo(13.8f, 8.5f); path.lineTo(13.8f, 15.5f)
                } else {
                    path.moveTo(10f, 8.2f); path.lineTo(10f, 15.8f); path.lineTo(16f, 12f); path.close()
                }
            }
        }
        canvas.drawPath(path, paint)
        canvas.restore()
    }
}
