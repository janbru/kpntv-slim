package nl.kpn.slim

import android.os.Build
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Channel(
    val id: Int,
    val number: Int,
    val name: String,
    val assetId: Int,
    val logoUrl: String?,
)

data class Stream(val manifestUrl: String, val licenseUrl: String?)

data class Program(
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val description: String = "",
    val genres: List<String> = emptyList(),
    val year: String = "",
    val episodeTitle: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val presenters: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    val ageRating: Int = 0,
    /** The API flags whether this programme may be restarted from its beginning. */
    val startOver: Boolean = false,
    /** Opaque artwork id; see [programImageUrl]. */
    val pictureId: String = "",
) {
    fun imageUrl(w: Int, h: Int): String? = programImageUrl(pictureId, w, h)
}

private const val IMAGE_HOST = "https://images.tv.kpn.com"

/** Channel logo, scaled to [height] px (width follows the aspect ratio). Pattern taken from the KPN web player. */
fun channelLogoUrl(externalId: String, height: Int): String? =
    externalId.takeIf { it.isNotEmpty() }?.let { "$IMAGE_HOST/logo/$it/$height.png" }

/** Programme artwork at exactly [w]x[h] px. */
fun programImageUrl(pictureId: String, w: Int, h: Int): String? =
    pictureId.takeIf { it.isNotEmpty() }?.let { "$IMAGE_HOST/epg/$it/${w}x$h.jpg?blurred=false" }

open class AvsException(message: String, val httpStatus: Int = 0) : IOException(message)

/** The server refused to give this deviceId a session (not registered, or signed out elsewhere). */
class AuthException(message: String) : AvsException(message)

/**
 * Minimal client for KPN's Nagra/MediaKind AVS backend. See API_CONTRACT.md.
 * Not thread-safe by design; call from a single dispatcher (the caller serializes).
 */
