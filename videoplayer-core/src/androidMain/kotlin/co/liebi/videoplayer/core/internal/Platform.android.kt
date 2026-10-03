package co.liebi.videoplayer.core.internal

import android.annotation.SuppressLint
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log
import co.liebi.videoplayer.core.BufferingConfig

internal actual fun createPlatformEngine(buffering: BufferingConfig): PlaybackEngine =
    ExoPlaybackEngine(ApplicationContext.get(), buffering)

internal actual fun logWarning(message: String) {
    Log.w("LiebiVideoPlayer", message)
}

internal object ApplicationContext {
    @SuppressLint("StaticFieldLeak") // Always the application context.
    private var context: Context? = null

    fun set(context: Context) {
        this.context = context.applicationContext
    }

    fun get(): Context = checkNotNull(context) {
        "LiebiVideoPlayer was not initialized. Make sure VideoPlayerContextProvider is not removed from the merged manifest."
    }
}

/** Runs at app start-up, before Application.onCreate, to capture the application context. */
internal class VideoPlayerContextProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        ApplicationContext.set(checkNotNull(context))
        return true
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
