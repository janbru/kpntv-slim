package nl.kpn.slim

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single screen: full-screen live player, with a channel list overlay.
 *
 * Remote: Up/Down or CH+/CH- zap, digits jump to a channel number, OK/Menu/Left opens the list,
 * Back closes the list (or exits), Info shows the channel banner.
 *
 * Plain Activity (not AppCompat) to keep the APK small; we provide our own LifecycleOwner
 * for coroutine scoping.
 */
class MainActivity : Activity(), LifecycleOwner {

    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private val avs = AvsClient(BuildConfig.KPN_ACCOUNT, BuildConfig.KPN_DEVICE_ID)
    private lateinit var logos: LogoLoader
    private val epg = Epg()
    private var epgJob: Job? = null

    private lateinit var playerView: PlayerView
    private lateinit var list: RecyclerView
    private lateinit var osd: View
    private lateinit var osdNumber: TextView
    private lateinit var osdName: TextView
    private lateinit var osdClock: TextView
    private lateinit var osdStream: TextView
    private lateinit var osdTitle: TextView
    private lateinit var osdTime: TextView
    private lateinit var osdNext: TextView
    private lateinit var osdProgress: ProgressBar
    private lateinit var status: TextView
    private lateinit var paused: View // pause icon
    private lateinit var options: View
    private lateinit var rowPause: TextView
    private lateinit var rowStartOver: View
    private lateinit var rowLive: View
    private lateinit var info: View
    private lateinit var infoTitle: TextView
    private lateinit var infoEpisode: TextView
    private lateinit var infoMeta: TextView
    private lateinit var infoDesc: TextView
    private lateinit var infoCredits: TextView
    private lateinit var settings: View
    private lateinit var rowHighBw: View
    private lateinit var rowBanner: View
    private lateinit var valueHighBw: TextView
    private lateinit var valueBanner: TextView

    private var player: ExoPlayer? = null
    private var channels: List<Channel> = emptyList()
    private var adapter: ChannelAdapter? = null
    private var current = -1
    private var previous = -1

    private var tuneJob: Job? = null
    private var osdJob: Job? = null
    private var digitJob: Job? = null
    private var digits = ""
    private var retriedCurrent = false
    private var noticeJob: Job? = null

    /** Wall-clock start of the stream when watching a started-over programme; null when live. */
    private var timeshiftBase: Long? = null
    private var timeshiftReady = false

    private val prefs by lazy { getSharedPreferences("slim", MODE_PRIVATE) }

    /** Start playback at the top quality instead of ramping up from the lowest (like the official app's flag). */
    private var highBandwidthStart: Boolean
        get() = prefs.getBoolean("highBandwidthStart", false)
        set(v) = prefs.edit().putBoolean("highBandwidthStart", v).apply()

