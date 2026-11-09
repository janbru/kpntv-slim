package nl.kpn.slim

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
)

class AvsException(message: String) : IOException(message)

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
            val logo = asset.optString("logoMedium").ifEmpty { asset.optString("logoSmall") }
            Channel(
                id = md.getInt("channelId"),
                number = md.optInt("orderId", i + 1),
                name = md.optString("channelName"),
                assetId = asset.getInt("assetId"),
                logoUrl = logo.takeIf { it.isNotEmpty() }?.let { "https://images.tv.kpn.com/$it" },
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

    private fun newSession() {
        if (avsHost == null) {
            val ear = request("GET",
                "https://ausar.tcloud-itv-prd1.prod.aws.kpn.com/public/v1/ear?type=ott&tan=${enc(account)}",
                body = null, auth = false)
            avsHost = ear.getJSONObject("result").getString("url")
        }
        cookies.clear()
        xsrf = null
        val deviceInfo = JSONObject()
            .put("deviceId", deviceId)
            .put("deviceIdType", "DEVICEID")
            .put("deviceType", "androidtv")
            .put("deviceModel", "PH9M_EA_5599")
            .put("deviceVendor", "Philips")
            .put("swVersionId", "STT2.230526.001")
            .put("deviceFirmVersion", "STT2.230526.001")
            .put("appVersion", APP_VERSION)
            .put("serialNumber", "NotAvailable")
            .put("ipAddress", "127.0.0.1")
        val body = JSONObject().put("credentialsStdAuth", JSONObject().put("deviceInfo", deviceInfo))
        request("POST", "$base/USER/SESSIONS/", body.toString(), auth = false)
        if (cookies["avs6_cookie"] == null) throw AvsException("Session refused (no avs6_cookie)")
        sessionAt = System.currentTimeMillis()
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
            if (code !in 200..299) throw AvsException("HTTP $code for ${url.substringBefore('?').substringAfterLast("/kpn")}")
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
        private const val SESSION_TTL_MS = 3L * 60 * 60 * 1000 // avs6_cookie lives 4h
    }
}
