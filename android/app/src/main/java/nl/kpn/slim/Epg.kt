package nl.kpn.slim

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory now/next guide. Replaced wholesale on refresh; reads are cheap and main-thread only. */
class Epg {
    private var byChannel: Map<Int, List<Program>> = emptyMap()
    private var loadedAt = 0L
    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())

    /**
     * Merges a fresh fetch into what we have. Fresh data wins per start time, and programmes that ended
     * up to [KEEP_PAST_MS] ago are kept: someone watching a started-over programme must still see its info.
     */
    fun replace(data: Map<Int, List<Program>>) {
        val now = System.currentTimeMillis()
        val keepAfter = now - KEEP_PAST_MS
        val merged = HashMap<Int, List<Program>>(data.size * 2)
        for (id in data.keys + byChannel.keys) {
            val fresh = data[id].orEmpty()
            val freshStarts = fresh.mapTo(HashSet()) { it.startMs }
            val old = byChannel[id].orEmpty().filter { it.endMs > keepAfter && it.startMs !in freshStarts }
            merged[id] = (old + fresh).sortedBy { it.startMs }
        }
        byChannel = merged
        loadedAt = now
    }

    fun isStale() = System.currentTimeMillis() - loadedAt > REFRESH_MS

    /** Title of what is on right now, or null when unknown. */
    fun now(channelId: Int, at: Long = System.currentTimeMillis()): Program? {
        val t = at
        return byChannel[channelId]?.firstOrNull { t >= it.startMs && t < it.endMs }
    }

    fun next(channelId: Int, at: Long = System.currentTimeMillis()): Program? {
        val t = at
        return byChannel[channelId]?.firstOrNull { it.startMs > t }
    }

    fun time(ms: Long): String = clock.format(Date(ms))

    /** "20:00-20:27  NOS Journaal" for list rows; null when there is no data. */
    fun nowLine(channelId: Int): String? =
        now(channelId)?.let { "${time(it.startMs)}-${time(it.endMs)}  ${it.title}" }

    companion object {
        const val REFRESH_MS = 15L * 60 * 1000
        private const val KEEP_PAST_MS = 6L * 3_600_000 // the start-over window is about 6 h deep
    }
}