    /** How long the info banner stays up after zapping / Info, in seconds. */
    private var bannerSeconds: Int
        get() = prefs.getInt("bannerSeconds", 5).coerceIn(MIN_BANNER_S, MAX_BANNER_S)
        set(v) = prefs.edit().putInt("bannerSeconds", v.coerceIn(MIN_BANNER_S, MAX_BANNER_S)).apply()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registry.currentState = Lifecycle.State.CREATED
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.player_view)
        list = findViewById(R.id.channel_list)
        osd = findViewById(R.id.osd)
        osdNumber = findViewById(R.id.osd_number)
        osdName = findViewById(R.id.osd_name)
        osdClock = findViewById(R.id.osd_clock)
        osdStream = findViewById(R.id.osd_stream)
        osdTitle = findViewById(R.id.osd_title)
        osdTime = findViewById(R.id.osd_time)
        osdNext = findViewById(R.id.osd_next)
        osdProgress = findViewById(R.id.osd_progress)
        status = findViewById(R.id.status)
        paused = findViewById(R.id.paused)
        options = findViewById(R.id.options)
        rowPause = findViewById(R.id.row_pause)
        rowStartOver = findViewById(R.id.row_startover)
        rowLive = findViewById(R.id.row_live)
        info = findViewById(R.id.info)
        infoTitle = findViewById(R.id.info_title)
        infoEpisode = findViewById(R.id.info_episode)
        infoMeta = findViewById(R.id.info_meta)
        infoDesc = findViewById(R.id.info_desc)
        infoCredits = findViewById(R.id.info_credits)
        rowPause.setOnClickListener { hideOptions(); togglePause() }
        rowStartOver.setOnClickListener { hideOptions(); startOver() }
        rowLive.setOnClickListener { hideOptions(); goLive() }
        findViewById<View>(R.id.row_info).setOnClickListener { hideOptions(); showInfo() }
        findViewById<View>(R.id.row_channels).setOnClickListener { hideOptions(); showList() }
        findViewById<View>(R.id.row_settings).setOnClickListener { hideOptions(); showSettings() }
        settings = findViewById(R.id.settings)
        rowHighBw = findViewById(R.id.row_highbw)
        rowBanner = findViewById(R.id.row_banner)
        valueHighBw = findViewById(R.id.value_highbw)
        valueBanner = findViewById(R.id.value_banner)
        rowHighBw.setOnClickListener { adjustSetting(rowHighBw, 1) }
        rowBanner.setOnClickListener { adjustSetting(rowBanner, 1) }
        list.layoutManager = LinearLayoutManager(this)
        logos = LogoLoader(lifecycleScope)

        loadChannels()
    }

    override fun onStart() {
        super.onStart()
        registry.currentState = Lifecycle.State.STARTED
        buildPlayer()
        if (current >= 0) tune(current, debounce = false)
    }

    private fun buildPlayer() {
        val builder = ExoPlayer.Builder(this)
        if (highBandwidthStart) {
            // A generous initial estimate makes adaptive selection pick the top rendition (8 Mbps) right away.
            builder.setBandwidthMeter(DefaultBandwidthMeter.Builder(this).setInitialBitrateEstimate(HIGH_START_BPS).build())
        }
        player = builder.build().also { p ->
            p.playWhenReady = true
            p.addListener(playerListener)
            playerView.player = p
        }
    }

    private fun releasePlayer() {
        tuneJob?.cancel()
        playerView.player = null
        player?.release()
        player = null
    }

    override fun onResume() {
        super.onResume()
        registry.currentState = Lifecycle.State.RESUMED
    }

    override fun onPause() {
        registry.currentState = Lifecycle.State.STARTED
        super.onPause()
    }

    override fun onStop() {
        releasePlayer()
        registry.currentState = Lifecycle.State.CREATED
        super.onStop()
    }

    override fun onDestroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    private fun loadChannels() {
        showStatus("Loading channels…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { avs.liveChannels() } }
            result.onSuccess { list ->
                channels = list
                adapter = ChannelAdapter(list, logos, epg, onSettings = { hideList(); showSettings() }) { i -> hideList(); tune(i, debounce = false) }
                this@MainActivity.list.adapter = adapter
                hideStatus()
                val last = prefs.getInt("lastChannelId", -1)
                val start = list.indexOfFirst { it.id == last }.takeIf { it >= 0 } ?: 0
                if (list.isNotEmpty()) tune(start, debounce = false)
                refreshEpg()
            }.onFailure {
                showStatus("Could not load channels:\n${it.message}\n\nPress OK to retry")
            }
        }
    }

    // ---- tuning ----

    private fun tune(index: Int, debounce: Boolean = true, startOverAt: Long? = null) {
        if (channels.isEmpty()) return
        val i = Math.floorMod(index, channels.size)
        if (i != current) { previous = current; retriedCurrent = false }
        current = i
        timeshiftBase = startOverAt
        timeshiftReady = false
        adapter?.playingIndex = i
        val ch = channels[i]
        prefs.edit().putInt("lastChannelId", ch.id).apply()
        showOsd(ch)

        tuneJob?.cancel()
        tuneJob = lifecycleScope.launch {
            if (debounce) delay(250) // let rapid zapping settle before hitting the API
            val p = player ?: return@launch
            p.stop()
            val stream = withContext(Dispatchers.IO) { runCatching { avs.liveStream(ch, startOverAt) } }
            stream.onSuccess { s ->
                hideStatus()
                val item = MediaItem.Builder()
                    .setUri(s.manifestUrl)
                    .setMimeType(MimeTypes.APPLICATION_MPD)
                    .apply {
                        if (s.licenseUrl != null) setDrmConfiguration(
                            MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                                .setLicenseUri(s.licenseUrl)
                                .build()
                        )
                    }
                    .build()
                // Start over: the manifest begins at the programme start, so position 0 is that moment.
                if (startOverAt != null) p.setMediaItem(item, 0L) else p.setMediaItem(item)
                p.playWhenReady = true
                setPausedIndicator(false)
                p.prepare()
            }.onFailure { showStatus("${ch.name}: ${it.message}") }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            // Manifest tokens / licenses expire: fetch a fresh play URL once before giving up.
            if (!retriedCurrent && current >= 0) {
                retriedCurrent = true
                tune(current, debounce = false)
            } else {
                showStatus("Playback error: ${error.errorCodeName}")
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) = bindStream()
        override fun onTracksChanged(tracks: Tracks) = bindStream()

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) { retriedCurrent = false; timeshiftReady = true; hideStatus() }
        }
    }

    // ---- remote control ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val code = event.keyCode

        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> { togglePause(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { setPaused(true); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { setPaused(false); return true }
        }

        if (info.visibility == View.VISIBLE) {
            when (code) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_PROG_GREEN -> hideInfo()
                else -> return super.dispatchKeyEvent(event) // up/down scroll the text
            }
            return true
        }

        if (options.visibility == View.VISIBLE) {
            when (code) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_DPAD_RIGHT -> hideOptions()
                else -> return super.dispatchKeyEvent(event)
            }
            return true
        }

        if (settings.visibility == View.VISIBLE) {
            when (code) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> hideSettings()
                KeyEvent.KEYCODE_DPAD_LEFT -> currentFocus?.let { adjustSetting(it, -1) }
                KeyEvent.KEYCODE_DPAD_RIGHT -> currentFocus?.let { adjustSetting(it, 1) }
                else -> return super.dispatchKeyEvent(event)
            }
            return true
        }

        if (code == KeyEvent.KEYCODE_SETTINGS) { showSettings(); return true }

        if (list.visibility == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) { hideList(); return true }
            return super.dispatchKeyEvent(event)
        }

        if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) { typeDigit(code - KeyEvent.KEYCODE_0); return true }

        when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> tune(current + 1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> tune(current - 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (channels.isEmpty()) loadChannels() else showOptions()
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_GUIDE -> {
                if (channels.isEmpty()) loadChannels() else showList()
            }
            KeyEvent.KEYCODE_LAST_CHANNEL -> if (previous >= 0) tune(previous, debounce = false)
            // First press shows the banner; pressing again while it is up opens the options menu.
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (osd.visibility == View.VISIBLE && digits.isEmpty()) showOptions()
                else channels.getOrNull(current)?.let(::showOsd)
            }
            KeyEvent.KEYCODE_PROG_RED -> startOver()
            KeyEvent.KEYCODE_PROG_GREEN -> showInfo()
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    private fun typeDigit(d: Int) {
        digits = (digits + d).takeLast(3)
        osd.visibility = View.VISIBLE
        osdNumber.text = digits
        osdName.text = channels.firstOrNull { it.number == digits.toInt() }?.name ?: ""
        bindGuide(null)
        osdJob?.cancel()
        digitJob?.cancel()
        digitJob = lifecycleScope.launch {
            delay(1500)
            val n = digits.toIntOrNull()
            digits = ""
            val idx = channels.indexOfFirst { it.number == n }
            if (idx >= 0) tune(idx, debounce = false) else osd.visibility = View.GONE
        }
    }

    // ---- playback controls ----

    private fun togglePause() = setPaused(player?.playWhenReady == true)

    private fun setPaused(pause: Boolean) {
        val p = player ?: return
        p.playWhenReady = !pause
        setPausedIndicator(pause)
    }

    private fun setPausedIndicator(on: Boolean) {
        paused.visibility = if (on) View.VISIBLE else View.GONE
    }

    /** Seconds the viewer is behind the live edge (paused or started over); 0 when live or unknown. */
    private fun secondsBehindLive(): Long {
        timeshiftBase?.let { return (System.currentTimeMillis() - playedAt()).coerceAtLeast(0) / 1000 }
        val p = player ?: return 0
        val off = p.currentLiveOffset
        if (off == C.TIME_UNSET || off < 0) return 0
        // Even at the live edge the player sits a few seconds back (its target offset); don't count that as "behind".
        val window = Timeline.Window()
        val target = if (p.currentTimeline.isEmpty) 0L
        else p.currentTimeline.getWindow(p.currentMediaItemIndex, window).liveConfiguration?.targetOffsetMs ?: 0L
        return (off - target).coerceAtLeast(0) / 1000
    }

    /**
     * Wall-clock moment currently on screen. For a started-over stream the manifest begins at the programme
     * start, so it is that start plus the playback position; before the first frame the position is stale.
     */
    private fun playedAt(): Long {
        timeshiftBase?.let { base -> return base + if (timeshiftReady) (player?.currentPosition ?: 0L) else 0L }
        return System.currentTimeMillis() - secondsBehindLive() * 1000
    }

    private fun startOver() {
        val ch = channels.getOrNull(current) ?: return
        val prog = epg.now(ch.id, playedAt())
        when {
            prog == null -> notice("No programme information for this channel")
            !prog.startOver -> notice("Start from beginning is not available for this programme")
            else -> {
                tune(current, debounce = false, startOverAt = prog.startMs)
                notice("Restarting: ${prog.title}")
            }
        }
    }

    private fun goLive() {
        if (current >= 0) tune(current, debounce = false)
    }

    /** Short message that clears itself. */
    private fun notice(msg: String) {
        showStatus(msg)
        noticeJob?.cancel()
        noticeJob = lifecycleScope.launch { delay(2500); if (status.text == msg) hideStatus() }
    }

    // ---- guide ----

    /** Fetches now/next for all channels in the background; failures are silent (the guide is optional). */
    private fun refreshEpg() {
        if (epgJob?.isActive == true || channels.isEmpty()) return
        epgJob = lifecycleScope.launch {
            val snapshot = channels
            val data = withContext(Dispatchers.IO) { runCatching { avs.epg(snapshot) }.getOrNull() } ?: return@launch
            epg.replace(data)
            adapter?.notifyDataSetChanged()
            channels.getOrNull(current)?.let { bindGuide(it) }
        }
    }

    // ---- overlays ----

    private fun showList() {
        list.visibility = View.VISIBLE
        if (epg.isStale()) refreshEpg()
        val target = current.coerceAtLeast(0)
        // Height is only known after the first layout, so position in a post.
        list.post {
            (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(target + ChannelAdapter.HEADER, list.height / 3)
            list.post { list.findViewHolderForAdapterPosition(target + ChannelAdapter.HEADER)?.itemView?.requestFocus() }
        }
    }

    private fun hideList() {
        list.visibility = View.GONE
    }

    private fun showOptions() {
        val p = player
        rowPause.text = if (p?.playWhenReady == false) "Play" else "Pause"
        rowLive.visibility = if (secondsBehindLive() >= 15) View.VISIBLE else View.GONE
        osd.visibility = View.GONE
        options.visibility = View.VISIBLE
        rowPause.requestFocus()
    }

    private fun hideOptions() {
        options.visibility = View.GONE
    }

    private fun showInfo() {
        val ch = channels.getOrNull(current) ?: return
        val prog = epg.now(ch.id, playedAt())
        osd.visibility = View.GONE
        infoTitle.text = prog?.title ?: ch.name
        if (prog == null) {
            infoEpisode.text = ""
            infoMeta.text = ""
            infoDesc.text = "No programme information available."
            infoCredits.text = ""
        } else {
            val ep = buildList {
                if (prog.season > 0 && prog.episode > 0) add("S${prog.season} E${prog.episode}")
                else if (prog.episode > 0) add("Episode ${prog.episode}")
                if (prog.episodeTitle.isNotEmpty()) add(prog.episodeTitle)
            }
            infoEpisode.text = ep.joinToString("  ·  ")
            infoEpisode.visibility = if (ep.isEmpty()) View.GONE else View.VISIBLE
            val minutes = (prog.endMs - prog.startMs) / 60_000
            infoMeta.text = listOfNotNull(
                "${ch.name}  ${epg.time(prog.startMs)} – ${epg.time(prog.endMs)} ($minutes min)",
                prog.genres.takeIf { it.isNotEmpty() }?.joinToString(", "),
                prog.year.takeIf { it.isNotEmpty() },
                prog.ageRating.takeIf { it > 0 }?.let { "$it+" },
            ).joinToString("  ·  ")
            infoDesc.text = prog.description.ifEmpty { "No description available." }
            infoCredits.text = listOfNotNull(
                prog.presenters.takeIf { it.isNotEmpty() }?.let { "Presenters: ${it.joinToString(", ")}" },
                prog.directors.takeIf { it.isNotEmpty() }?.let { "Director: ${it.joinToString(", ")}" },
                prog.actors.takeIf { it.isNotEmpty() }?.let { "Cast: ${it.joinToString(", ")}" },
            ).joinToString("\n")
            infoCredits.visibility = if (infoCredits.text.isEmpty()) View.GONE else View.VISIBLE
        }
        info.scrollTo(0, 0)
        info.visibility = View.VISIBLE
        info.requestFocus()
    }

    private fun hideInfo() {
        info.visibility = View.GONE
    }

    private fun showSettings() {
        osd.visibility = View.GONE
        renderSettings()
        settings.visibility = View.VISIBLE
        rowHighBw.requestFocus()
    }

    private fun hideSettings() {
        settings.visibility = View.GONE
    }

    private fun renderSettings() {
        valueHighBw.text = if (highBandwidthStart) "On" else "Off"
        val s = bannerSeconds
        valueBanner.text = "${if (s > MIN_BANNER_S) "◀ " else "   "}$s s${if (s < MAX_BANNER_S) " ▶" else "   "}"
    }

    /** [dir] is -1 (Left), +1 (Right / OK). The banner duration wraps around on OK past the maximum. */
    private fun adjustSetting(row: View, dir: Int) {
        when (row.id) {
            R.id.row_highbw -> {
                highBandwidthStart = !highBandwidthStart
                // The bandwidth meter is fixed at player creation, so rebuild and re-tune.
                releasePlayer()
                buildPlayer()
                if (current >= 0) tune(current, debounce = false)
                osd.visibility = View.GONE
            }
            R.id.row_banner -> {
                val s = bannerSeconds
                bannerSeconds = if (s >= MAX_BANNER_S && dir > 0) MIN_BANNER_S else s + dir
            }
        }
        renderSettings()
    }

    private fun showOsd(ch: Channel) {
        osdNumber.text = ch.number.toString()
        osdName.text = ch.name
        bindGuide(ch)
        bindStream()
        if (epg.isStale()) refreshEpg()
        osd.visibility = View.VISIBLE
        osdJob?.cancel()
        osdJob = lifecycleScope.launch { delay(bannerSeconds * 1000L); osd.visibility = View.GONE }
    }

    /** "HD 1920x1080 · 50 fps · H.264 · 8.0 Mbps   |   NL · AC-3 5.1 · 384 kbps" for what is actually playing. */
    private fun bindStream() {
        val p = player
        osdStream.text = if (p == null) "" else listOfNotNull(videoInfo(p.videoFormat), audioInfo(p.audioFormat))
            .joinToString("   |   ")
    }

    private fun videoInfo(f: Format?): String? {
        if (f == null || f.height <= 0) return null
        val tier = when { f.height >= 2160 -> "UHD"; f.height >= 720 -> "HD"; else -> "SD" }
        val parts = mutableListOf("$tier ${f.width}x${f.height}")
        if (f.frameRate > 0) parts += "${fmtNum(f.frameRate)} fps"
        codecName(f.sampleMimeType)?.let { parts += it }
        if (f.bitrate > 0) parts += "%.1f Mbps".format(f.bitrate / 1_000_000f)
        return parts.joinToString(" · ")
    }

    private fun audioInfo(f: Format?): String? {
        if (f == null) return null
        val parts = mutableListOf<String>()
        f.language?.takeIf { it != "und" }?.let { parts += langName(it) }
        val codec = codecName(f.sampleMimeType)
        val layout = when (f.channelCount) { 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> if (f.channelCount > 0) "${f.channelCount}ch" else null }
        parts += listOfNotNull(codec, layout).joinToString(" ")
        if (f.bitrate > 0) parts += "${f.bitrate / 1000} kbps"
        return parts.filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { null }
    }

    private fun fmtNum(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else "%.2f".format(v)

    private fun langName(code: String) = when (code.lowercase()) {
        "nl", "nld", "dut", "ned" -> "NL"
        "en", "eng" -> "EN"
        else -> code.uppercase()
    }

    private fun codecName(mime: String?) = when (mime) {
        MimeTypes.VIDEO_H264 -> "H.264"
        MimeTypes.VIDEO_H265 -> "HEVC"
        MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_AC3 -> "AC-3"
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3"
        MimeTypes.AUDIO_MPEG_L2 -> "MP2"
        MimeTypes.AUDIO_MPEG -> "MP3"
        else -> null
    }

    /** Fills the program rows of the banner for [ch]; clears them when [ch] is null or has no guide data. */
    private fun bindGuide(ch: Channel?) {
        val at = playedAt()
        val now = ch?.let { epg.now(it.id, at) }
        val next = ch?.let { epg.next(it.id, at) }
        val behindMin = secondsBehindLive() / 60
        osdClock.text = epg.time(System.currentTimeMillis()) + if (behindMin >= 1) "  (-$behindMin min)" else ""
        osdTitle.text = now?.title.orEmpty()
        osdTime.text = now?.let { "${epg.time(it.startMs)} – ${epg.time(it.endMs)}" }.orEmpty()
        osdNext.text = next?.let { "Volgend: ${it.title}" }.orEmpty()
        osdProgress.visibility = if (now == null) View.INVISIBLE else View.VISIBLE
        if (now != null) {
            osdProgress.progress = ((at - now.startMs) * 1000 / (now.endMs - now.startMs)).toInt().coerceIn(0, 1000)
        }
    }

    private fun showStatus(msg: String) {
        status.text = msg
        status.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        status.visibility = View.GONE
    }

    companion object {
        private const val MIN_BANNER_S = 1
        private const val MAX_BANNER_S = 15
        private const val HIGH_START_BPS = 20_000_000L
    }
}
