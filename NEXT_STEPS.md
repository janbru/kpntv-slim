# KPN TV+ slim — project state & next steps

Last updated: 2026-11-09. Read this first when resuming.

## Status: v0.1 app BUILT and running on the TV (2026-11-09).

App lives in `android/` (package `nl.kpn.slim`, label "KPN Slim"). Release APK 1.1 MB, ~72 MB PSS while playing.
- Build: `cd android && ./gradlew assembleRelease` (JDK 21 pinned in `gradle.properties`; Homebrew's default JDK 26 breaks Gradle 8.14).
- Install: `adb -s 192.168.2.21:5555 install -r app/build/outputs/apk/release/app-release.apk` (signed with the debug keystore; installs alongside the official app).
- Account + deviceId come from `android/gradle.properties` (`kpn.account`, `kpn.deviceId`) into BuildConfig.
- Code: `AvsClient.kt` (EAR → SESSIONS → LIVECHANNELS → VIDEOURL, HttpURLConnection + org.json, cookie/xsrf rotation, 3h session refresh + retry once), `MainActivity.kt` (ExoPlayer DASH + Widevine, zap/list/digits/OSD), `ChannelAdapter.kt`, `LogoLoader.kt`.
- Remote: Up/Down or CH± zap, digits = channel number, OK = options menu, Menu/Left/Guide = channel list, Right/Info = banner (press again = options menu), Back closes overlay/exits. Remembers last channel.
- Verified on TV: session, 108 channels, Widevine playback, zapping, list overlay.

### v0.2: EPG now/next (done 2026-11-09)
`AvsClient.epg()` does one `GET /TRAY/EPG?filter_startTime={now}&filter_endTime={now+3h}&filter_channelIds={all ids}&outputFormat=EXTENDED` (~400 KB, ~0.4 s, all 108 channels). Response: `containers[]` per channel (`metadata.channelId`) with nested `containers[]` programs (`metadata.title/airingStartTime/airingEndTime`, also `pictureUrl`, `longDescription`). `Epg.kt` holds it in memory (refresh when older than 15 min, on OSD/list open); list rows show the current program, the banner shows now + next. Verified on the TV.

### v0.3: stream info banner + settings (2026-11-09)
Bottom info banner shows live video/audio stream info. Settings: first row of the channel list (OK/centre, then Left/Right/OK to change, Back to close). "High bandwidth start" (default off) gives ExoPlayer a 20 Mbps initial bandwidth estimate so it starts at 1080p50/8 Mbps instead of ramping up; "Info banner duration" 1-15 s (default 5). Both live in SharedPreferences `slim`. Info banner keys: Right / Info. The TV's system intercepts the Settings key, so settings are reached via the list.

### v0.4: pause, start over, programme info (2026-11-09)
- **Start over needs no new endpoint**: `VIDEOURL/LIVE/{ch}/{asset}?time={programStartMs}` (exact ms, not rounded) returns a manifest that begins at that moment (the response URL carries `&t=<ISO time>`). The USP window reaches back about 6 h (tried 10 min to 3 days, anything older is clamped). Played with `setMediaItem(item, 0)`. Programmes carry `contentOptions: STARTOVER`; the app only offers it when that flag is set. After a restart the banner shows `(-N min)`, progress follows the playback position (`now - currentLiveOffset + target offset`), and "Go to live" appears.
- Careful when testing with curl: AVS rotates the xsrf/cookies, so a second call with the first session's cookie fails with `403-10110 Token not found`. Create a fresh session per call.
- Pause: Play/Pause, Pause and Play media keys (and Space). Paused indicator top right.
- Options menu: OK, or Right/Info a second time while the banner is showing (Pause/Play, Start from beginning, Go to live, Programme info, Channel list, Settings). Red = start over, Green = programme info.
- Programme info panel: title, season/episode, time, genres, year, age rating, description, presenters/directors/cast (all from the EPG response).

- When not live, the banner and info panel describe the programme being *played* (`timeshiftBase + currentPosition`), not the live one. `Epg.replace` merges refreshes and keeps programmes that ended up to 6 h ago so a started-over programme keeps its info after it has left the live guide.

### Known gaps / next ideas
- **Channel logos don't load**: `images.tv.kpn.com/{logoMedium}` (e.g. `size_x/dtvchannels_1_100x50.png`) returns 404. The real URL pattern needs a recapture with `--allow-hosts` including `images\.tv\.kpn\.com` (libapp.so has a `/logo/` path segment). Views collapse gracefully meanwhile.
- deviceId is hardcoded to this TV; deriving it on-device would make the app portable.

## (Previous) RE status: COMPLETE and validated.

- Full report: **`FINDINGS.md`**. Exact HTTP contract: **`API_CONTRACT.md`**.
- The live-TV API chain (EAR → deviceId session → channel list → DASH+Widevine play URL) was captured and **re-validated from plain `curl` on macOS**. Nothing else is blocking the build.
- TV was returned to stock: official KPN app reinstalled, WireGuard tunnel down, proxy cleared. The TV will ask for login next time it's opened (data was wiped).

## What's in this folder now (after cleanup)

| Path | Keep? | Note |
|---|---|---|
| `*.apkm` | ✅ source | the original split bundle (re-extract anything from here) |
| `FINDINGS.md`, `API_CONTRACT.md` | ✅ | the deliverable docs |
| `NEXT_STEPS.md` | ✅ | this file |
| `jadx-out/` | ✅ | jadx decompilation (Java side; regenerable: `jadx -d jadx-out --deobf base.apk`) |
| `libs/libapp.so` | ✅ | Dart AOT snapshot — `strings` it for any detail not yet captured |
| `tools/APKEditor.jar` | ✅ | reusable (merge/manifest-edit) |
| `capture/flows.mitm` | ✅ | raw mitmproxy capture — replay: `mitmdump -nr capture/flows.mitm --flow-detail 3` |
| `capture/channels.json` | ✅ | validated 108-channel LIVECHANNELS response (sample data for the UI) |
| `capture/remote_config.json` | ✅ | Firebase Remote Config dump (feature flags) |
| `capture/flutter_prefs.xml` | ✅ | app prefs (channel-id model, account id) |
| `capture/kpn-wg.conf` | ✅ | WireGuard client config (if we need to recapture) |
| ~~tools build APKs, extracted/, restore/, full_dump.txt~~ | deleted | all regenerable; see commands below |

## Key facts to resume with (also in memory + API_CONTRACT.md)

- TV: Philips `192.168.2.21:5555`, adb at `~/Library/Android/sdk/platform-tools/adb`. Android 12, armeabi-v7a, **not rooted**.
- Account `tan` = `20000010487262`. This TV's `deviceId` = `131837EB582CC64E8510763B027C5976E795F9F88096036DD5B1D2D1644A46C8` (serial-based SHA-256).
- Auth = **deviceId only** on `POST /USER/SESSIONS`. AVS host discovered via EAR. Base path `/000002/1.5/A/nld/androidtv/kpn/`. Headers: cookies (`sessionId`,`up`,`avs6_cookie`) + `x-xsrf-token` + `avssite: http://www.itvonline.nl`.
- Play: `GET /CONTENT/VIDEOURL/LIVE/{channelId}/{assetId}?deviceId=..&profile=G09&time={nowMs}` → DASH `.mpd` + Widevine `licenseAcquisitionURL`.

## Build plan (the actual goal)

Native **Android, Kotlin, Media3/ExoPlayer**, leanback. Target < 10 MB, low memory. No Flutter, Firebase, ads, consent, recordings, VOD, cast.

1. **AVS client** (OkHttp/Retrofit + cookie jar): `ear()`, `createSession(deviceId)`, `liveChannels()`, `videoUrl(channelId, assetId)`.
2. **Channel grid** (leanback `BrowseSupportFragment` or `RecyclerView`): logos from `images.tv.kpn.com/{logoBig}`.
3. **Player**: `ExoPlayer` + `DashMediaSource` + `DefaultDrmSessionManager` (Widevine, `licenseUri` from VIDEOURL). Up/down = channel zap.
4. v1 scope: session + grid + play + zap. EPG now/next (done in v0.2).

### Open decisions for next session
- **deviceId handling**: hardcode this TV's deviceId, or replicate the serial-based SHA-256 derivation so it works on any device? (Derivation hints in `libapp.so`: "serialBasedDeviceId", "deviceId migrated to full-length SHA-256".)
- **EPG in v1?** (yes/no)
- **Project location**: new Android Studio project — where (subfolder here, or separate repo)?

## How to recapture live traffic (only if a new detail is needed)

Tools (already installed via pipx): `mitmproxy`, `reflutter`; `tools/APKEditor.jar`; Android build-tools 36 (`zipalign`/`apksigner`); debug keystore at `~/.android/debug.keystore` (pass `android`).

```bash
# 1. merge splits -> universal
java -jar tools/APKEditor.jar m -i *.apkm -o /tmp/universal.apk
# 2. (optional) make debuggable: APKEditor d -> add android:debuggable="true" to <application> -> APKEditor b
# 3. SSL-bypass patch:   printf '1\n192.168.2.16\n' | reflutter /tmp/universal.apk   (-> release.RE.apk)
# 4. zipalign -p -f 4 release.RE.apk cap.apk ; apksigner sign --ks ~/.android/debug.keystore ... cap.apk
# 5. mitmdump --mode wireguard --allow-hosts 'aws\.kpn\.com' -w capture/flows.mitm
#    derive client config from ~/.mitmproxy/wireguard.conf -> capture/kpn-wg.conf (Mac IP 192.168.2.16:51820)
# 6. TV: WireGuard app imports kpn-wg.conf, toggle ON. Install cap.apk (-r keeps login). Log in / tune.
# 7. parse: mitmdump -nr capture/flows.mitm --flow-detail 3
```
Note: Flutter/Dart ignores the Android system proxy + user CA store — a plain proxy won't work; WireGuard transparent redirect + reFlutter cert bypass is required. Remember to toggle WireGuard OFF and restore the official app afterward (`adb install-multiple` the splits from the `.apkm`).
