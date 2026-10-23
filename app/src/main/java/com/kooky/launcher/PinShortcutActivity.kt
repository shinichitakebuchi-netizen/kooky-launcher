package com.kooky.launcher

import android.app.Activity
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Bundle
import android.widget.Toast

// ブラウザなどの「ホーム画面に追加」を受け取る画面（見えない）
class PinShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val la = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val req = la.getPinItemRequest(intent)
            if (req != null && req.isValid &&
                req.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT) {
                val label = req.shortcutInfo?.shortLabel?.toString() ?: "ショートカット"
                if (req.accept()) {
                    Toast.makeText(this, "「$label」をホームに追加しました", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "ホームに追加できませんでした", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
