package com.nickwoluff.wearfiles

import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

data class ShareTarget(val component: ComponentName, val label: String)

fun fileShareIntent(uri: Uri, mime: String): Intent = Intent(Intent.ACTION_SEND)
    .setType(mime)
    .putExtra(Intent.EXTRA_STREAM, uri)
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    .apply { clipData = ClipData.newRawUri("文件", uri) }

fun fileShareTargets(manager: PackageManager, intent: Intent): List<ShareTarget> =
    manager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        .filter { result ->
            val info = result.activityInfo
            info.exported && info.enabled && info.applicationInfo.enabled &&
                // Wear 模拟器的 EmailStub 仅占位，启动后立即退出，无法接收文件。
                info.packageName != "com.google.android.wearable.frameworkpackagestubs"
        }
        .map { result -> ShareTarget(
            ComponentName(result.activityInfo.packageName, result.activityInfo.name),
            result.loadLabel(manager).toString()
        ) }
        .distinctBy { it.component }
        .sortedBy { it.label }
