package nl.kpn.slim

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory guide. Reads are cheap and main-thread only.
 *
 * Two users feed it: the periodic now/next refresh (all channels, next 3 h) and the TV guide, which loads a few
 * channels and a few hours at a time as the viewer scrolls. [covered] remembers which (channel, [SLOT_MS]) pairs
 * the guide already asked for, so scrolling back does not refetch. [trim] drops what is no longer needed.
 */
class Epg {
    private var byChannel: Map<Int, List<Program>> = emptyMap()
    private var loadedAt = 0L
    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val covered = HashSet<Long>()

    /** Adds fetched programmes. Fresh data wins per start time; nothing is dropped (see [trim]). */
    fun merge(data: Map<Int, List<Program>>) {
        val merged = HashMap(byChannel)
        for ((id, fresh) in data) {
            val freshStarts = fresh.mapTo(HashSet()) { it.startMs }
            val old = byChannel[id].orEmpty().filter { it.startMs !in freshStarts }
            merged[id] = (old + fresh).sortedBy { it.startMs }
        }
        byChannel = merged
    }

    /** The periodic now/next refresh: [merge] plus the time stamp that [isStale] looks at. */
    fun replace(data: Map<Int, List<Program>>) {
        merge(data)
        loadedAt = System.currentTimeMillis()
    }

    fun isStale() = System.currentTimeMillis() - loadedAt > REFRESH_MS

    /** Drops programmes (and coverage marks) entirely outside [keepFromMs, keepToMs). */
    fun trim(keepFromMs: Long, keepToMs: Long) {
        byChannel = byChannel.mapValues { (_, l) -> l.filter { it.endMs > keepFromMs && it.startMs < keepToMs } }
            .filterValues { it.isNotEmpty() }
        covered.removeAll { key ->
            val slot = key % SLOT_KEY
            (slot + 1) * SLOT_MS <= keepFromMs || slot * SLOT_MS >= keepToMs
        }
    }

    /** All known programmes of a channel, sorted by start. */
    fun programs(channelId: Int): List<Program> = byChannel[channelId].orEmpty()

    /**
     * Marks every (channel, slot) pair of [channelIds] x [fromMs, toMs] as requested and returns the ones that
     * were not yet, as slot to channel ids. The caller must fetch them, and [release] them if that fails.
     */
    fun claimMissing(channelIds: List<Int>, fromMs: Long, toMs: Long): Map<Long, List<Int>> {
        val out = LinkedHashMap<Long, MutableList<Int>>()
        for (slot in Math.floorDiv(fromMs, SLOT_MS)..Math.floorDiv(toMs, SLOT_MS)) {
            for (id in channelIds) if (covered.add(id.toLong() * SLOT_KEY + slot)) out.getOrPut(slot) { ArrayList() }.add(id)
        }
        return out
    }

    fun release(slot: Long, channelIds: List<Int>) {
        for (id in channelIds) covered.remove(id.toLong() * SLOT_KEY + slot)
    }

    /** What is on at [at] (default: now), or null when unknown. */
    fun now(channelId: Int, at: Long = System.currentTimeMillis()): Program? =
        byChannel[channelId]?.firstOrNull { at >= it.startMs && at < it.endMs }

    fun next(channelId: Int, at: Long = System.currentTimeMillis()): Program? =
        byChannel[channelId]?.firstOrNull { it.startMs > at }

    fun time(ms: Long): String = clock.format(Date(ms))

    /** "20:00-20:27  NOS Journaal" for list rows; null when there is no data. */
    fun nowLine(channelId: Int): String? =
        now(channelId)?.let { "${time(it.startMs)}-${time(it.endMs)}  ${it.title}" }

    companion object {
        const val REFRESH_MS = 15L * 60 * 1000
        /** Granularity of the guide's requests. */
        const val SLOT_MS = 3L * 3_600_000
        private const val SLOT_KEY = 1_000_000L // key = channelId * SLOT_KEY + slot (slots are ~ 80,000 today)
    }
}
