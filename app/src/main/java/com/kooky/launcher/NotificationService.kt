package com.kooky.launcher

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotificationService : NotificationListenerService() {

    // 掲示板に流す1件分
    data class Item(
        val pkg: String,
        val app: String,
        val title: String,
        val text: String,
        val time: Long,
        val intent: PendingIntent?
    )

    companion object {
        // 今通知が出ているアプリのパッケージ名の集合（丸ごと差し替えるので、読む側で壊れない）
        @Volatile var activePackages: Set<String> = emptySet()
        // 掲示板用：アプリごとの最新1件（新しい順）
        @Volatile var items: List<Item> = emptyList()
        // 掲示板に流さないシステム系のパッケージ
        private val blockedPkgs = setOf(
            "android", "com.android.systemui", "com.android.providers.downloads",
            "com.samsung.android.lool", "com.samsung.android.app.smartcapture"
        )
        // 掲示板に流さない通知の種類
        private val blockedCategories = setOf(
            Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_PROGRESS, Notification.CATEGORY_SYSTEM,
            Notification.CATEGORY_STATUS
        )
        // 変化をMainActivityに知らせるためのコールバック
        var onChange: (() -> Unit)? = null
        // 新しい通知が届いたとき（四季のカラクリ用）
        var onArrive: (() -> Unit)? = null
        // 通知ごとの最後に届いた時刻（同じ通知の中身だけの更新を見分ける）
        private val lastPost = HashMap<String, Long>()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        refresh()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refresh()
        if (sbn == null) return
        // 掲示板に流すような通知で、新しく届いたもの（または鳴らし直しのある更新）だけ
        val n = sbn.notification ?: return
        if (!isShown(sbn)) return
        val prev = lastPost[sbn.key]
        lastPost[sbn.key] = sbn.postTime
        val alertOnce = n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0
        if (prev == null || (prev != sbn.postTime && !alertOnce)) onArrive?.invoke()
    }

    // 掲示板に流す通知かどうか（refresh と同じ条件）
    private fun isShown(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false
        if (sbn.isOngoing) return false
        if (sbn.packageName in blockedPkgs) return false
        if (!launchable(sbn.packageName)) return false
        val n = sbn.notification ?: return false
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return false
        if (n.category != null && n.category in blockedCategories) return false
        val ex = n.extras ?: return false
        if ((ex.getString(Notification.EXTRA_TEMPLATE) ?: "").contains("MediaStyle")) return false
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = (ex.getCharSequence(Notification.EXTRA_TEXT) ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT))
            ?.toString()?.trim() ?: ""
        return title.isNotEmpty() || text.isNotEmpty()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.key?.let { lastPost.remove(it) }
        refresh()
    }

    // ===== 重い処理は別のスレッドで（画面の動きを止めない） =====
    private val worker = android.os.HandlerThread("notif").apply { start() }
    private val bg = android.os.Handler(worker.looper)
    // アプリ名・起動できるかは、毎回調べずに覚えておく
    private val labelCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val launchCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun launchable(pkg: String): Boolean = launchCache.getOrPut(pkg) {
        packageManager.getLaunchIntentForPackage(pkg) != null
    }

    private fun label(pkg: String): String = labelCache.getOrPut(pkg) {
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { pkg }
    }

    private val doRefresh = Runnable { refreshNow() }

    override fun onDestroy() {
        super.onDestroy()
        worker.quitSafely()
    }

    // 通知が続けて来ても、まとめて1回だけ数え直す
    private fun refresh() {
        bg.removeCallbacks(doRefresh)
        bg.postDelayed(doRefresh, 120)
    }

    // 現在出ている通知を数え直して更新（別スレッド）
    private fun refreshNow() {
        try {
            val list = activeNotifications ?: emptyArray()
            activePackages = list.mapNotNull { it.packageName }.toSet()

            val latest = HashMap<String, Item>()
            for (sbn in list) {
                if (sbn.packageName == packageName) continue
                if (sbn.isOngoing) continue
                if (sbn.packageName in blockedPkgs) continue
                // ホームに出てこない（起動できない）アプリの通知は流さない
                if (!launchable(sbn.packageName)) continue
                val n = sbn.notification ?: continue
                if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
                if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) continue
                if (n.category != null && n.category in blockedCategories) continue
                val ex = n.extras ?: continue
                // 音楽・動画の再生コントロールは流さない
                val tmpl = ex.getString(Notification.EXTRA_TEMPLATE) ?: ""
                if (tmpl.contains("MediaStyle")) continue
                val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
                val text = (ex.getCharSequence(Notification.EXTRA_TEXT)
                    ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()
                    ?.replace("\n", " ")?.trim() ?: ""
                if (title.isEmpty() && text.isEmpty()) continue
                val prev = latest[sbn.packageName]
                if (prev != null && prev.time >= sbn.postTime) continue
                val app = label(sbn.packageName)
                latest[sbn.packageName] = Item(sbn.packageName, app, title, text, sbn.postTime, n.contentIntent)
            }
            items = latest.values.sortedByDescending { it.time }
            onChange?.invoke()
        } catch (e: Exception) {}
    }
}
