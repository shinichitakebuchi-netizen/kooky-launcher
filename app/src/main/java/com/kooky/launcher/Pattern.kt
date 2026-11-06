package com.kooky.launcher

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// 日本の伝統文様（アプリの四角と設定アイコン用）
// 文様：0=なし / 1=麻の葉 / 2=青海波 / 3=七宝 / 4=亀甲 / 5=市松 / 6=鱗
// 入れ方：0=薄く敷く / 1=角だけ / 2=枠を文様に
object Pattern {
    val NAMES = listOf("なし", "麻の葉", "青海波", "七宝", "亀甲", "市松", "鱗")
    val STYLES = listOf("薄く敷く", "角だけ", "枠を文様に")

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // 四角（size×size）に文様を入れる。inset は枠の内側までの距離、r は角丸
    // color：文様の色 / bg：青海波の重なりを消すための地の色 / sw：線の太さ(px)
    fun drawInIcon(c: Canvas, type: Int, style: Int, size: Float, inset: Float, r: Float,
                   color: Int, bg: Int, sw: Float) {
        if (type <= 0) return
        val k = size / 56f
        val clip = Path()
        val unit: Float
        when (style) {
            1 -> { // 角だけ：右下の三角
                clip.moveTo(size - inset, size * 0.42f)
                clip.lineTo(size - inset, size - inset)
                clip.lineTo(size * 0.42f, size - inset)
                clip.close()
                unit = (if (type == 5) 5f else 8f) * k
            }
            2 -> { // 枠を文様に：外側6/56の帯
                clip.fillType = Path.FillType.EVEN_ODD
                clip.addRoundRect(RectF(inset, inset, size - inset, size - inset), r, r, Path.Direction.CW)
                val b = 6f * k
                clip.addRect(RectF(inset + b, inset + b, size - inset - b, size - inset - b), Path.Direction.CW)
                unit = (if (type == 5) 3f else 6f) * k
            }
            else -> { // 薄く敷く：四角いっぱい
                clip.addRoundRect(RectF(inset, inset, size - inset, size - inset), r, r, Path.Direction.CW)
                unit = (if (type == 5) 6f else 10f) * k
            }
        }
        c.save()
        c.clipPath(clip)
        draw(c, type, size, size, unit, color, bg, sw)
        c.restore()
    }

    // 文様を w×h いっぱいに描く（unit＝くり返しの大きさ）
    fun draw(c: Canvas, type: Int, w: Float, h: Float, u: Float, color: Int, bg: Int, sw: Float) {
        line.color = color; line.strokeWidth = sw
        fill.color = color
        when (type) {
            1 -> { // 麻の葉：三角の格子に、重心から頂点への線
                val th = u * sqrt(3f) / 2f
                var r = -1
                while (r * th <= h + th) {
                    var q = -1
                    while (q * u <= w + u) {
                        val x = q * u + if (r % 2 != 0) u / 2f else 0f
                        val y = r * th
                        // 上向きでない三角（▽）
                        val cx = x + u / 2f; val cy = y + th / 3f
                        c.drawLine(x, y, x + u, y, line)
                        c.drawLine(x, y, x + u / 2f, y + th, line)
                        c.drawLine(x + u, y, x + u / 2f, y + th, line)
                        c.drawLine(cx, cy, x, y, line); c.drawLine(cx, cy, x + u, y, line); c.drawLine(cx, cy, x + u / 2f, y + th, line)
                        // となりの三角（△）
                        val dx = x + u / 2f; val dy = y + th
                        val cx2 = dx + u / 2f; val cy2 = y + 2f * th / 3f
                        c.drawLine(cx2, cy2, dx, dy, line); c.drawLine(cx2, cy2, x + u, y, line); c.drawLine(cx2, cy2, dx + u, dy, line)
                        q++
                    }
                    r++
                }
            }
            2 -> { // 青海波：上の段から順に、地の色で塗った同心円を重ねる
                fill.color = bg
                var r = -1
                while (r * u / 4f <= h + u) {
                    var q = -1
                    while (q * u <= w + u) {
                        val x = q * u + if (r % 2 != 0) u / 2f else 0f
                        val y = r * u / 4f
                        c.drawCircle(x, y, u / 2f, fill)
                        c.drawCircle(x, y, u / 2f, line)
                        c.drawCircle(x, y, u / 2f * 0.7f, line)
                        c.drawCircle(x, y, u / 2f * 0.4f, line)
                        q++
                    }
                    r++
                }
            }
            3 -> { // 七宝：円を半分ずつずらして重ねる
                var y = 0f
                while (y <= h + u) {
                    var x = 0f
                    while (x <= w + u) {
                        c.drawCircle(x, y, u / 2f, line)
                        c.drawCircle(x + u / 2f, y + u / 2f, u / 2f, line)
                        x += u
                    }
                    y += u
                }
            }
            4 -> { // 亀甲：六角形の格子
                val th = u * sqrt(3f) / 2f
                val p = Path()
                var r = -1
                while (r * th <= h + th) {
                    var q = -1
                    while (q * 1.5f * u <= w + u) {
                        val cx = q * 1.5f * u
                        val cy = r * 2f * th + if (q % 2 != 0) th else 0f
                        p.reset()
                        for (i in 0 until 6) {
                            val a = Math.PI / 3 * i
                            val px = cx + u * cos(a).toFloat(); val py = cy + u * sin(a).toFloat()
                            if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
                        }
                        p.close()
                        c.drawPath(p, line)
                        q++
                    }
                    r++
                }
            }
            5 -> { // 市松：正方形を交互に塗る
                fill.alpha = 140
                var y = 0f; var j = 0
                while (y < h) {
                    var x = 0f; var i = 0
                    while (x < w) {
                        if ((i + j) % 2 == 0) c.drawRect(x, y, x + u, y + u, fill)
                        x += u; i++
                    }
                    y += u; j++
                }
                fill.alpha = 255
            }
            6 -> { // 鱗：三角形を交互に塗る
                val th = u * 0.8f
                val p = Path()
                fill.alpha = 115
                var r = 0
                while (r * th <= h + th) {
                    var q = -1
                    while (q * u <= w + u) {
                        val x = q * u + if (r % 2 != 0) u / 2f else 0f
                        val y = r * th
                        p.reset()
                        p.moveTo(x, y + th); p.lineTo(x + u / 2f, y); p.lineTo(x + u, y + th); p.close()
                        if ((q + r) % 2 != 0) c.drawPath(p, fill)
                        c.drawPath(p, line)
                        q++
                    }
                    r++
                }
                fill.alpha = 255
            }
        }
    }

