package com.kooky.launcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.DisplayMetrics
import android.view.WindowManager
import java.util.Calendar

// ロック画面の壁紙（1年の○）を作って設定する
object LockWallpaper {

    private const val PREFS = "usage"
    const val KEY_ON = "lock_dots_on"
    private const val KEY_LAST = "lock_dots_last"

    fun isOn(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    private fun isLight(ctx: Context): Boolean {
        val mode = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("theme_mode", 0)
        return when (mode) {
            1 -> true
            2 -> Calendar.getInstance().get(Calendar.HOUR_OF_DAY) in 6..20
            else -> false
        }
    }

    // 日付かテーマが変わっていたら作り直す（force=trueなら必ず）
    fun update(ctx: Context, force: Boolean = false) {
        if (!isOn(ctx)) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cal = Calendar.getInstance()
        val light = isLight(ctx)
        val key = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}-${if (light) "L" else "D"}"
        if (!force && prefs.getString(KEY_LAST, "") == key) { schedule(ctx); return }
        try {
            val bmp = render(ctx, light, cal)
            WallpaperManager.getInstance(ctx).setBitmap(bmp, null, true, WallpaperManager.FLAG_LOCK)
            bmp.recycle()
            prefs.edit().putString(KEY_LAST, key).apply()
        } catch (e: Exception) {}
        schedule(ctx)
    }

    // オフにしたらロック画面専用の壁紙を外す（ホームの壁紙と同じに戻る）
    fun turnOff(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_LAST).apply()
        try { WallpaperManager.getInstance(ctx).clear(WallpaperManager.FLAG_LOCK) } catch (e: Exception) {}
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(ctx))
    }

    private fun render(ctx: Context, light: Boolean, cal: Calendar): Bitmap {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        val w = minOf(dm.widthPixels, dm.heightPixels).coerceAtLeast(720)
        val h = maxOf(dm.widthPixels, dm.heightPixels).coerceAtLeast(1280)

        val bg = if (light) Color.parseColor("#F5F5F7") else Color.BLACK
        val future = if (light) Color.parseColor("#1C1C1E") else Color.WHITE
        val past = if (light) Color.parseColor("#1F000000") else Color.parseColor("#29FFFFFF")
        val orange = Color.parseColor("#FF8C00")

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(bg)

        val days = cal.getActualMaximum(Calendar.DAY_OF_YEAR)   // 365 or 366
        val today = cal.get(Calendar.DAY_OF_YEAR)
        val cols = 15
        val gap = w * 0.05f
        val r = gap * 0.30f
        val x0 = (w - (cols - 1) * gap) / 2f
        val y0 = h * 0.32f

        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until days) {
            val d = i + 1
            val x = x0 + (i % cols) * gap
            val y = y0 + (i / cols) * gap
            when {
                d < today -> { p.color = past; canvas.drawCircle(x, y, r, p) }
                d == today -> { p.color = orange; canvas.drawCircle(x, y, r * 1.15f, p) }
                else -> { p.color = future; canvas.drawCircle(x, y, r, p) }
            }
        }
        return bmp
    }

    private fun pending(ctx: Context): PendingIntent {
        val i = Intent(ctx, LockWallpaperReceiver::class.java).setAction("com.kooky.launcher.LOCK_DOTS")
        return PendingIntent.getBroadcast(ctx, 7301, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    // 次の 0:01 / 6:00 / 21:00 のうち一番近い時刻に更新を予約
    fun schedule(ctx: Context) {
        if (!isOn(ctx)) return
        val now = Calendar.getInstance()
        val targets = listOf(0 to 1, 6 to 0, 21 to 0).map { (hh, mm) ->
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hh); set(Calendar.MINUTE, mm)
                set(Calendar.SECOND, 5); set(Calendar.MILLISECOND, 0)
                if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        val next = targets.minByOrNull { it.timeInMillis } ?: return
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pending(ctx))
        } catch (e: Exception) {}
    }
}

class LockWallpaperReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pr = goAsync()
        Thread {
            try { LockWallpaper.update(context) } finally { pr.finish() }
        }.start()
    }
}
