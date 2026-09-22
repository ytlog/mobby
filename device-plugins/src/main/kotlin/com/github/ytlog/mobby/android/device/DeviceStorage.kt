package com.github.ytlog.mobby.android.device

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.app.Activity
import android.os.Bundle

object DeviceStorage {
    private const val PREF = "device-plugins"
    private const val TREE = "storage-tree"
    private const val FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    fun persist(context: Context, uri: Uri) {
        val resolver = context.contentResolver
        val previous = tree(context)
        resolver.takePersistableUriPermission(uri, FLAGS)
        if (previous != null && previous != uri) runCatching { resolver.releasePersistableUriPermission(previous, FLAGS) }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(TREE, uri.toString()).apply()
    }
    fun tree(context: Context): Uri? {
        val stored = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(TREE, null) ?: return null
        val uri = Uri.parse(stored)
        val held = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        return uri.takeIf { held }
    }
}

object DeviceForeground : Application.ActivityLifecycleCallbacks {
    @Volatile var started: Int = 0
        private set
    override fun onActivityStarted(activity: Activity) { started++ }
    override fun onActivityStopped(activity: Activity) { started = (started - 1).coerceAtLeast(0) }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
    fun foreground() = started > 0
}

class DeviceInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        (context?.applicationContext as? Application)?.registerActivityLifecycleCallbacks(DeviceForeground)
        return true
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
