package nl.kpn.slim

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import java.io.File
import java.util.Locale
import android.widget.EditText
import android.widget.ImageView
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
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single screen: full-screen live player, with a channel list overlay.
 *
 * Remote: Up/Down or CH+/CH- zap, digits jump to a channel number, OK/Menu/Left opens the list,
 * Back closes the open overlay, or (pressed twice) exits. Info shows the channel banner.
 *
 * Plain Activity (not AppCompat) to keep the APK small; we provide our own LifecycleOwner
 * for coroutine scoping.
 */
class MainActivity : Activity(), LifecycleOwner {

    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    /** Created once this install has an [Identity] (stored from an earlier sign-in, or the sign-in just done). */
    private lateinit var avs: AvsClient
    private lateinit var logos: LogoLoader
    private val epg = Epg()
    private var epgJob: Job? = null

    private lateinit var playerView: PlayerView
    private lateinit var list: RecyclerView
    private lateinit var guide: GuideView
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
    private lateinit var paused: View // pause icon + time behind live
    private lateinit var pausedBehind: TextView
    private lateinit var loader: View
    private lateinit var options: View
    private lateinit var rowPause: TextView
    private lateinit var rowStartOver: View
    private lateinit var rowLive: View
    private lateinit var info: View
    private lateinit var infoScroll: View
    private lateinit var infoStartOver: TextView
    private lateinit var osdLogo: ImageView
    private lateinit var infoImage: ImageView
    private lateinit var infoTitle: TextView
    private lateinit var infoEpisode: TextView
    private lateinit var infoMeta: TextView
    private lateinit var infoDesc: TextView
    private lateinit var infoCredits: TextView
    private lateinit var settings: View
    private lateinit var rowHighBw: View
    private lateinit var rowBanner: View
    private lateinit var rowLogos: View
    private lateinit var valueLogos: TextView
    private lateinit var valueHighBw: TextView
    private lateinit var valueBanner: TextView
    private lateinit var tracksPanel: View
    private lateinit var stats: TextView
    private var statsJob: Job? = null
    private var bandwidthMeter: DefaultBandwidthMeter? = null
    private var decoderName = "-"
    private var formatSwitches = ArrayList<Long>() // wall-clock times of quality switches in this stream
    private var firstFormatSeen = false

    // Zap timing for the stats overlay, in ms after the key press (uptime [zapAt]); -1 while not reached.
    private var zapAt = 0L
    private var lastZapAt = 0L
    private var zapUrlMs = -1L
    private var zapKeysMs = -1L
    private var zapFrameMs = -1L
    private var zapDecoderMs = -1L // how long opening the video decoder took; -1 when the previous one was kept
    private lateinit var rowAudio: View
    private lateinit var rowSubs: View
    private lateinit var valueAudio: TextView
    private lateinit var valueSubs: TextView

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
    private var loaderJob: Job? = null
    private var pausedJob: Job? = null
    private var lastBackAt = 0L
    private var guardKey: String? = null
    private var guardAt = 0L
    private var keyRepeating = false
    private lateinit var hintView: View
    private lateinit var hintText: TextView
    private var hintJob: Job? = null
    private var lastSeekAt = 0L
    private var seekStep = 0
    private var seekTotalMs = 0L

    /** One selectable audio or subtitle track; [group] is null for "Off". */
    private class TrackOption(val label: String, val key: String, val group: Tracks.Group?, val index: Int, val selected: Boolean)

    // Stream info glow: last shown pieces (key to text), the pieces currently glowing, and the running fade.
    private var streamVideo: List<Pair<String, String>> = emptyList()
    private var lastParts: Map<String, String> = emptyMap()
    private var glowKeys: Set<String> = emptySet()
    private var streamAnim: ValueAnimator? = null

    /**
     * Wall-clock moment of playback position 0 when not watching live (a started-over programme, or catch-up);
     * null when live. [timeshiftStartPos] is where playback begins in that stream (catch-up starts a little in,
     * after the recording's lead-in).
     */
    private var timeshiftBase: Long? = null
    private var timeshiftStartPos = 0L
    private var timeshiftReady = false

    /** What is playing, so a retry after an error (expired token) keeps watching the same thing. */
    private var curCatchUp: Program? = null

    /** The programme the info panel shows (it can belong to any channel, when opened from the TV guide). */
    private var infoChIdx = -1
    private var infoProg: Program? = null

    private val prefs by lazy { getSharedPreferences("slim", MODE_PRIVATE) }

    private lateinit var login: View
    private lateinit var loginUser: EditText
    private lateinit var loginPass: EditText
    private lateinit var loginError: TextView
    private lateinit var loginButton: TextView
    private var loginBusy = false

    /** Start playback at the top quality instead of ramping up from the lowest (like the official app's flag). */
    private var highBandwidthStart: Boolean
        get() = prefs.getBoolean("highBandwidthStart", false)
        set(v) = prefs.edit().putBoolean("highBandwidthStart", v).apply()

    /** Channel logos in the channel list and the banner. */
    private var showLogos: Boolean
        get() = prefs.getBoolean("showLogos", true)
        set(v) = prefs.edit().putBoolean("showLogos", v).apply()

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
        guide = findViewById(R.id.guide)
        guide.listener = guideListener
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
        pausedBehind = findViewById(R.id.paused_behind)
        loader = findViewById(R.id.loader)
        options = findViewById(R.id.options)
        rowPause = findViewById(R.id.row_pause)
        rowStartOver = findViewById(R.id.row_startover)
        rowLive = findViewById(R.id.row_live)
        info = findViewById(R.id.info)
        infoScroll = findViewById(R.id.info_scroll)
        infoStartOver = findViewById(R.id.info_startover)
        infoStartOver.setOnClickListener { startOver(infoChIdx, infoProg) }
        osdLogo = findViewById(R.id.osd_logo)
        infoImage = findViewById(R.id.info_image)
        infoTitle = findViewById(R.id.info_title)
        infoEpisode = findViewById(R.id.info_episode)
        infoMeta = findViewById(R.id.info_meta)
        infoDesc = findViewById(R.id.info_desc)
        infoCredits = findViewById(R.id.info_credits)
        rowPause.setOnClickListener { hideOptions(); togglePause() }
        rowStartOver.setOnClickListener { startOver() }
        rowLive.setOnClickListener { guard("live", "Not live. Press OK again to return to live TV") { hideOptions(); goLive() } }
        findViewById<View>(R.id.row_info).setOnClickListener { hideOptions(); showInfo() }
        findViewById<View>(R.id.row_guide).setOnClickListener { hideOptions(); showGuide() }
        findViewById<View>(R.id.row_channels).setOnClickListener { hideOptions(); showList() }
        findViewById<View>(R.id.row_settings).setOnClickListener { hideOptions(); showSettings() }
        settings = findViewById(R.id.settings)
        rowHighBw = findViewById(R.id.row_highbw)
        rowBanner = findViewById(R.id.row_banner)
        rowLogos = findViewById(R.id.row_logos)
        valueLogos = findViewById(R.id.value_logos)
        rowLogos.setOnClickListener { adjustSetting(rowLogos, 1) }
        valueHighBw = findViewById(R.id.value_highbw)
        valueBanner = findViewById(R.id.value_banner)
        rowHighBw.setOnClickListener { adjustSetting(rowHighBw, 1) }
        rowBanner.setOnClickListener { adjustSetting(rowBanner, 1) }
        tracksPanel = findViewById(R.id.tracks)
        stats = findViewById(R.id.stats)
        rowAudio = findViewById(R.id.row_audio)
        rowSubs = findViewById(R.id.row_subs)
        valueAudio = findViewById(R.id.value_audio)
        valueSubs = findViewById(R.id.value_subs)
        rowAudio.setOnClickListener { adjustTrack(rowAudio, 1) }
        rowSubs.setOnClickListener { adjustTrack(rowSubs, 1) }
        list.layoutManager = LinearLayoutManager(this)
        logos = LogoLoader(lifecycleScope)

