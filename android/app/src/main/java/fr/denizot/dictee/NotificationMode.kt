package fr.denizot.dictee

import android.content.Context

/**
 * Local-only notification presentation preference. Defaults to the experiment,
 * and can be disabled instantly without changing capture sessions or files.
 */
object NotificationMode {
    private const val PREFERENCES = "dictee_notification_presentation"
    private const val KEY_MEDIA_STYLE = "media_style"

    fun mediaStyleEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_MEDIA_STYLE, true)

    fun setMediaStyleEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_MEDIA_STYLE, value).apply()
    }
}
