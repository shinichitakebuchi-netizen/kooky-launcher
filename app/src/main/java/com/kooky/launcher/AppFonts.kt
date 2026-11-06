package com.kooky.launcher

import android.content.Context
import android.graphics.Typeface

// 文字のフォント。0=端末の明朝（標準）/ 1=Kaisei Tokumin / 2=Yuji Syuku / 3=システムに合わせる
// （0〜2は縁と同じ。3は庵だけ。保存している番号を変えないよう、後ろに足している）
// 庵は Compose を使っていないので、View / Canvas 用の Typeface だけを持つ
object AppFonts {
    val names = listOf("明朝（標準）", "Kaisei Tokumin", "Yuji Syuku", "システムに合わせる")
    // 設定画面での並び順（システムを一番上に）
    val order = listOf(3, 0, 1, 2)

    // 一度読んだら使い回す（描画のたびに読み込まない）
    private val cache = HashMap<Int, Typeface>()

    fun typeface(ctx: Context, i: Int): Typeface = cache.getOrPut(i) {
        try {
            when (i) {
                1 -> ctx.resources.getFont(R.font.kaisei_tokumin)
                2 -> ctx.resources.getFont(R.font.yuji_syuku)
                3 -> Typeface.DEFAULT   // 端末の設定のフォント（Galaxyならフォントスタイルで選んだもの）
                else -> null
            }
        } catch (e: Exception) { null } ?: Typeface.SERIF
    }
}
