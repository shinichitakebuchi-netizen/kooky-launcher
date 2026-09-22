package com.kooky.launcher

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotificationService : NotificationListenerService() {

    companion object {
        // 今通知が出ているアプリのパッケージ名の集合
        val activePackages = mutableSetOf<String>()
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

    // 現在出ている通知を数え直して集合を更新
    private fun refresh() {
        try {
            val pkgs = activeNotifications?.mapNotNull { it.packageName }?.toMutableSet()
                ?: mutableSetOf()
            activePackages.clear()
            activePackages.addAll(pkgs)
            onChange?.invoke()
        } catch (e: Exception) {}
    }
}