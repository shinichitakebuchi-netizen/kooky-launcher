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
        // 今通知が出ているアプリのパッケージ名の集合
        val activePackages = mutableSetOf<String>()
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
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        refresh()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refresh()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refresh()
    }

    // 現在出ている通知を数え直して更新
    private fun refresh() {
        try {
            val list = activeNotifications ?: emptyArray()
            val pkgs = list.mapNotNull { it.packageName }.toMutableSet()
            activePackages.clear()
            activePackages.addAll(pkgs)

            val latest = HashMap<String, Item>()
            for (sbn in list) {
                if (sbn.packageName == packageName) continue
                if (sbn.isOngoing) continue
                if (sbn.packageName in blockedPkgs) continue
                // ホームに出てこない（起動できない）アプリの通知は流さない
                if (packageManager.getLaunchIntentForPackage(sbn.packageName) == null) continue
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
                val app = try {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
                } catch (e: Exception) { sbn.packageName }
                latest[sbn.packageName] = Item(sbn.packageName, app, title, text, sbn.postTime, n.contentIntent)
            }
            items = latest.values.sortedByDescending { it.time }
            onChange?.invoke()
        } catch (e: Exception) {}
    }
}
