package nl.kpn.slim

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The TV guide: channels as rows, time as columns, drawn straight onto a canvas (no per-cell views).
 *
 * The activity owns the data and the playback; this view only knows what to show and how to move around.
 * It does not take focus: [MainActivity] hands it the remote's keys through [onKey].
 *
 * Moving: Left/Right = previous/next programme of the row, Up/Down = other channel (the programme at the same
 * time), channel keys = a page of channels, fast forward / rewind = 3 h, Red = back to now, OK = watch,
 * Info/Green = details. Programmes are loaded around the visible part by the activity (see [Listener.onMoved]).
 */
class GuideView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    interface Listener {
        /** OK on [program] of channel [row]; [program] is null when nothing is known there. */
        fun onWatch(row: Int, program: Program?)
        fun onInfo(row: Int, program: Program)
        fun onClose()
        /** The visible part changed (selection, scroll, size): load what is missing around it. */
        fun onMoved()
    }

    var listener: Listener? = null
    private var channels: List<Channel> = emptyList()
    private var epg: Epg = Epg()

    /** Index of the channel that is being watched (shown in green), or -1. */
    var playingIndex = -1
        set(value) { field = value; invalidate() }

    // ---- view state ----
    private var viewStart = 0L
    private var firstRow = 0
    private var selRow = 0
    private var selProg: Program? = null
    /** The time the cursor sits at; Up/Down pick the programme of the new row that is on at this time. */
    private var anchorMs = 0L

    val firstVisibleRow get() = firstRow
    val visibleRows get() = rows
    /** Visible time range, for loading. */
    val windowStart get() = viewStart
    val windowEnd get() = viewStart + spanMs

    // ---- geometry (px) ----
    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    private val headerH = 36 * dp
    private val rowH = 46 * dp
    private val footerH = 96 * dp
    private val chanW = 170 * dp
    private val gap = 1.5f * dp
    private var rows = 1
    private var pxPerMs = 1f
    private var spanMs = VISIBLE_MS

    // ---- paints ----
    private val green = context.getColor(R.color.kpn_green)
    private val fill = Paint()
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint().apply { strokeWidth = dp }
    private val dayFormat = SimpleDateFormat("EEE d MMM", Locale.getDefault())

    private val bgPanel = 0xF2101010.toInt()
    private val cellNormal = 0xFF2B2B2B.toInt()
    private val cellNow = 0xFF1C4A27.toInt()
    private val cellPast = 0xFF1B1B1B.toInt()
    private val chanBg = 0xFF1A1A1A.toInt()
    private val chanSel = 0xFF333333.toInt()

    init { isFocusable = false }

    /** Opens on [row], at the programme that is on now. */
    fun open(channels: List<Channel>, epg: Epg, row: Int, playing: Int) {
        this.channels = channels
        this.epg = epg
        playingIndex = playing
        selRow = row.coerceIn(0, (channels.size - 1).coerceAtLeast(0))
        goToNow()
    }

    /** New programmes arrived (or were trimmed): re-pick the selection if it is gone. */
    fun dataChanged() {
        if (selProg == null || selProg !in rowPrograms(selRow)) {
            selProg = programAt(selRow, anchorMs)
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rows = ((h - headerH - footerH) / rowH).toInt().coerceAtLeast(1)
        pxPerMs = (w - chanW) / VISIBLE_MS.toFloat()
        spanMs = VISIBLE_MS
        if (channels.isNotEmpty()) { scrollIntoView(); listener?.onMoved() }
    }

    // ---- keys ----

    /** Returns true when the key was used. */
    fun onKey(code: Int): Boolean {
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> moveProgram(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveProgram(+1)
            KeyEvent.KEYCODE_DPAD_UP -> moveRow(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveRow(+1)
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_DOWN -> moveRow(+rows)
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP -> moveRow(-rows)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, KeyEvent.KEYCODE_MEDIA_STEP_FORWARD ->
                moveTime(+PAGE_MS)
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD, KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD ->
                moveTime(-PAGE_MS)
            KeyEvent.KEYCODE_PROG_RED -> goToNow()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> listener?.onWatch(selRow, selProg)
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_PROG_GREEN -> selProg?.let { listener?.onInfo(selRow, it) }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE -> listener?.onClose()
            else -> return false
        }
        return true
    }

    private fun moveProgram(dir: Int) {
        val list = rowPrograms(selRow)
        val i = selProg?.let { list.indexOf(it) } ?: -1
        if (i < 0) { selProg = programAt(selRow, anchorMs); changed(); return }
        val next = list.getOrNull(i + dir) ?: return
        if (next.endMs < minTime() || next.startMs > maxTime()) return
        selProg = next
        scrollIntoView()
        anchorMs = maxOf(next.startMs, viewStart)
        changed()
    }

    private fun moveRow(delta: Int) {
        if (channels.isEmpty()) return
        val row = (selRow + delta).coerceIn(0, channels.size - 1)
        if (row == selRow) return
        selRow = row
        selProg = programAt(row, anchorMs)
        scrollIntoView(alongTime = false)
        changed()
    }

    private fun moveTime(delta: Long) {
        viewStart = clampStart(viewStart + delta)
        anchorMs = viewStart + 2 * 60_000
        selProg = programAt(selRow, anchorMs)
        changed()
    }

    private fun goToNow() {
        val now = System.currentTimeMillis()
        viewStart = clampStart(now - LOOKBACK_MS - Math.floorMod(now - LOOKBACK_MS, TICK_MS))
        anchorMs = now
        selProg = programAt(selRow, anchorMs)
        scrollIntoView()
        changed()
    }

    private fun changed() {
        invalidate()
        listener?.onMoved()
    }

    /**
     * Keeps the selected row on screen and, with [alongTime], the selected programme too. Moving between rows
     * leaves the time axis alone: a long programme that starts off screen is still selected, not scrolled to.
     */
    private fun scrollIntoView(alongTime: Boolean = true) {
        if (selRow < firstRow) firstRow = selRow else if (selRow >= firstRow + rows) firstRow = selRow - rows + 1
        firstRow = firstRow.coerceIn(0, (channels.size - rows).coerceAtLeast(0))
        val p = selProg?.takeIf { alongTime } ?: return
        if (p.startMs < viewStart) viewStart = p.startMs
        else if (p.endMs > viewStart + spanMs) viewStart = minOf(p.startMs, p.endMs - spanMs)
        viewStart = clampStart(viewStart)
    }

    private fun minTime() = System.currentTimeMillis() - MAX_PAST_MS
    private fun maxTime() = System.currentTimeMillis() + MAX_FUTURE_MS
    private fun clampStart(t: Long) = t.coerceIn(minTime(), maxTime() - spanMs)

    private fun rowPrograms(row: Int): List<Program> =
        channels.getOrNull(row)?.let { epg.programs(it.id) } ?: emptyList()

    /** The programme on at [t], else the nearest one within [NEAR_MS]; null when the row has nothing there. */
    private fun programAt(row: Int, t: Long): Program? {
        val list = rowPrograms(row)
        list.firstOrNull { t >= it.startMs && t < it.endMs }?.let { return it }
        fun distance(p: Program) = if (t < p.startMs) p.startMs - t else t - p.endMs
        return list.minByOrNull(::distance)?.takeIf { distance(it) <= NEAR_MS }
    }

    // ---- drawing ----

    private fun timeToX(t: Long) = chanW + (t - viewStart) * pxPerMs

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        canvas.drawColor(bgPanel)
        val now = System.currentTimeMillis()
        val end = viewStart + spanMs

        // Rows first, the header and the channel column are painted over their edges afterwards.
        for (r in 0 until rows) {
            val idx = firstRow + r
            val ch = channels.getOrNull(idx) ?: break
            val y = headerH + r * rowH
            for (p in rowPrograms(idx)) {
                if (p.endMs <= viewStart || p.startMs >= end) continue
                drawCell(canvas, p, y, now, selected = idx == selRow && p == selProg)
            }
            drawChannel(canvas, ch, idx, y)
        }

        // Header: ticks every half hour, the date of the left edge in the corner.
        fill.color = bgPanel
        canvas.drawRect(0f, 0f, w, headerH, fill)
        text.reset(); text.isAntiAlias = true
        text.textSize = 14 * sp
        text.color = 0xFFFFFFFF.toInt()
        text.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(dayFormat.format(Date(viewStart + 60_000)), 12 * dp, headerH * 0.68f, text)
        text.typeface = Typeface.DEFAULT
        var t = viewStart + (TICK_MS - Math.floorMod(viewStart, TICK_MS)) % TICK_MS
        while (t < end) {
            val x = timeToX(t)
            if (x >= chanW) {
                val hour = Math.floorMod(t, 3_600_000L) == 0L
                line.color = if (hour) 0x55FFFFFF else 0x22FFFFFF
                canvas.drawLine(x, headerH * (if (hour) 0.3f else 0.6f), x, headerH, line)
                text.color = if (hour) 0xFFFFFFFF.toInt() else 0x99FFFFFF.toInt()
                text.textSize = 13 * sp
                canvas.drawText(epg.time(t), x + 4 * dp, headerH * 0.68f, text)
            }
            t += TICK_MS
        }

        // "Now" marker.
        if (now in viewStart until end) {
            val x = timeToX(now)
            line.color = green
            line.strokeWidth = 2 * dp
            canvas.drawLine(x, headerH * 0.2f, x, headerH + rows * rowH, line)
            line.strokeWidth = dp
        }

        drawFooter(canvas, now)
    }

    private fun drawChannel(canvas: Canvas, ch: Channel, idx: Int, y: Float) {
        fill.color = if (idx == selRow) chanSel else chanBg
        canvas.drawRect(0f, y, chanW - gap, y + rowH - gap, fill)
        if (idx == selRow) { fill.color = green; canvas.drawRect(0f, y, 4 * dp, y + rowH - gap, fill) }
        text.reset(); text.isAntiAlias = true
        text.textSize = 13 * sp
        text.typeface = Typeface.DEFAULT_BOLD
        text.color = if (idx == playingIndex) green else 0xB3FFFFFF.toInt()
        val num = ch.number.toString()
        canvas.drawText(num, 12 * dp, y + rowH * 0.62f, text)
        val nameX = 12 * dp + text.measureText("000") + 8 * dp
        text.typeface = Typeface.DEFAULT
        text.textSize = 14 * sp
        text.color = 0xFFFFFFFF.toInt()
        canvas.drawText(TextUtils.ellipsize(ch.name, text, chanW - nameX - 8 * dp, TextUtils.TruncateAt.END).toString(), nameX, y + rowH * 0.62f, text)
    }

    private fun drawCell(canvas: Canvas, p: Program, y: Float, now: Long, selected: Boolean) {
        val x0 = maxOf(chanW, timeToX(p.startMs)) + gap
        val x1 = minOf(width.toFloat(), timeToX(p.endMs)) - gap
        if (x1 - x0 < 3 * dp) return
        val airing = p.startMs <= now && p.endMs > now
        val past = p.endMs <= now
        fill.color = when {
            selected -> green
            airing -> cellNow
            past -> cellPast
            else -> cellNormal
        }
        canvas.drawRect(x0, y, x1, y + rowH - gap, fill)

        canvas.save()
        canvas.clipRect(x0, y, x1, y + rowH - gap)
        text.reset(); text.isAntiAlias = true
        val dim = past && !p.catchUp && !p.startOver // gone for good: no way to watch it
        text.color = if (selected) 0xFFFFFFFF.toInt() else if (dim) 0x66FFFFFF else 0xFFFFFFFF.toInt()
        text.textSize = 15 * sp
        text.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        canvas.drawText(p.title, x0 + 8 * dp, y + rowH * 0.45f, text)
        text.typeface = Typeface.DEFAULT
        text.textSize = 11 * sp
        text.color = if (selected) 0xE6FFFFFF.toInt() else if (dim) 0x44FFFFFF else 0x99FFFFFF.toInt()
        val sub = epg.time(p.startMs) + "–" + epg.time(p.endMs) + if (past && (p.catchUp || p.startOver)) "  ↺" else ""
        canvas.drawText(sub, x0 + 8 * dp, y + rowH * 0.82f, text)
        canvas.restore()
    }

    private fun drawFooter(canvas: Canvas, now: Long) {
        val top = headerH + rows * rowH
        val w = width.toFloat()
        fill.color = 0xFF181818.toInt()
        canvas.drawRect(0f, top, w, height.toFloat(), fill)
        val x = 16 * dp
        val avail = w - 2 * x
        text.reset(); text.isAntiAlias = true
        val p = selProg
        if (p == null) {
            text.textSize = 16 * sp
            text.color = 0x99FFFFFF.toInt()
            val ch = channels.getOrNull(selRow)
            canvas.drawText("${ch?.name.orEmpty()}: no guide information here", x, top + 30 * dp, text)
        } else {
            text.typeface = Typeface.DEFAULT_BOLD
            text.textSize = 18 * sp
            text.color = 0xFFFFFFFF.toInt()
            canvas.drawText(TextUtils.ellipsize(p.title, text, avail, TextUtils.TruncateAt.END).toString(), x, top + 26 * dp, text)

            text.typeface = Typeface.DEFAULT
            text.textSize = 13 * sp
            text.color = 0xB3FFFFFF.toInt()
            val status = when {
                p.startMs > now -> "Not aired yet"
                p.endMs > now -> "On now"
                p.catchUp || p.startOver -> "OK to watch"
                else -> "Not available"
            }
            val meta = listOfNotNull(
                dayFormat.format(Date(p.startMs)) + " " + epg.time(p.startMs) + " – " + epg.time(p.endMs) + " (${(p.endMs - p.startMs) / 60_000} min)",
                p.genres.takeIf { it.isNotEmpty() }?.take(3)?.joinToString(", "),
                status,
            ).joinToString("  ·  ")
            canvas.drawText(TextUtils.ellipsize(meta, text, avail, TextUtils.TruncateAt.END).toString(), x, top + 46 * dp, text)

            val desc = p.episodeTitle.takeIf { it.isNotEmpty() }?.let { "$it. " }.orEmpty() + p.description
            text.color = 0x99FFFFFF.toInt()
            canvas.drawText(TextUtils.ellipsize(desc, text, avail, TextUtils.TruncateAt.END).toString(), x, top + 66 * dp, text)
        }
        text.textSize = 11 * sp
        text.color = 0x66FFFFFF
        canvas.drawText("◀ ▶ programme    ▲ ▼ channel    CH± page    FF / REW ±3 h    Red now    Info details    OK watch    Back close",
            x, height - 8 * dp, text)
    }

    companion object {
        /** The grid always shows this much time. */
        private const val VISIBLE_MS = 3L * 3_600_000
        private const val PAGE_MS = 3L * 3_600_000
        private const val TICK_MS = 30L * 60_000
        private const val LOOKBACK_MS = 30L * 60_000
        private const val NEAR_MS = 90L * 60_000
        /** How far the grid scrolls (the catch-up archive is a week deep; the guide data about as far ahead). */
        private const val MAX_PAST_MS = 7L * 24 * 3_600_000
        private const val MAX_FUTURE_MS = 7L * 24 * 3_600_000
    }
}
