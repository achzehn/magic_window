package com.github.lsposed.magicwindow.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class AppItem(
    val packageName: String,
    val label: String,
    val isSystem: Boolean
) {
    fun icon(pm: PackageManager): Drawable? =
        runCatching { pm.getApplicationIcon(packageName) }.getOrNull()
}

object AppLoader {

    fun load(context: Context): List<AppItem> {
        val pm = context.packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { it.packageName != context.packageName }
            .map {
                AppItem(
                    packageName = it.packageName,
                    label = runCatching { pm.getApplicationLabel(it).toString() }
                        .getOrDefault(it.packageName),
                    isSystem = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }
            .sortedWith(compareBy({ it.isSystem }, { it.label.lowercase() }))
            .toList()
    }
}