class AvsClient(
    private val account: String,
    private val deviceId: String,
) {
    private var avsHost: String? = null
    private val cookies = linkedMapOf<String, String>()
    private var xsrf: String? = null
    private var sessionAt = 0L

    private val base get() = "https://$avsHost/000002/1.5/A/nld/androidtv/kpn"

    /**
     * One-time sign-in: registers this client's [deviceId] with the account by presenting the pincode once,
     * like the KPN web player's subscription number + pincode login. [account] is the abonnementsnummer.
     * Returns the account number (what later sessions need) as given back in the profile.
     */
    @Synchronized
    fun register(pin: String): String = newSession(pin).ifEmpty { account }

    @Synchronized
    fun liveChannels(): List<Channel> {
        val json = authed {
            get("$base/TRAY/LIVECHANNELS?orderBy=orderId&sortOrder=asc&from=0&to=999" +
                "&dfilter_channels=subscription&filter_excludedGenres=Erotiek&filter_isAdult=false")
        }
        val containers = json.getJSONObject("resultObj").getJSONArray("containers")
        return (0 until containers.length()).mapNotNull { i ->
            val c = containers.getJSONObject(i)
            val md = c.getJSONObject("metadata")
            val asset = c.optJSONArray("assets")?.optJSONObject(0) ?: return@mapNotNull null
            Channel(
                id = md.getInt("channelId"),
                number = md.optInt("orderId", i + 1),
                name = md.optString("channelName"),
                assetId = asset.getInt("assetId"),
                logoUrl = channelLogoUrl(md.optString("externalId"), LOGO_HEIGHT),
            )
        }
    }

    /**
     * Programs airing in the next [windowMs] for every channel, keyed by channelId and sorted by start.
     * One call covers the whole lineup (~400 KB); the caller derives now/next from the times.
     */
    @Synchronized
    fun epg(channels: List<Channel>, windowMs: Long = 3L * 3_600_000): Map<Int, List<Program>> {
        val now = System.currentTimeMillis()
        val ids = channels.joinToString(",") { it.id.toString() }
        val json = authed {
            get("$base/TRAY/EPG?filter_startTime=$now&filter_endTime=${now + windowMs}" +
                "&filter_channelIds=$ids&outputFormat=EXTENDED")
        }
        val rows = json.getJSONObject("resultObj").optJSONArray("containers") ?: return emptyMap()
        val out = HashMap<Int, List<Program>>(rows.length() * 2)
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val channelId = row.optJSONObject("metadata")?.optInt("channelId", -1) ?: -1
            val programs = row.optJSONArray("containers") ?: continue
            if (channelId < 0) continue
            out[channelId] = (0 until programs.length()).mapNotNull { j ->
                val md = programs.getJSONObject(j).optJSONObject("metadata") ?: return@mapNotNull null
                val title = md.optString("title")
                val start = md.optLong("airingStartTime")
                val end = md.optLong("airingEndTime")
                if (title.isEmpty() || end <= start) null else Program(
                    title = title,
                    startMs = start,
                    endMs = end,
                    description = md.optString("longDescription"),
                    genres = md.strings("genres"),
                    year = md.optString("year"),
                    episodeTitle = md.optString("episodeTitle"),
                    season = md.optInt("season"),
                    episode = md.optInt("episodeNumber"),
                    presenters = md.strings("presenters"),
                    directors = md.strings("directors"),
                    actors = md.strings("actors"),
                    ageRating = md.optInt("pcLevel"),
                    startOver = md.strings("contentOptions").contains("STARTOVER"),
                    pictureId = md.optString("pictureUrl"),
                )
            }.sortedBy { it.startMs }
        }
        return out
    }

    @Synchronized
    fun liveStream(ch: Channel, startAtMs: Long? = null): Stream {
        // Live: the official app rounds `time` down to 5 minutes (CDN-cache friendly).
        // Start over: `time` = the programme start; the returned manifest then begins at that moment.
        val time = startAtMs ?: (System.currentTimeMillis() / 300_000 * 300_000)
        val json = authed {
            get("$base/CONTENT/VIDEOURL/LIVE/${ch.id}/${ch.assetId}?deviceId=$deviceId&profile=G09&time=$time")
        }
        val sources = json.getJSONObject("resultObj").getJSONObject("src").getJSONObject("sources")
        val license = sources.optJSONObject("contentProtection")
            ?.optJSONObject("widevine")
            ?.optString("licenseAcquisitionURL")
            ?.takeIf { it.isNotEmpty() }
        return Stream(sources.getString("src"), license)
    }

    /** Runs [block] with a valid session; on failure re-creates the session once and retries. */
    private fun authed(block: () -> JSONObject): JSONObject {
        if (avsHost == null || System.currentTimeMillis() - sessionAt > SESSION_TTL_MS) newSession()
        return try {
            block()
        } catch (e: IOException) {
            newSession()
            block()
        }
    }

    /** Creates a session. With [pin] it also registers the deviceId; returns the account number from the profile. */
    private fun newSession(pin: String? = null): String {
        if (avsHost == null) {
            val ear = request("GET", "$EAR?type=ott&tan=${enc(account)}", body = null, auth = false)
            avsHost = ear.getJSONObject("result").getString("url")
        }
        cookies.clear()
        xsrf = null
        val deviceInfo = JSONObject()
            .put("deviceId", deviceId)
            .put("deviceIdType", "DEVICEID")
            .put("deviceType", "androidtv")
            .put("deviceModel", Build.DEVICE)
            .put("deviceVendor", Build.MANUFACTURER)
            .put("swVersionId", Build.ID)
            .put("deviceFirmVersion", Build.ID)
            .put("appVersion", APP_VERSION)
            .put("serialNumber", "NotAvailable")
            .put("ipAddress", "127.0.0.1")
        val std = JSONObject().put("deviceInfo", deviceInfo)
        if (pin != null) std.put("username", account).put("password", pin).put("remember", true)
        val body = JSONObject().put("credentialsStdAuth", std)
        val json = try {
            request("POST", "$base/USER/SESSIONS/", body.toString(), auth = false)
        } catch (e: AvsException) {
            // A reply that says no (HTTP 401/403 or an AVS error code) is an auth problem; 5xx is just a bad moment.
            if (e.httpStatus == 0 || e.httpStatus in 401..403) throw AuthException(e.message.orEmpty()) else throw e
        }
        if (cookies["avs6_cookie"] == null) throw AuthException("Session refused (no avs6_cookie)")
        sessionAt = System.currentTimeMillis()
        return json.optJSONObject("resultObj")?.optJSONObject("profile")?.optJSONObject("profileData")
            ?.optString("username").orEmpty()
    }

    private fun get(url: String) = request("GET", url, null, auth = true)

    private fun request(method: String, url: String, body: String?, auth: Boolean): JSONObject {
        val sep = if ('?' in url) '&' else '?'
        val conn = URL("$url${sep}0_0=$APP_VERSION").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("user-agent", "Dart/3.9 (dart:io)")
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("avssite", "http://www.itvonline.nl")
            if (auth) {
                if (cookies.isNotEmpty()) {
                    conn.setRequestProperty("cookie", cookies.entries.joinToString(";") { "${it.key}=${it.value}" })
                }
                xsrf?.let { conn.setRequestProperty("x-xsrf-token", it) }
            }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            captureSession(conn)
            val text = (if (code < 400) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw AvsException("HTTP $code for ${url.substringBefore('?').substringAfterLast("/kpn")}", code)
            val json = JSONObject(text)
            val rc = json.optString("resultCode")
            if (rc.isNotEmpty() && rc != "OK") {
                throw AvsException("${json.optString("errorDescription")} ${json.optString("message")}".trim())
            }
            return json
        } finally {
            conn.disconnect()
        }
    }

    /** AVS rotates cookies / xsrf on many responses; always keep the latest. */
    private fun captureSession(conn: HttpURLConnection) {
        for ((key, values) in conn.headerFields) {
            when {
                key == null -> Unit
                key.equals("set-cookie", true) -> values.forEach { raw ->
                    val pair = raw.substringBefore(';')
                    val name = pair.substringBefore('=').trim()
                    val value = pair.substringAfter('=', "").trim()
                    if (name.isNotEmpty()) {
                        if (value.isEmpty()) cookies.remove(name) else cookies[name] = value
                    }
                }
                key.equals("x-xsrf-token", true) -> values.firstOrNull()?.let { xsrf = it }
            }
        }
    }

    private fun JSONObject.strings(key: String): List<String> {
        val a = optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val APP_VERSION = "09.06.00"
        private const val EAR = "https://ausar.tcloud-itv-prd1.prod.aws.kpn.com/public/v1/ear"
        /** Logos are shown at most 40 dp high; 100 px is sharp on a 1080p TV and only ~3 KB. */
        const val LOGO_HEIGHT = 100
        private const val SESSION_TTL_MS = 3L * 60 * 60 * 1000 // avs6_cookie lives 4h
    }
}