        hintView = findViewById(R.id.hint)
        hintText = findViewById(R.id.hint_text)
        login = findViewById(R.id.login)
        loginUser = findViewById(R.id.login_user)
        loginPass = findViewById(R.id.login_pass)
        loginError = findViewById(R.id.login_error)
        loginButton = findViewById(R.id.login_button)
        loginButton.setOnClickListener { submitLogin() }
        loginPass.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { submitLogin(); true } else false
        }

        val id = IdentityStore.load(prefs)
        if (id == null) showLogin() else { avs = AvsClient(id.account, id.deviceId, prefs); loadChannels() }
    }

    override fun onStart() {
        super.onStart()
        registry.currentState = Lifecycle.State.STARTED
        buildPlayer()
        if (current >= 0) tune(current, debounce = false)
    }

    private fun buildPlayer() {
        val builder = ExoPlayer.Builder(this)
        // A generous initial estimate makes adaptive selection pick the top rendition (8 Mbps) right away.
        // Otherwise the shared default meter, which is what ExoPlayer would use anyway (and remembers its estimate).
        val meter = if (highBandwidthStart) DefaultBandwidthMeter.Builder(this).setInitialBitrateEstimate(HIGH_START_BPS).build()
        else DefaultBandwidthMeter.getSingletonInstance(this)
        bandwidthMeter = meter
        builder.setBandwidthMeter(meter)
        player = builder.build().also { p ->
            // Keeps the video decoder open between channels (stop() would otherwise release it on every zap).
            p.setForegroundMode(true)
            p.playWhenReady = true
            p.addListener(playerListener)
            p.addAnalyticsListener(statsListener)
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

    private val channelFile by lazy { File(filesDir, "channels.json") }

    /**
     * Starts from the channel list of the last run when there is one (the picture then only waits for the play
     * URL), and fetches the current list a little later, out of the way of that first tune.
     */
    private fun loadChannels() {
        lifecycleScope.launch {
            val cached = if (channels.isEmpty()) withContext(Dispatchers.IO) { ChannelStore.load(channelFile) } else emptyList()
            if (cached.isNotEmpty()) {
                applyChannels(cached)
                delay(CHANNEL_REFRESH_DELAY_MS)
            } else if (channels.isEmpty()) {
                showStatus("Loading channels…")
            }
            val result = withContext(Dispatchers.IO) { runCatching { avs.liveChannels() } }
            result.onSuccess { list ->
                if (list == channels) { if (list.isEmpty()) hideStatus(); return@onSuccess }
                withContext(Dispatchers.IO) { ChannelStore.save(channelFile, list) }
                applyChannels(list)
            }.onFailure {
                if (it is AuthException) showLogin(AUTH_REFUSED)
                else if (channels.isEmpty()) showStatus("Could not load channels:\n${it.message}\n\nPress OK to retry")
            }
        }
    }

    /**
     * Puts [list] in use. The first list tunes to the last watched channel. A later one (the lineup changed since
     * the last run) keeps what is playing when that channel is still there unchanged.
     */
    private fun applyChannels(list: List<Channel>) {
        val first = channels.isEmpty()
        val playing = channels.getOrNull(current)
        val previousId = channels.getOrNull(previous)?.id
        // Rows and indices are about to change under the open overlays.
        hideGuide()
        hideList()
        channels = list
        adapter = ChannelAdapter(list, logos, epg, showLogos = { showLogos }, onSettings = { hideList(); showSettings() }) { i -> guard("ch:$i", "Not live. Press OK again to change channel") { hideList(); tune(i, debounce = false) } }
        this.list.adapter = adapter
        if (first) {
            hideStatus()
            val last = prefs.getInt("lastChannelId", -1)
            if (list.isNotEmpty()) tune(list.indexOfFirst { it.id == last }.coerceAtLeast(0), debounce = false)
            refreshEpg()
            return
        }
        previous = list.indexOfFirst { it.id == previousId }
        val idx = list.indexOfFirst { it.id == playing?.id }
        if (idx >= 0 && list[idx] == playing) {
            current = idx
            adapter?.playingIndex = idx
            guide.playingIndex = idx
        } else if (list.isNotEmpty()) {
            current = -1
            tune(idx.coerceAtLeast(0), debounce = false)
        }
    }

    // ---- tuning ----

    /**
     * Plays channel [index]: live by default, from a programme start with [startOverAt] (the stream's window is
     * about 6 h deep), or a finished programme from the recording with [catchUp]. [resumePos] starts a catch-up
     * recording at that position instead of its beginning, [startPaused] keeps the player paused once it is ready
     * (both are for picking up where an expired play URL interrupted the viewer).
     */
    private fun tune(
        index: Int, debounce: Boolean = true, startOverAt: Long? = null, catchUp: Program? = null,
        resumePos: Long? = null, startPaused: Boolean = false,
    ) {
        if (channels.isEmpty()) return
        val i = Math.floorMod(index, channels.size)
        if (i != current) { previous = current; retriedCurrent = false }
        current = i
        curCatchUp = catchUp
        timeshiftBase = startOverAt ?: catchUp?.let { it.startMs - CATCHUP_LEAD_MS }
        timeshiftStartPos = resumePos ?: if (catchUp != null) CATCHUP_LEAD_MS else 0L
        timeshiftReady = false
        adapter?.playingIndex = i
        guide.playingIndex = i
        val ch = channels[i]
        prefs.edit().putInt("lastChannelId", ch.id).apply()
        showOsd(ch)
        hideLoader()
        armLoader()

        // A single zap tunes at once. Only presses that follow each other quickly (stepping through channels, a
        // held key) wait for the run to settle before hitting the API.
        zapAt = SystemClock.uptimeMillis()
        val settle = debounce && zapAt - lastZapAt < ZAP_RUN_MS
        lastZapAt = zapAt
        zapUrlMs = -1
        zapKeysMs = -1
        zapFrameMs = -1
        zapDecoderMs = -1

        tuneJob?.cancel()
        tuneJob = lifecycleScope.launch {
            if (settle) delay(ZAP_SETTLE_MS)
            val p = player ?: return@launch
            p.stop()
            p.playWhenReady = true
            setPausedIndicator(false)
            val stream = withContext(Dispatchers.IO) {
                runCatching { if (catchUp != null) avs.catchUpStream(catchUp) else avs.liveStream(ch, startOverAt) { isActive } }
            }
            stream.onSuccess { s ->
                zapUrlMs = SystemClock.uptimeMillis() - zapAt
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
                // Catch-up: the recording starts CATCHUP_LEAD_MS before the programme.
                // A different stream starts here: its first values must not glow against the previous stream's.
                lastParts = emptyMap()
                glowKeys = emptySet()
                formatSwitches.clear()
                firstFormatSeen = false
                streamAnim?.cancel()
                if (timeshiftBase != null) p.setMediaItem(item, timeshiftStartPos) else p.setMediaItem(item)
                p.playWhenReady = !startPaused
                setPausedIndicator(startPaused)
                p.prepare()
            }.onFailure {
                hideLoader()
                if (it is AuthException) showLogin(AUTH_REFUSED) else showStatus("${ch.name}: ${it.message}")
            }
        }
    }

    /** Feeds the stats overlay: which decoder is in use and how often the quality switched in this stream. */
    private val statsListener = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long,
        ) {
            this@MainActivity.decoderName = decoderName
            zapDecoderMs = initializationDurationMs
        }

        override fun onDrmKeysLoaded(eventTime: AnalyticsListener.EventTime) {
            if (zapKeysMs < 0) zapKeysMs = SystemClock.uptimeMillis() - zapAt
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            // The first format of a stream is the start, not a switch.
            if (firstFormatSeen) formatSwitches.add(System.currentTimeMillis()) else firstFormatSeen = true
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
            // Manifest tokens / licenses expire: fetch a fresh play URL once before giving up, and pick up where the
            // viewer was (a long pause) instead of at the live edge or the start of the programme.
            if (!retriedCurrent && current >= 0) {
                retriedCurrent = true
                // Retry with a fresh decoder, in case the kept one is what failed.
                p.setForegroundMode(false)
                p.setForegroundMode(true)
                val wasPaused = !p.playWhenReady
                val catchUp = curCatchUp
                val behind = wasPaused || secondsBehindLive() >= 3
                when {
                    catchUp != null ->
                        tune(current, debounce = false, catchUp = catchUp, resumePos = p.currentPosition.coerceAtLeast(0L), startPaused = wasPaused)
                    timeshiftBase != null || behind ->
                        // The start-over stream can begin at any moment within its reach, which is what a paused
                        // live stream (or a started-over one) needs to continue from the same picture.
                        tune(current, debounce = false, startOverAt = playedAt(), startPaused = wasPaused)
                    else -> tune(current, debounce = false)
                }
            } else {
                hideLoader()
                showStatus("Playback error: ${error.errorCodeName}")
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) = bindStream()
        override fun onRenderedFirstFrame() {
            if (zapFrameMs < 0) zapFrameMs = SystemClock.uptimeMillis() - zapAt
        }
        override fun onTracksChanged(tracks: Tracks) {
            bindStream()
            applySavedTracks()
            if (tracksPanel.visibility == View.VISIBLE) renderTracks()
        }

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> { retriedCurrent = false; timeshiftReady = true; hideStatus(); hideLoader() }
                Player.STATE_BUFFERING -> armLoader()
                Player.STATE_ENDED -> catchUpEnded()
                else -> Unit
            }
        }
    }

    // ---- remote control ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val code = event.keyCode
        keyRepeating = event.repeatCount > 0

        // While signing in every key belongs to the form (space and letters must type, not pause or zap).
        if (login.visibility == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK) { confirmExit(); return true }
            return super.dispatchKeyEvent(event)
        }

        // The TV guide takes the navigation keys, fast forward and rewind (they page through time there).
        // Play/pause and stop keep working; the programme info panel on top of it takes its own keys below.
        if (guide.visibility == View.VISIBLE && info.visibility != View.VISIBLE && code !in GUIDE_PASS_KEYS) {
            return guide.onKey(code) || super.dispatchKeyEvent(event)
        }

        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> { togglePause(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { setPaused(true); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { setPaused(false); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, KeyEvent.KEYCODE_MEDIA_STEP_FORWARD -> { seekBy(+1); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD, KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> { seekBy(-1); return true }
            KeyEvent.KEYCODE_MEDIA_STOP -> { stopKey(); return true }
        }

        if (info.visibility == View.VISIBLE) {
            when (code) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_PROG_GREEN -> hideInfo()
                KeyEvent.KEYCODE_PROG_RED -> startOver(infoChIdx, infoProg)
                else -> return super.dispatchKeyEvent(event) // up/down scroll the text, OK presses the button
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

        if (tracksPanel.visibility == View.VISIBLE) {
            when (code) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_PROG_YELLOW -> hideTracks()
                KeyEvent.KEYCODE_DPAD_LEFT -> currentFocus?.let { adjustTrack(it, -1) }
                KeyEvent.KEYCODE_DPAD_RIGHT -> currentFocus?.let { adjustTrack(it, 1) }
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

        if (code == KeyEvent.KEYCODE_BACK) { confirmExit(); return true }

        if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) { typeDigit(code - KeyEvent.KEYCODE_0); return true }

        when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP ->
                guard("zap:+1", "Not live. Press again to change channel") { tune(current + 1) }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN ->
                guard("zap:-1", "Not live. Press again to change channel") { tune(current - 1) }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (channels.isEmpty()) loadChannels() else showOptions()
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (channels.isEmpty()) loadChannels() else showList()
            }
            KeyEvent.KEYCODE_GUIDE -> {
                if (channels.isEmpty()) loadChannels() else showGuide()
            }
            KeyEvent.KEYCODE_LAST_CHANNEL -> if (previous >= 0) {
                guard("last", "Not live. Press again to change channel") { tune(previous, debounce = false) }
            }
            // First press shows the banner; pressing again while it is up opens the options menu.
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (osd.visibility == View.VISIBLE && digits.isEmpty()) showOptions()
                else channels.getOrNull(current)?.let(::showOsd)
            }
            KeyEvent.KEYCODE_PROG_RED -> startOver()
            KeyEvent.KEYCODE_PROG_GREEN -> showInfo()
            KeyEvent.KEYCODE_PROG_YELLOW -> showTracks()
            KeyEvent.KEYCODE_PROG_BLUE -> toggleStats()
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
            if (idx < 0) { osd.visibility = View.GONE; return@launch }
            val ran = guard("ch:$idx", "Not live. Enter $n again to change channel", fromKey = false) { tune(idx, debounce = false) }
            if (!ran) channels.getOrNull(current)?.let(::showOsd) // back to the banner of what is still playing
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
        pausedJob?.cancel()
        paused.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) return
        // While paused the live edge keeps moving: keep the "behind live" time current.
        pausedJob = lifecycleScope.launch {
            while (true) {
                pausedBehind.text = "-" + clockLength(secondsBehindLive())
                delay(500)
            }
        }
    }

    /** 75 -> "1:15", 3725 -> "1:02:05". */
    private fun clockLength(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = totalSeconds / 60 % 60
        val s = totalSeconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    // ---- buffering spinner: only after a short delay, so quick zaps stay clean ----

    private fun armLoader() {
        if (loaderJob?.isActive == true || loader.visibility == View.VISIBLE) return
        loaderJob = lifecycleScope.launch { delay(LOADER_DELAY_MS); loader.visibility = View.VISIBLE }
    }

    private fun hideLoader() {
        loaderJob?.cancel()
        loader.visibility = View.GONE
    }

    // ---- seeking, stop, exit ----

    /**
     * Fast forward / rewind. Repeated presses within [SEEK_REPEAT_MS] step up through [SEEK_STEPS_S];
     * going forward past the live edge lands on live.
     */
    private fun seekBy(dir: Int) {
        val p = player ?: return
        val now = System.currentTimeMillis()
        val burst = now - lastSeekAt < SEEK_REPEAT_MS
        seekStep = if (burst) (seekStep + 1).coerceAtMost(SEEK_STEPS_S.lastIndex) else 0
        if (!burst) seekTotalMs = 0L
        lastSeekAt = now
        val before = p.currentPosition
        val target = before + dir * SEEK_STEPS_S[seekStep] * 1000L
        val window = Timeline.Window()
        val liveEdge = if (p.currentTimeline.isEmpty) C.TIME_UNSET
        else p.currentTimeline.getWindow(p.currentMediaItemIndex, window).defaultPositionMs
        if (dir > 0 && p.isCurrentMediaItemLive && liveEdge != C.TIME_UNSET && target >= liveEdge) {
            p.seekToDefaultPosition()
            seekTotalMs = 0L
            hint("▶▶  Live", SEEK_HINT_MS)
        } else {
            val to = target.coerceAtLeast(0L)
            p.seekTo(to)
            // What was actually skipped, summed over a quick run of presses (a rewind stops at the start).
            seekTotalMs += to - before
            hint(skipText(seekTotalMs), SEEK_HINT_MS)
        }
        channels.getOrNull(current)?.let(::showOsd)
    }

    /** 70 s forward -> "▶▶  +1 min 10 s", 30 s back -> "◀◀  -30 s". */
    private fun skipText(ms: Long): String {
        val s = Math.abs(ms) / 1000
        val h = s / 3600
        val m = s / 60 % 60
        val sec = s % 60
        val amount = when {
            h > 0 -> if (m > 0) "$h h $m min" else "$h h"
            m > 0 -> if (sec > 0) "$m min $sec s" else "$m min"
            else -> "$sec s"
        }
        return if (ms >= 0) "▶▶  +$amount" else "◀◀  -$amount"
    }

    /** Stop: back to live. Guarded like every way out of non-live playback; when already live it only says so. */
    private fun stopKey() {
        if (!nonLive()) { hint("You are watching live TV"); return }
        guard("live", "Press stop again to return to live TV") { goLive() }
    }

    /** True when leaving the current stream would lose the viewer's place: paused, behind live, or started over. */
    private fun nonLive() = secondsBehindLive() >= 3 || timeshiftBase != null || player?.playWhenReady == false

    /**
     * Runs [action] at once while watching live. When not live, the first press only shows [msg]; the same
     * action again within [GUARD_MS] runs it. Key auto-repeat never confirms. Returns whether [action] ran.
     */
    private fun guard(key: String, msg: String, fromKey: Boolean = true, action: () -> Unit): Boolean {
        if (!nonLive()) { guardKey = null; action(); return true }
        if (fromKey && keyRepeating) return false
        val now = System.currentTimeMillis()
        if (guardKey == key && now - guardAt < GUARD_MS) {
            guardKey = null
            hint(null)
            action()
            return true
        }
        guardKey = key
        guardAt = now
        hint(msg, GUARD_MS)
        return false
    }

    /** First press shows a hint, a second one within [EXIT_WINDOW_MS] leaves the app. */
    private fun confirmExit() {
        val now = System.currentTimeMillis()
        if (now - lastBackAt < EXIT_WINDOW_MS) {
            hint(null)
            finish()
            return
        }
        lastBackAt = now
        hint("Press back again to exit")
    }

    /**
     * One short message at a time, in a small pill with the app icon; null just dismisses it.
     * Drawn by the app itself: the system toast takes its icon from SystemUI, which on some TVs keeps showing
     * the default Android icon after the app was reinstalled.
     */
    private fun hint(msg: String?, ms: Long = EXIT_WINDOW_MS) {
        hintJob?.cancel()
        if (msg == null) { hintView.visibility = View.GONE; return }
        hintText.text = msg
        hintView.visibility = View.VISIBLE
        hintJob = lifecycleScope.launch { delay(ms); hintView.visibility = View.GONE }
    }

    /** Seconds the viewer is behind the live edge (paused or started over); 0 when live or unknown. */
    private fun secondsBehindLive(): Long {
        timeshiftBase?.let { return (System.currentTimeMillis() - playedAt()).coerceAtLeast(0) / 1000 }
        // A new stream that has not started yet is not "behind" anything (the old stream's offset is stale).
        if (!timeshiftReady) return 0
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
        timeshiftBase?.let { base -> return base + if (timeshiftReady) (player?.currentPosition ?: 0L) else timeshiftStartPos }
        return System.currentTimeMillis() - secondsBehindLive() * 1000
    }

    /**
     * Start a programme from its beginning: [prog] on channel [idx], by default what is on screen right now.
     * See [watchProgramme] for how that is played.
     */
    private fun startOver(idx: Int = current, prog: Program? = epg.now(channels.getOrNull(idx)?.id ?: -1, playedAt())) {
        if (prog == null) { dismissPanels(); notice("No programme information for this channel"); return }
        val now = System.currentTimeMillis()
        if (prog.startMs > now) { dismissPanels(); notice("This programme has not started yet"); return }
        if (!canWatchFromStart(prog)) {
            dismissPanels()
            notice(if (prog.endMs > now) "Start from beginning is not available for this programme"
            else "Catch-up is not available for this programme")
            return
        }
        guard("watch:${prog.id}", "Not live. Press again to restart the programme") {
            dismissPanels()
            watchProgramme(idx, prog)
            hideGuide() // after: trimming keeps what is being played, and that is only known once tune() ran
        }
    }

    /** Whether [prog] has started and can be played from its beginning, as start-over or as catch-up. */
    private fun canWatchFromStart(prog: Program): Boolean {
        val now = System.currentTimeMillis()
        return prog.startMs <= now && (startOverReachable(prog) || (prog.endMs <= now && prog.catchUp))
    }

    /** The live-style start-over stream only reaches about 6 h back; the margin keeps clear of that edge. */
    private fun startOverReachable(prog: Program) =
        prog.startOver && prog.startMs >= System.currentTimeMillis() - START_OVER_REACH_MS

    /**
     * Plays [prog] from its start. Recent programmes use the start-over stream (exact start, carries on into what
     * followed); older ones the catch-up recording. The caller has checked [canWatchFromStart].
     */
    private fun watchProgramme(idx: Int, prog: Program) {
        if (startOverReachable(prog)) {
            tune(idx, debounce = false, startOverAt = prog.startMs)
            notice(if (prog.endMs > System.currentTimeMillis()) "Restarting: ${prog.title}" else "Replay: ${prog.title}")
        } else {
            tune(idx, debounce = false, catchUp = prog)
            notice("Catch-up: ${prog.title}")
        }
    }

    /** A catch-up recording played to its end: carry on with the next programme if it is also catch-up, else live. */
    private fun catchUpEnded() {
        val shown = curCatchUp ?: return
        val ch = channels.getOrNull(current) ?: return
        val next = epg.next(ch.id, shown.startMs)
        if (next != null && next.endMs <= System.currentTimeMillis() && next.catchUp) {
            watchProgramme(current, next)
        } else {
            tune(current, debounce = false)
            notice("End of catch-up, back to live")
        }
    }

    private fun dismissPanels() { hideOptions(); hideInfo() }

    private fun goLive() {
        val p = player
        if (p != null && timeshiftBase == null && p.isCurrentMediaItemLive && p.playbackState == Player.STATE_READY) {
            // Same stream, just jump to its live edge (no new play URL needed).
            p.seekToDefaultPosition()
            setPaused(false)
            channels.getOrNull(current)?.let(::showOsd)
        } else if (current >= 0) {
            tune(current, debounce = false)
        }
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
            // In parts, so a zap during the refresh waits for one small request instead of the whole lineup.
            val data = withContext(Dispatchers.IO) {
                runCatching {
                    val all = HashMap<Int, List<Program>>()
                    for (part in snapshot.map { it.id }.chunked(EPG_REFRESH_CHUNK)) all += avs.epg(part)
                    all
                }.getOrNull()
            } ?: return@launch
            epg.replace(data)
            if (guide.visibility != View.VISIBLE) trimEpg()
            adapter?.notifyDataSetChanged()
            channels.getOrNull(current)?.let { bindGuide(it) }
        }
    }

    /**
     * Drops guide data nobody needs: older than 6 h (what start-over can reach), further ahead than 12 h, but
     * always keeping what the player is showing, since a catch-up programme is days old.
     */
    private fun trimEpg() {
        val now = System.currentTimeMillis()
        epg.trim(minOf(now - 6 * 3_600_000, playedAt() - 3_600_000), now + 12 * 3_600_000)
    }

    // ---- TV guide ----

    private val guideListener = object : GuideView.Listener {
        override fun onWatch(row: Int, program: Program?) {
            val now = System.currentTimeMillis()
            when {
                // Nothing known, or on now: just tune to the channel.
                program == null || (program.startMs <= now && program.endMs > now) ->
                    guard("ch:$row", "Not live. Press OK again to change channel") { tune(row, debounce = false); hideGuide() }
                program.startMs > now -> showInfo(row, program)
                canWatchFromStart(program) ->
                    guard("watch:${program.id}", "Not live. Press OK again to watch this programme") {
                        watchProgramme(row, program); hideGuide()
                    }
                else -> notice("Not available: this programme can no longer be watched")
            }
        }

        override fun onInfo(row: Int, program: Program) = showInfo(row, program)
        override fun onClose() = hideGuide()
        override fun onMoved() = ensureGuideData()
    }

    private fun showGuide() {
        osd.visibility = View.GONE
        guide.open(channels, epg, current.coerceAtLeast(0), current)
        guide.visibility = View.VISIBLE
        ensureGuideData()
    }

    private fun hideGuide() {
        if (guide.visibility != View.VISIBLE) return
        guide.visibility = View.GONE
        trimEpg()
    }

    /** Loads the programmes around the visible part of the guide that are not in memory yet. */
    private fun ensureGuideData() {
        if (guide.visibility != View.VISIBLE || channels.isEmpty()) return
        val first = (guide.firstVisibleRow - GUIDE_ROW_MARGIN).coerceAtLeast(0)
        val last = (guide.firstVisibleRow + guide.visibleRows + GUIDE_ROW_MARGIN).coerceAtMost(channels.size - 1)
        val ids = (first..last).map { channels[it].id }
        val missing = epg.claimMissing(ids, guide.windowStart - Epg.SLOT_MS, guide.windowEnd + Epg.SLOT_MS)
        if (missing.isEmpty()) return
        // Nearest to what is on screen first.
        val centre = Math.floorDiv(guide.windowStart + (guide.windowEnd - guide.windowStart) / 2, Epg.SLOT_MS)
        val slots = missing.keys.sortedBy { Math.abs(it - centre) }
        lifecycleScope.launch {
            for (slot in slots) {
                val chIds = missing.getValue(slot)
                val from = slot * Epg.SLOT_MS
                val data = withContext(Dispatchers.IO) { runCatching { avs.epg(chIds, from, from + Epg.SLOT_MS) }.getOrNull() }
                if (data == null) { epg.release(slot, chIds); continue }
                epg.merge(data)
                guide.dataChanged()
            }
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

    /** Programme info for [prog] on channel [idx]; by default what is on screen right now. */
    private fun showInfo(idx: Int = current, prog: Program? = epg.now(channels.getOrNull(idx)?.id ?: -1, playedAt())) {
        val ch = channels.getOrNull(idx) ?: return
        infoChIdx = idx
        infoProg = prog
        osd.visibility = View.GONE
        logos.load(prog?.imageUrl(INFO_IMAGE_W, INFO_IMAGE_H), infoImage)
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
        infoScroll.scrollTo(0, 0)
        val canStart = prog != null && canWatchFromStart(prog)
        infoStartOver.text = if (prog != null && prog.endMs <= System.currentTimeMillis()) "Watch from beginning" else "Start from beginning"
        infoStartOver.visibility = if (canStart) View.VISIBLE else View.GONE
        info.visibility = View.VISIBLE
        // The button is the default focus when offered; Up moves to the text, which then scrolls.
        (if (canStart) infoStartOver else infoScroll).requestFocus()
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
        valueLogos.text = if (showLogos) "On" else "Off"
        val s = bannerSeconds
        valueBanner.text = "${if (s > MIN_BANNER_S) "◀ " else "   "}$s s${if (s < MAX_BANNER_S) " ▶" else "   "}"
    }

    /** [dir] is -1 (Left), +1 (Right / OK). The banner duration wraps around on OK past the maximum. */
    private fun adjustSetting(row: View, dir: Int) {
        when (row.id) {
            R.id.row_highbw -> guard("hibw", "Not live. Press again to apply; the stream restarts") {
                highBandwidthStart = !highBandwidthStart
                // The bandwidth meter is fixed at player creation, so rebuild and re-tune.
                releasePlayer()
                buildPlayer()
                if (current >= 0) tune(current, debounce = false)
                osd.visibility = View.GONE
            }
            R.id.row_logos -> {
                showLogos = !showLogos
                adapter?.notifyDataSetChanged()
                channels.getOrNull(current)?.let { bindGuide(it) }
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

    /**
     * "HD 1920x1080 · 50 fps · H.264 · 8.0 Mbps" for the video that is actually playing.
     * Each piece has a key; when a piece changes while the same stream keeps playing (adaptive quality switch),
     * it glows green and fades back to the dim base colour.
     */
    private fun bindStream() {
        val p = player
        val video = if (p == null) emptyList() else videoParts(p.videoFormat)
        streamVideo = video
        val all = video
        if (all.isEmpty()) { renderStream(0f); return }

        val changed = all.filter { (key, text) -> lastParts[key]?.let { it != text } == true }.map { it.first }
        lastParts = all.toMap()
        if (changed.isNotEmpty()) {
            glowKeys = changed.toSet()
            streamAnim?.cancel()
            streamAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = GLOW_MS
                addUpdateListener { a ->
                    val t = a.animatedFraction
                    // Hold full glow for the first third, then fade out.
                    renderStream(if (t < 0.33f) 1f else 1f - (t - 0.33f) / 0.67f)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { glowKeys = emptySet(); renderStream(0f) }
                })
                start()
            }
        } else if (streamAnim?.isRunning != true) {
            renderStream(0f)
        }
    }

    private fun renderStream(glow: Float) {
        val base = getColor(R.color.stream_dim)
        val glowColor = getColor(R.color.stream_glow)
        val sb = SpannableStringBuilder()
        fun group(parts: List<Pair<String, String>>) {
            parts.forEachIndexed { i, (key, text) ->
                if (i > 0) sb.append(" · ")
                val start = sb.length
                sb.append(text)
                if (key in glowKeys && glow > 0f) {
                    sb.setSpan(ForegroundColorSpan(ArgbEvaluator().evaluate(glow, base, glowColor) as Int),
                        start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        group(streamVideo)
        val appearing = osdStream.text.isEmpty() && sb.isNotEmpty()
        osdStream.text = sb
        if (appearing) {
            // First info for a new stream: fade in instead of popping in.
            osdStream.animate().cancel()
            osdStream.alpha = 0f
            osdStream.animate().alpha(1f).setDuration(STREAM_FADE_MS).start()
        } else if (sb.isEmpty()) {
            osdStream.animate().cancel()
            osdStream.alpha = 1f
        }
    }

    private fun videoParts(f: Format?): List<Pair<String, String>> {
        if (f == null || f.height <= 0) return emptyList()
        val tier = when { f.height >= 2160 -> "UHD"; f.height >= 720 -> "HD"; else -> "SD" }
        return buildList {
            add("res" to "$tier ${f.width}x${f.height}")
            if (f.frameRate > 0) add("fps" to "${fmtNum(f.frameRate)} fps")
            codecName(f.sampleMimeType)?.let { add("vcodec" to it) }
            if (f.bitrate > 0) add("vbitrate" to "%.1f Mbps".format(f.bitrate / 1_000_000f))
        }
    }

    private fun fmtNum(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else "%.2f".format(v)

    private fun codecName(mime: String?) = when (mime) {
        MimeTypes.VIDEO_H264 -> "H.264"
        MimeTypes.VIDEO_H265 -> "HEVC"
        MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
        else -> null
    }

    /** Fills the program rows of the banner for [ch]; clears them when [ch] is null or has no guide data. */
    private fun bindGuide(ch: Channel?) {
        val at = playedAt()
        val now = ch?.let { epg.now(it.id, at) }
        val next = ch?.let { epg.next(it.id, at) }
        val behindSec = secondsBehindLive()
        osdClock.text = epg.time(System.currentTimeMillis()) + when {
            behindSec >= 2 * 86_400 -> "  (-${behindSec / 86_400} days)"
            behindSec >= 2 * 3_600 -> "  (-${behindSec / 3_600} h ${behindSec / 60 % 60} min)"
            behindSec >= 60 -> "  (-${behindSec / 60} min)"
            behindSec >= 3 -> "  (-$behindSec s)"
            else -> ""
        }
        logos.load(ch?.logoUrl.takeIf { showLogos }, osdLogo)
        osdTitle.text = now?.title.orEmpty()
        osdTime.text = now?.let { "${epg.time(it.startMs)} – ${epg.time(it.endMs)}" }.orEmpty()
        osdNext.text = next?.let { "Volgend: ${it.title}" }.orEmpty()
        osdProgress.visibility = if (now == null) View.INVISIBLE else View.VISIBLE
        if (now != null) {
            val span = now.endMs - now.startMs
            // Never quite empty: right after a restart a sliver of green still marks where the viewer is.
            osdProgress.progress = ((at - now.startMs) * 1000 / span).toInt().coerceIn(MIN_PROGRESS, 1000)
            // The live point, shown only while this program is still on air.
            val wall = System.currentTimeMillis()
            osdProgress.secondaryProgress =
                if (wall < now.endMs) ((wall - now.startMs) * 1000 / span).toInt().coerceIn(0, 1000) else 0
        }
    }

    // ---- stream stats (blue key) ----

    private fun toggleStats() {
        if (stats.visibility == View.VISIBLE) {
            statsJob?.cancel()
            stats.visibility = View.GONE
            return
        }
        stats.visibility = View.VISIBLE
        statsJob = lifecycleScope.launch {
            while (true) {
                stats.text = statsText()
                delay(STATS_REFRESH_MS)
            }
        }
    }

    private fun statsText(): String {
        val p = player ?: return "No player"
        val ch = channels.getOrNull(current)
        val behind = secondsBehindLive()
        val mode = when {
            curCatchUp != null -> "catch-up"
            timeshiftBase != null -> "start over, ${clockLength(behind)} behind live"
            p.playWhenReady.not() -> "paused, ${clockLength(behind)} behind live"
            behind >= 3 -> "${clockLength(behind)} behind live"
            else -> "live"
        }
        val lines = ArrayList<Pair<String, String>>()
        lines += "Channel" to "${ch?.number ?: "-"} ${ch?.name ?: ""}  ·  $mode"

        // Video: what plays, and where it sits among the renditions on offer (trick-play tracks excluded).
        val vf = p.videoFormat
        val offered = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { g -> (0 until g.length).map { g.getTrackFormat(it) } }
            .filter { it.roleFlags and C.ROLE_FLAG_TRICK_PLAY == 0 && it.bitrate > 0 }
            .map { it.bitrate }.distinct().sorted()
        lines += "Video" to if (vf == null) "-" else {
            val rank = offered.indexOfFirst { it == vf.bitrate }.takeIf { it >= 0 && offered.size > 1 }
                ?.let { "  (${it + 1} of ${offered.size})" }.orEmpty()
            "${vf.width}x${vf.height}  ${if (vf.frameRate > 0) fmtNum(vf.frameRate) + " fps  " else ""}" +
                "${codecName(vf.sampleMimeType) ?: "?"}  ${"%.1f".format(vf.bitrate / 1_000_000f)} Mbps$rank"
        }
        val af = p.audioFormat
        lines += "Audio" to if (af == null) "-" else
            audioLabel(af) + if (af.bitrate > 0) "  ${af.bitrate / 1000} kbps" else ""

        val ahead = p.totalBufferedDuration
        lines += "Buffer" to "%.1f s ahead".format(ahead / 1000f)
        if (p.isCurrentMediaItemLive && timeshiftBase == null) {
            val off = p.currentLiveOffset
            val target = if (p.currentTimeline.isEmpty) 0L
            else p.currentTimeline.getWindow(p.currentMediaItemIndex, Timeline.Window()).liveConfiguration?.targetOffsetMs ?: 0L
            lines += "Latency" to if (off == C.TIME_UNSET) "-" else "%.1f s  (target %.1f s)".format(off / 1000f, target / 1000f)
        }
        val bps = bandwidthMeter?.bitrateEstimate ?: 0L
        lines += "Network" to if (bps > 0) "%.1f Mbps measured".format(bps / 1_000_000f) else "-"
        val now = System.currentTimeMillis()
        formatSwitches.removeAll { now - it > STATS_SWITCH_WINDOW_MS }
        lines += "Switches" to "${formatSwitches.size} in the last ${STATS_SWITCH_WINDOW_MS / 60_000} min"
        val counters = p.videoDecoderCounters
        if (counters != null) {
            counters.ensureUpdated()
            lines += "Frames" to "${counters.droppedBufferCount} dropped of ${counters.renderedOutputBufferCount + counters.droppedBufferCount}"
        }
        lines += "Decoder" to decoderName
        fun sec(ms: Long) = "%.2f".format(ms / 1000f)
        lines += "Zap" to if (zapFrameMs < 0) "-" else "${sec(zapFrameMs)} s to picture" +
            "  (URL ${if (zapUrlMs < 0) "-" else sec(zapUrlMs)}, keys ${if (zapKeysMs < 0) "-" else sec(zapKeysMs)}, " +
            "decoder ${if (zapDecoderMs < 0) "kept" else "opened in ${sec(zapDecoderMs)}"})"
        val age = if (this::avs.isInitialized) avs.sessionAgeMs() else -1L
        lines += "Session" to if (age < 0) "-"
        else "${clockLength(age / 1000)} old  (renews after ${AvsClient.SESSION_TTL_MS / 3_600_000} h)"
        val mem = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss / 1024
        lines += "Memory" to "$mem MB in use"

        val width = lines.maxOf { it.first.length }
        return lines.joinToString("\n") { (k, v) -> k.padEnd(width + 2) + v }
    }

    // ---- audio and subtitles (yellow key) ----

    private fun showTracks() {
        osd.visibility = View.GONE
        renderTracks()
        tracksPanel.visibility = View.VISIBLE
        rowAudio.requestFocus()
    }

    private fun hideTracks() {
        tracksPanel.visibility = View.GONE
    }

    private fun audioOptions(): List<TrackOption> {
        val groups = player?.currentTracks?.groups?.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported } ?: return emptyList()
        val raw = groups.flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }
        val labels = raw.map { (g, i) -> audioLabel(g.getTrackFormat(i)) }
        return raw.mapIndexed { n, (g, i) ->
            // Two tracks that read the same get their bitrate or a number so they can be told apart.
            val label = if (labels.count { it == labels[n] } > 1) {
                val kbps = g.getTrackFormat(i).bitrate.takeIf { it > 0 }?.let { " · ${it / 1000} kbps" } ?: " (${n + 1})"
                labels[n] + kbps
            } else labels[n]
            TrackOption(label, trackKey(g.getTrackFormat(i)), g, i, g.isTrackSelected(i))
        }
    }

    private fun subtitleOptions(): List<TrackOption> {
        val groups = player?.currentTracks?.groups?.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported } ?: return emptyList()
        val raw = groups.flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }
        if (raw.isEmpty()) return emptyList()
        val labels = raw.map { (g, i) -> subtitleLabel(g.getTrackFormat(i)) }
        val tracks = raw.mapIndexed { n, (g, i) ->
            val label = if (labels.count { it == labels[n] } > 1) labels[n] + " (${n + 1})" else labels[n]
            TrackOption(label, trackKey(g.getTrackFormat(i)), g, i, g.isTrackSelected(i))
        }
        return listOf(TrackOption("Off", "off", null, 0, tracks.none { it.selected })) + tracks
    }

    /** What makes a track "the same" on another channel: language, codec, channels, role; not the label text. */
    private fun trackKey(f: Format) = "${f.language}|${f.sampleMimeType}|${f.channelCount}|${f.roleFlags}"

    private fun languageName(f: Format): String {
        // The manifest's own label ("Ned") is shown as is; the language name is only a fallback.
        f.label?.takeIf { it.isNotBlank() }?.let { return it }
        val tag = f.language?.takeIf { it != C.LANGUAGE_UNDETERMINED } ?: return "Unknown language"
        return Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH).ifBlank { tag }.replaceFirstChar { it.uppercase() }
    }

    private fun audioLabel(f: Format): String {
        val codec = when (f.sampleMimeType) {
            MimeTypes.AUDIO_AC3 -> "AC-3"
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_AC4 -> "AC-4"
            else -> f.sampleMimeType?.substringAfter('/')?.uppercase()
        }
        val channels = when (f.channelCount) {
            Format.NO_VALUE -> null
            1 -> "mono"
            2 -> "stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> "${f.channelCount} ch"
        }
        val description = if (f.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0) "audio description" else null
        return listOfNotNull(languageName(f), listOfNotNull(codec, channels).joinToString(" ").ifEmpty { null }, description).joinToString(" · ")
    }

    private fun subtitleLabel(f: Format): String {
        val hearing = if (f.roleFlags and C.ROLE_FLAG_CAPTION != 0) "hearing impaired" else null
        return listOfNotNull(languageName(f), hearing).joinToString(" · ")
    }

    /** What the row shows: the selected option, with arrows when there is something to switch to. */
    private fun trackValue(options: List<TrackOption>, none: String): String {
        if (options.isEmpty()) return none
        val sel = options.firstOrNull { it.selected } ?: options.first()
        return if (options.size > 1) "◀ ${sel.label} ▶" else sel.label
    }

    private fun renderTracks() {
        valueAudio.text = trackValue(audioOptions(), "No audio tracks")
        valueSubs.text = trackValue(subtitleOptions(), "None available")
    }

    /** [dir] is -1 (Left) or +1 (Right / OK); the choice wraps around and is remembered for other channels. */
    private fun adjustTrack(row: View, dir: Int) {
        val audio = row.id == R.id.row_audio
        val options = if (audio) audioOptions() else subtitleOptions()
        if (options.size < 2) return
        val at = options.indexOfFirst { it.selected }.coerceAtLeast(0)
        val next = options[Math.floorMod(at + dir, options.size)]
        selectTrack(next, audio)
        prefs.edit().putString(if (audio) "audioKey" else "subtitleKey", next.key).apply()
        // The player reports the new selection through onTracksChanged; show it right away as well.
        valueSubs.takeIf { !audio }?.text = "◀ ${next.label} ▶"
        valueAudio.takeIf { audio }?.text = "◀ ${next.label} ▶"
    }

    private fun selectTrack(option: TrackOption, audio: Boolean) {
        val p = player ?: return
        val type = if (audio) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
        val b = p.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
        if (option.group == null) b.setTrackTypeDisabled(type, true)
        else b.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(option.group.mediaTrackGroup, listOf(option.index)))
        p.trackSelectionParameters = b.build()
    }

    /** A new stream starts with the player's defaults: put the viewer's earlier audio and subtitle choice back. */
    private fun applySavedTracks() {
        listOf(true to audioOptions(), false to subtitleOptions()).forEach { (audio, options) ->
            val saved = prefs.getString(if (audio) "audioKey" else "subtitleKey", null) ?: return@forEach
            val want = options.firstOrNull { it.key == saved } ?: return@forEach
            if (!want.selected) selectTrack(want, audio)
        }
    }

    // ---- sign-in ----

    private fun showLogin(message: String? = null) {
        hideStatus()
        loginError.text = message.orEmpty()
        loginError.visibility = if (message.isNullOrEmpty()) View.GONE else View.VISIBLE
        login.visibility = View.VISIBLE
        loginUser.requestFocus()
    }

    private fun submitLogin() {
        if (loginBusy) return
        val user = loginUser.text.toString().trim()
        val pass = loginPass.text.toString()
        if (user.isEmpty() || pass.isEmpty()) {
            showLoginError("Enter your abonnementsnummer and pincode")
            return
        }
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(loginPass.windowToken, 0)
        loginBusy = true
        loginError.visibility = View.GONE
        loginButton.text = "Signing in…"
        val deviceId = IdentityStore.newDeviceId()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { AvsClient(user, deviceId).register(pass) } }
            loginBusy = false
            loginButton.text = "Sign in"
            result.onSuccess { account ->
                IdentityStore.save(prefs, Identity(account, deviceId))
                loginPass.setText("")
                login.visibility = View.GONE
                avs = AvsClient(account, deviceId, prefs)
                channelFile.delete() // another account can have another lineup
                channels = emptyList()
                current = -1
                previous = -1
                loadChannels()
            }.onFailure {
                showLoginError("Sign in failed: ${it.message}")
            }
        }
    }

    private fun showLoginError(msg: String) {
        loginError.text = msg
        loginError.visibility = View.VISIBLE
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
        private const val MIN_PROGRESS = 6 // of 1000
        private const val MAX_BANNER_S = 15
        private const val LOADER_DELAY_MS = 1500L
        private const val EXIT_WINDOW_MS = 2000L
        /** The start-over stream reaches back about 6 h; stay a little inside that. */
        private const val START_OVER_REACH_MS = 5L * 3_600_000 + 30 * 60_000
        /**
         * A catch-up recording is 20 min longer than its programme (the API does not say how that is split).
         * Playback starts at position 0, which the user found fine; raise this to start later in the recording.
         */
        private const val CATCHUP_LEAD_MS = 0L
        /** The guide loads this many channels above and below what is visible. */
        private const val GUIDE_ROW_MARGIN = 4
        private val GUIDE_PASS_KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_SPACE,
        )
        private const val GUARD_MS = 2500L
        private const val AUTH_REFUSED = "KPN no longer accepts this TV. Sign in again."
        /** Zaps closer together than this are a run; the last one of a run tunes after the settle time. */
        private const val ZAP_RUN_MS = 400L
        private const val ZAP_SETTLE_MS = 250L
        /** With a remembered channel list, the fresh one is fetched this long after start, clear of the first tune. */
        private const val CHANNEL_REFRESH_DELAY_MS = 8_000L
        private const val EPG_REFRESH_CHUNK = 36
        private const val STREAM_FADE_MS = 700L
        private const val SEEK_REPEAT_MS = 1500L
        private const val SEEK_HINT_MS = 1800L
        private const val STATS_REFRESH_MS = 1000L
        private const val STATS_SWITCH_WINDOW_MS = 10L * 60_000
        /** Seek step in seconds; each quick repeat of the key moves one step up the ladder. */
        private val SEEK_STEPS_S = longArrayOf(10, 30, 60, 120, 300)
        // Artwork size in px (the image host scales to any WxH): ~2x the dp size of the view.
        private const val INFO_IMAGE_W = 640
        private const val INFO_IMAGE_H = 360
        private const val HIGH_START_BPS = 20_000_000L
        private const val GLOW_MS = 2500L
    }
}
