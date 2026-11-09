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
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
    private lateinit var osdTitle: TextView
    private lateinit var osdTime: TextView
    private lateinit var osdNext: TextView
    private lateinit var osdProgress: ProgressBar
    private lateinit var status: TextView

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

    private val prefs by lazy { getSharedPreferences("slim", MODE_PRIVATE) }

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
        osdTitle = findViewById(R.id.osd_title)
        osdTime = findViewById(R.id.osd_time)
        osdNext = findViewById(R.id.osd_next)
        osdProgress = findViewById(R.id.osd_progress)
        status = findViewById(R.id.status)
        list.layoutManager = LinearLayoutManager(this)
        logos = LogoLoader(lifecycleScope)

        loadChannels()
    }

    override fun onStart() {
        super.onStart()
        registry.currentState = Lifecycle.State.STARTED
        player = ExoPlayer.Builder(this).build().also { p ->
            p.playWhenReady = true
            p.addListener(playerListener)
            playerView.player = p
        }
        if (current >= 0) tune(current, debounce = false)
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
        tuneJob?.cancel()
        playerView.player = null
        player?.release()
        player = null
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
                adapter = ChannelAdapter(list, logos, epg) { i -> hideList(); tune(i, debounce = false) }
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

    private fun tune(index: Int, debounce: Boolean = true) {
        if (channels.isEmpty()) return
        val i = Math.floorMod(index, channels.size)
        if (i != current) { previous = current; retriedCurrent = false }
        current = i
        adapter?.playingIndex = i
        val ch = channels[i]
        prefs.edit().putInt("lastChannelId", ch.id).apply()
        showOsd(ch)

        tuneJob?.cancel()
        tuneJob = lifecycleScope.launch {
            if (debounce) delay(250) // let rapid zapping settle before hitting the API
            val p = player ?: return@launch
            p.stop()
            val stream = withContext(Dispatchers.IO) { runCatching { avs.liveStream(ch) } }
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
                p.setMediaItem(item)
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

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) { retriedCurrent = false; hideStatus() }
        }
    }

    // ---- remote control ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val code = event.keyCode

        if (list.visibility == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) { hideList(); return true }
            return super.dispatchKeyEvent(event)
        }

        if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) { typeDigit(code - KeyEvent.KEYCODE_0); return true }

        when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> tune(current + 1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> tune(current - 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_GUIDE -> {
                if (channels.isEmpty()) loadChannels() else showList()
            }
            KeyEvent.KEYCODE_LAST_CHANNEL -> if (previous >= 0) tune(previous, debounce = false)
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_DPAD_RIGHT -> channels.getOrNull(current)?.let(::showOsd)
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
            (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(target, list.height / 3)
            list.post { list.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
        }
    }

    private fun hideList() {
        list.visibility = View.GONE
    }

    private fun showOsd(ch: Channel) {
        osdNumber.text = ch.number.toString()
        osdName.text = ch.name
        bindGuide(ch)
        if (epg.isStale()) refreshEpg()
        osd.visibility = View.VISIBLE
        osdJob?.cancel()
        osdJob = lifecycleScope.launch { delay(5000); osd.visibility = View.GONE }
    }

    /** Fills the program rows of the banner for [ch]; clears them when [ch] is null or has no guide data. */
    private fun bindGuide(ch: Channel?) {
        val now = ch?.let { epg.now(it.id) }
        val next = ch?.let { epg.next(it.id) }
        osdClock.text = epg.time(System.currentTimeMillis())
        osdTitle.text = now?.title.orEmpty()
        osdTime.text = now?.let { "${epg.time(it.startMs)} – ${epg.time(it.endMs)}" }.orEmpty()
        osdNext.text = next?.let { "Volgend: ${it.title}" }.orEmpty()
        osdProgress.visibility = if (now == null) View.INVISIBLE else View.VISIBLE
        if (now != null) {
            val t = System.currentTimeMillis()
            osdProgress.progress = ((t - now.startMs) * 1000 / (now.endMs - now.startMs)).toInt().coerceIn(0, 1000)
        }
    }

    private fun showStatus(msg: String) {
        status.text = msg
        status.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        status.visibility = View.GONE
    }
}
