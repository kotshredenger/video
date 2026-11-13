package com.example.universalplayer

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import java.util.Locale

object Utils {

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("player", Context.MODE_PRIVATE)

    fun formatTime(ms: Long): String {
        val total = ms.coerceAtLeast(0L) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    fun displayName(ctx: Context, uri: Uri): String {
        if (uri.scheme == "content") {
            try {
                ctx.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0) {
                            val name = c.getString(i)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
            } catch (e: Exception) {
                // игнорируем, вернём запасное имя
            }
        }
        return uri.lastPathSegment ?: uri.toString()
    }

    // ---------- История ----------

    fun history(ctx: Context): MutableList<String> =
        (prefs(ctx).getString("history", "") ?: "")
            .split("\n")
            .filter { it.isNotBlank() }
            .toMutableList()

    private fun saveHistory(ctx: Context, list: List<String>) {
        prefs(ctx).edit().putString("history", list.joinToString("\n")).apply()
    }

    fun addHistory(ctx: Context, uri: Uri) {
        val list = history(ctx)
        list.remove(uri.toString())
        list.add(0, uri.toString())
        while (list.size > 30) list.removeAt(list.size - 1)
        saveHistory(ctx, list)
    }

    fun removeHistory(ctx: Context, uriString: String) {
        val list = history(ctx)
        list.remove(uriString)
        saveHistory(ctx, list)
    }

    fun clearHistory(ctx: Context) {
        saveHistory(ctx, emptyList())
    }
}