    // 設定アイコン（ハンバーガーの代わり）：文様を一つだけ取り出した印。28×28 の座標で描いて拡大
    // 線は accent（紅緋など）。市松・鱗は線を ink、塗りを accent にする
    fun drawGlyph(c: Canvas, type: Int, size: Float, accent: Int, ink: Int, swPx: Float) {
        val s = size / 28f
        c.save(); c.scale(s, s)
        line.strokeWidth = swPx / s
        line.color = if (type == 5 || type == 6) ink else accent
        fill.color = accent; fill.alpha = 255
        val p = Path()
        fun poly(vararg pts: Float): Path {
            p.reset(); p.moveTo(pts[0], pts[1])
            var i = 2
            while (i < pts.size) { p.lineTo(pts[i], pts[i + 1]); i += 2 }
            p.close(); return p
        }
        when (type) {
            1 -> { // 麻の葉：六角形と、中心から6本
                c.drawPath(poly(14f, 3f, 23.5f, 8.5f, 23.5f, 19.5f, 14f, 25f, 4.5f, 19.5f, 4.5f, 8.5f), line)
                for ((x, y) in listOf(14f to 3f, 23.5f to 19.5f, 4.5f to 19.5f, 23.5f to 8.5f, 4.5f to 8.5f, 14f to 25f))
                    c.drawLine(14f, 14f, x, y, line)
            }
            2 -> { // 青海波：重なった3本の弧
                for (rr in listOf(11f, 7f, 3f)) c.drawArc(RectF(14f - rr, 20f - rr, 14f + rr, 20f + rr), 180f, 180f, false, line)
            }
            3 -> { // 七宝：4つの円の重なり
                c.drawCircle(14f, 9.5f, 6f, line); c.drawCircle(14f, 18.5f, 6f, line)
                c.drawCircle(9.5f, 14f, 6f, line); c.drawCircle(18.5f, 14f, 6f, line)
            }
            4 -> { // 亀甲：二重の六角形
                c.drawPath(poly(14f, 4f, 22.5f, 9f, 22.5f, 19f, 14f, 24f, 5.5f, 19f, 5.5f, 9f), line)
                c.drawPath(poly(14f, 9f, 18.3f, 11.5f, 18.3f, 16.5f, 14f, 19f, 9.7f, 16.5f, 9.7f, 11.5f), line)
            }
            5 -> { // 市松：2×2
                c.drawRect(6f, 6f, 14f, 14f, fill); c.drawRect(14f, 14f, 22f, 22f, fill)
                c.drawRect(6f, 6f, 22f, 22f, line)
            }
            6 -> { // 鱗：三角の中に小さな三角
                c.drawPath(poly(14f, 5f, 22f, 19f, 6f, 19f), line)
                c.drawPath(poly(14f, 12f, 18f, 19f, 10f, 19f), fill)
                c.drawLine(4f, 23f, 24f, 23f, line)
            }
        }
        c.restore()
    }
}
