package com.github.lsposed.magicwindow.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import com.github.lsposed.magicwindow.common.Constants

/**
 * 接收 system_server 侧抓取到的页面记录。
 *
 * 只实现 [insert]：hook 进程拿不到模块的 SharedPreferences 写权限，
 * 因此用 ContentProvider 作为唯一回传通道（对应 Constants.CAPTURE_AUTHORITY）。
 */
class CaptureProvider : ContentProvider() {

    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(Constants.CAPTURE_AUTHORITY, Constants.CAPTURE_PATH, CODE_RECORD)
    }

    override fun onCreate(): Boolean = true

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (matcher.match(uri) != CODE_RECORD) {
            throw IllegalArgumentException("Unknown URL $uri")
        }
        val ctx = context ?: return null
        val pkg = values?.getAsString(Constants.CAPTURE_COL_PACKAGE) ?: return null
        val activity = values.getAsString(Constants.CAPTURE_COL_ACTIVITY) ?: return null
        CaptureStore.add(ctx, pkg, activity)
        return uri
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val CODE_RECORD = 1
    }
}
