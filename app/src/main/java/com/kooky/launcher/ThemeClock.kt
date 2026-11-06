package com.kooky.launcher

import android.content.Context
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

// 色合い：0=墨（ダーク）/ 1=白練（ライト）/ 2=移ろい（日の出から日の入りまで白練、それ以外は墨）
// 日の出・日の入りは、天気で使った場所（なければ前橋）で計算する
object ThemeClock {
    val NAMES = listOf("墨", "白練", "移ろい")
    private const val PREFS = "usage"
    private const val DEF_LAT = 36.389   // 前橋
    private const val DEF_LON = 139.063

    fun saveLocation(ctx: Context, lat: Double, lon: Double) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("sun_lat", lat.toFloat()).putFloat("sun_lon", lon.toFloat()).apply()
    }

    fun mode(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("theme_mode", 0)

    fun isLight(ctx: Context, mode: Int = mode(ctx), now: Calendar = Calendar.getInstance()): Boolean =
        when (mode) {
            1 -> true
            2 -> {
                val (rise, set) = sunTimes(ctx, now)
                val m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
                m in rise until set
            }
            else -> false
        }

    // その日の 日の出・日の入り（0時からの分）
    fun sunTimes(ctx: Context, day: Calendar = Calendar.getInstance()): Pair<Int, Int> {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lat = p.getFloat("sun_lat", DEF_LAT.toFloat()).toDouble()
        val lon = p.getFloat("sun_lon", DEF_LON.toFloat()).toDouble()
        return calc(day, lat, lon) ?: (6 * 60 to 18 * 60)
    }

    // NOAA の簡易式（誤差は数分）
    private fun calc(day: Calendar, lat: Double, lon: Double): Pair<Int, Int>? {
        val n = day.get(Calendar.DAY_OF_YEAR)
        val g = 2 * Math.PI / 365.0 * (n - 1)
        val eq = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) +
            0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val la = Math.toRadians(lat)
        val x = cos(Math.toRadians(90.833)) / (cos(la) * cos(decl)) - tan(la) * tan(decl)
        if (x < -1 || x > 1) return null   // 白夜・極夜
        val ha = Math.toDegrees(acos(x))
        val tz = TimeZone.getDefault().getOffset(day.timeInMillis) / 60000.0
        val rise = 720 - 4 * (lon + ha) - eq + tz
        val set = 720 - 4 * (lon - ha) - eq + tz
        return Math.floorMod(rise.toInt(), 1440) to Math.floorMod(set.toInt(), 1440)
    }
}
