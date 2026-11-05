package com.kooky.launcher

import android.content.Context
import android.graphics.Typeface

// 文字のフォント（縁と同じ3種類）。0=端末の明朝（標準）/ 1=Kaisei Tokumin / 2=Yuji Syuku
// 庵は Compose を使っていないので、View / Canvas 用の Typeface だけを持つ
object AppFonts {
    val names = listOf("明朝（標準）", "Kaisei Tokumin", "Yuji Syuku")

    // 一度読んだら使い回す（描画のたびに読み込まない）
    private val cache = HashMap<Int, Typeface>()

    fun typeface(ctx: Context, i: Int): Typeface = cache.getOrPut(i) {
        try {
            when (i) {
                1 -> ctx.resources.getFont(R.font.kaisei_tokumin)
                2 -> ctx.resources.getFont(R.font.yuji_syuku)
                else -> null
            }
        } catch (e: Exception) { null } ?: Typeface.SERIF
    }
}
