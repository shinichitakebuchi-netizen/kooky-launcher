package com.kooky.launcher

import android.graphics.Color

// 日本の伝統色（縁アプリと共通）。ここにある色以外は使わない
object Wa {
    val AI = Color.parseColor("#264348")          // 藍色：背景
    val TORINOKO = Color.parseColor("#E2BE9F")    // 鳥の子色：文字（主）
    val KAMENOZOKI = Color.parseColor("#C6C2B6")  // 甕覗
    val KAMENOZOKI70 = Color.parseColor("#B3C6C2B6") // 甕覗 70%：文字（補足）
    val RIKYU = Color.parseColor("#656255")       // 利休鼠：罫線・枠線
    val BENIHI = Color.parseColor("#F35336")      // 紅緋：見出し・強調
    val WAKATAKE = Color.parseColor("#6B9362")    // 若竹色：状態（稼働中・充電中）
    val KARAKURENAI = Color.parseColor("#C91F37") // 韓紅花：注意（残りわずか）
    val UKON = Color.parseColor("#E69B3A")        // 鬱金色：雷

    // ライト時は同じ色を入れ替えて使う（背景 鳥の子／文字 藍／補足・線 利休鼠）
    fun bg(light: Boolean) = if (light) TORINOKO else AI
    fun ink(light: Boolean) = if (light) AI else TORINOKO
    fun sub(light: Boolean) = if (light) RIKYU else KAMENOZOKI70
    fun line(light: Boolean) = RIKYU

    // 色を選ぶ設定の18色（6色×3行）
    val PICK_NAMES = arrayOf(
        "一斤染", "鴇色", "韓紅花", "紅緋", "柿渋色", "鬱金色",
        "玉子色", "鳥の子色", "鶸色", "萌黄", "松葉色", "若竹色",
        "新橋色", "甕覗", "藍色", "利休鼠", "紫苑", "桔梗色")
    val PICK = intArrayOf(
        Color.parseColor("#F08F90"), Color.parseColor("#F3A696"), Color.parseColor("#C91F37"),
        Color.parseColor("#F35336"), Color.parseColor("#934337"), Color.parseColor("#E69B3A"),
        Color.parseColor("#FFA631"), Color.parseColor("#E2BE9F"), Color.parseColor("#BDA928"),
        Color.parseColor("#5B8930"), Color.parseColor("#454D32"), Color.parseColor("#6B9362"),
        Color.parseColor("#006C7F"), Color.parseColor("#C6C2B6"), Color.parseColor("#264348"),
        Color.parseColor("#656255"), Color.parseColor("#976E9A"), Color.parseColor("#5D3F6A"))
}
