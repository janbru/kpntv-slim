package nl.kpn.slim

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tiny image loader for channel logos and programme artwork: in-memory LRU capped by bytes (not count,
 * since a 320x180 thumbnail is about 100x the size of a logo), a few parallel downloads, no disk cache.
 */
class LogoLoader(private val scope: CoroutineScope) {
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val limit = Semaphore(4)
    private val failed = HashSet<String>()

    /** The view is GONE until a bitmap arrives, so a missing image leaves no gap. */
    fun load(url: String?, into: ImageView) {
        // Same image already shown or on its way (the banner re-binds every second): nothing to do.
        if (url != null && into.tag == url) return
        into.tag = url
        into.setImageDrawable(null)
        into.visibility = View.GONE
        if (url == null || url in failed) return
        cache.get(url)?.let { show(into, it); return }
        scope.launch {
            val bmp = cache.get(url) ?: withContext(Dispatchers.IO) {
                limit.withPermit { fetch(url) }
            }
            if (bmp == null) { failed += url; return@launch }
            cache.put(url, bmp)
            if (into.tag == url) show(into, bmp)
        }
    }

    private fun show(into: ImageView, bmp: Bitmap) {
        into.setImageBitmap(bmp)
        into.visibility = View.VISIBLE
    }

    private fun fetch(url: String): Bitmap? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        try {
            if (conn.responseCode != 200) null
            else conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally { conn.disconnect() }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val CACHE_BYTES = 12 * 1024 * 1024
    }
}
