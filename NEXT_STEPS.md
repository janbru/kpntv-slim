# KPN TV+ slim: project state & next steps

Last updated: 2026-11-09 (session 4). Read this first when resuming.

## Status: native app works on the TV (v0.6)

Minimal Kotlin + Media3 live-TV viewer for KPN TV+ in `android/` (package `nl.kpn.slim`, label "KPN Slim"). Release APK about 1.2 MB. The git repo is local only (6 commits on `main`).

### Build, install, setup
- Build: `cd android && ./gradlew assembleRelease` (JDK 21 pinned in `gradle.properties`; Homebrew's default JDK 26 breaks Gradle 8.14).
- Install: `~/Library/Android/sdk/platform-tools/adb -s 192.168.2.21:5555 install -r app/build/outputs/apk/release/app-release.apk` (debug-keystore signed; coexists with the official app). Launch: `adb shell am start -n nl.kpn.slim/.MainActivity`.
- **`android/gradle.properties` is git-ignored** (account, deviceId, JDK path). On a fresh clone copy `gradle.properties.example` to `gradle.properties` and fill it in. Also ignored: `capture/` (tokens, WireGuard keys), `jadx-out/`, `libs/`, the `.apkm`, `tools/*.jar`, `my-tv.jpg`.
- The web player at tv.kpn.com is a useful reference for URL patterns (its `app.js` is readable); try it before a recapture.
- FINDINGS.md, API_CONTRACT.md and this file still contain the account number and deviceId in plain text. Redact before sharing the repo or adding a remote.

### Code map (`android/app/src/main/java/nl/kpn/slim/`)
- `AvsClient.kt`: EAR, SESSIONS, LIVECHANNELS, VIDEOURL, EPG. `HttpURLConnection` + `org.json`, cookie/xsrf rotation, 3 h session refresh, retry once. `Program` carries `pictureId`, description, genres, cast, year, age rating, `startOver` flag.
- `Epg.kt`: in-memory guide. `now/next(channelId, at)`, refresh every 15 min, merges refreshes and keeps programmes that ended up to 6 h ago.
- `MainActivity.kt`: everything UI. ExoPlayer DASH + Widevine, zapping, overlays (list, options menu, info panel, settings, banner), pause, start over, stream info.
- `ChannelAdapter.kt`: channel list, first row is "Settings". `LogoLoader.kt`: image loader for logos and programme artwork (byte-capped 12 MB LRU, 4 parallel downloads, hides the view when there is no image).
- Layout: `res/layout/activity_main.xml` holds all overlays. Colours in `res/values/colors.xml`.

### Remote control
- Up/Down or CH+/CH-: zap. Digits: channel number. Last channel: previous channel.
- **OK: options menu** (Pause/Play, Start from beginning, Go to live when behind, Programme info, Channel list, Settings).
- Right or Info: show the bottom banner; press again while it shows to open the options menu.
- Menu, Left, Guide: channel list (its first row opens Settings).
- Play/Pause, Pause, Play media keys (and Space): pause. Red: start over. Green: programme info.
- Fast forward / Rewind media keys (also skip/step keys): seek. 10 s, and quick repeats (within 1.5 s) step up 30 s, 1 min, 2 min, 5 min. Forward past the live edge lands on live.
- Stop: back to live when paused or behind (instant seek to the live edge on a plain live stream, re-tune after start over). When already live it behaves like Back (asks to exit).
- Back: close the open overlay; with no overlay the first press shows a "Press back again to exit" toast and a second press within 2 s exits the app.
- The TV's system intercepts the Settings key, so it is not used.

### Features
- **EPG now/next**: one `GET /TRAY/EPG?filter_startTime={now}&filter_endTime={now+3h}&filter_channelIds={all}&outputFormat=EXTENDED` (about 400 KB, 0.4 s) for all 108 channels. List rows show the current programme.
- **Bottom banner** (860dp wide, rounded, floating above the bottom edge): channel number and name, live video/audio stream info, clock, programme title, time range, "Volgend: next", progress bar. Stream info is dim; any piece that changes during the same stream (adaptive quality switch) glows green and fades over 2.5 s. A new stream starts without a glow (`lastParts` is reset right before `setMediaItem`).
- **Start from beginning**: no extra endpoint. `VIDEOURL/LIVE/{ch}/{asset}?time={programStartMs}` (exact ms, not rounded) returns a manifest that begins at that moment (URL carries `&t=<ISO>`). The window reaches back about 6 h (tested 10 min to 3 days, older is clamped). Played with `setMediaItem(item, 0)`. Only offered when the programme has `contentOptions: STARTOVER`. While not live: banner and info panel describe the programme being played (`timeshiftBase + currentPosition`), the clock shows `(-N min)`, "Go to live" appears. "Behind live" is measured against ExoPlayer's target live offset, so plain live never counts as behind.
- **Pause**: pause icon top right with the time behind live next to it (`-0:14`, updated twice a second). Live pause works because the dynamic manifest has a long window. The banner clock also shows `(-28 s)` / `(-N min)` when behind.
- **Buffering spinner**: ExoPlayer's own one is off; a green spinner appears only after 1 s of continuous tuning/buffering (`armLoader`, `LOADER_DELAY_MS`), so quick zaps stay clean.
- **Programme info panel**: title, season/episode, episode title, channel, time and length, genres, year, age rating, description, presenters/directors/cast.
- **Settings** (SharedPreferences `slim`): "High bandwidth start" (default Off) gives ExoPlayer a 20 Mbps initial bandwidth estimate so playback starts at 1080p50 / 8 Mbps instead of ramping up from 768x432. Only matters on a cold start, because the shared bandwidth meter remembers its estimate afterwards. "Info banner duration" 1-15 s (default 5). Toggling the first one rebuilds the player and re-tunes.
- The TV currently has High bandwidth start **Off** and banner **15 s** (left over from testing).

- **Images** (v0.6): channel logo in the list rows and the banner header, programme artwork in the info panel only (640x360 on the right; the banner has no thumbnail by choice). URL patterns come from the KPN web player bundle (`https://tv.kpn.com/app.js`, helpers `wt` and `Xr`), no app recapture needed:
  - Channel logo: `https://images.tv.kpn.com/logo/{externalId}/{height}.png` (`externalId` from LIVECHANNELS metadata, e.g. `dtvchannels_1`; height 100 gives 149x100; transparent PNG; all 108 channels verified).
  - Programme: `https://images.tv.kpn.com/epg/{pictureUrl}/{w}x{h}.jpg?blurred=false` (`pictureUrl` from the EPG metadata; any WxH works; `vod/` instead of `epg/` for non-programme content; `blurred=true` gives a blurred variant; `round=true` gives a round PNG).
  - Provider logos: `/misc/provider/{id}/{w}x{h}.png`. The old `logoMedium`/`size_x/...` paths in the channel assets are dead.

### Findings about the streams (see also FINDINGS.md)
- Channel metadata says SD/MPEG-2 for every channel, but the DASH manifests have H.264 up to 1080p50 (6 and 8 Mbps), AC-3 5.1 at 384 kbps or AAC stereo 128 kbps, a Dutch subtitle stream and trick-play tracks. Some channels are 1080p25.
- Per programme the EPG also has `pictureUrl`, `starRating`, `contentOptions` (`STARTOVER`, `CATCHUP`, `RECORDABLE`, `TV_PREMIERE`). Per channel: timeshift on 105, out-of-home (`OOH`) on 82, `PLUS` on 25, unicast on 21.
- Firebase remote config (`capture/remote_config.json`) has ad tags and flags such as `highBandwidthStartEnabled`, `aiSearchEnabled`.

### Testing gotchas (learned the hard way)
- AVS rotates xsrf and cookies: a second curl with the first session's cookie fails with `403-10110 Token not found`. Create a fresh session per call (a helper like `sess.sh` that does EAR + `POST /USER/SESSIONS` and saves cookie jar + xsrf).
- `adb shell input keyevent KEYCODE_BACK` with no overlay open exits the app, and later keys then drive the TV's launcher (it once opened a movie page). Check the screen before sending key sequences, or use `am force-stop` + `am start`.
- `adb exec-out screencap -p` takes about 3 s, so keep loops short or run them in the background. Crop with `magick`.
- Press keys one at a time and screenshot; the banner hides itself after the configured duration.

### Known gaps / next ideas
- Audio and subtitle track picker (manifest has a second Dutch audio track and a Dutch subtitle stream; ExoPlayer picks defaults).
- Catch-up of earlier programmes and a full EPG grid (the `TRAY/EPG` endpoint takes any time range; start-over proves play URLs accept past times).
- Artwork in the channel list rows (small programme thumbnail) or a grid-style EPG.
- Recordings/CPVR, out-of-home, trick-play thumbnails while scrubbing (no seeking UI yet).
- deviceId is hardcoded to this TV; deriving it on-device (serial-based SHA-256) would make the app portable.
- Long pause: if the manifest token expires the player errors once and the app re-tunes live (not resumed at the paused position).

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
