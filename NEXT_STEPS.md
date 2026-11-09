# KPN TV+ slim: project state & next steps

Last updated: 2026-11-09 (session 5). Read this first when resuming.

## Status: native app works on the TV (v0.8)

Minimal Kotlin + Media3 live-TV viewer for KPN TV+ in `android/` (package `nl.kpn.slim`, label "KPN Slim"). Release APK about 1.2 MB. The git repo is local only (16 commits on `main`, including the one that updates this line). New in v0.8 (session 5): one-time sign-in with abonnementsnummer + pincode (no more hardcoded deviceId), new icon and banner, in-app hint pill, Settings row with icon in the channel list.

### Build, install, setup
- Build: `cd android && ./gradlew assembleRelease` (JDK 21 pinned in `gradle.properties`; Homebrew's default JDK 26 breaks Gradle 8.14). To build without the optional account/deviceId seed (what a fresh user gets, and what is on the TV now): add `-Pkpn.account= -Pkpn.deviceId=`.
- Install: `~/Library/Android/sdk/platform-tools/adb -s 192.168.2.21:5555 install -r app/build/outputs/apk/release/app-release.apk` (debug-keystore signed; coexists with the official app). Launch: `adb shell am start -n nl.kpn.slim/.MainActivity`.
- **`android/gradle.properties` is git-ignored** (JDK path, and optionally an account + deviceId seed). On a fresh clone copy `gradle.properties.example` to `gradle.properties` and set the JDK path. The `kpn.account` / `kpn.deviceId` seed is optional: without it the app shows a one-time sign-in (see Sign-in below). With it, a fresh install skips the sign-in using that already registered pair; stored values in the app's preferences win over the seed afterwards. Also ignored: `capture/` (tokens, WireGuard keys), `jadx-out/`, `libs/`, the `.apkm`, `tools/*.jar`, `my-tv.jpg`.
- The web player at tv.kpn.com is a useful reference for URL patterns (its `app.js` is readable); try it before a recapture.
- Account number and deviceId are redacted in FINDINGS.md, API_CONTRACT.md and this file (session 5), and in the project memory. Keep them out of every markdown file and commit. Locally they live only in the app's private preferences and, optionally, the git-ignored `android/gradle.properties`.
- Needs for the artwork script only: `rsvg-convert` (`brew install librsvg`), ImageMagick `magick`, the Inter font.

### Code map (`android/app/src/main/java/nl/kpn/slim/`)
- `Identity.kt`: `Identity` (account + deviceId) and `IdentityStore` (SharedPreferences `slim`: `account`, `deviceId`; falls back to the optional BuildConfig seed; `newDeviceId()` makes a random 64-hex SHA-256 id).
- `AvsClient.kt`: EAR, SESSIONS, LIVECHANNELS, VIDEOURL, EPG, and `register(pin)` for the one-time sign-in. A refused session throws `AuthException`. `HttpURLConnection` + `org.json`, cookie/xsrf rotation, 3 h session refresh, retry once. `Program` carries `pictureId`, description, genres, cast, year, age rating, `startOver` flag.
- `Epg.kt`: in-memory guide. `now/next(channelId, at)`, refresh every 15 min, merges refreshes and keeps programmes that ended up to 6 h ago.
- `MainActivity.kt`: everything UI. ExoPlayer DASH + Widevine, zapping, overlays (list, options menu, info panel, settings, banner, sign-in panel, hint pill), pause, start over, stream info. Start-up picks the identity (`IdentityStore.load`): stored or seeded identity loads channels, none shows the sign-in.
- `ChannelAdapter.kt`: channel list, first row is "Settings" with the app icon (`list_icon.png`). The icon follows the logos setting so the label always lines up with the channel names: logos on, the icon sits in the 80dp logo column; logos off (channel rows then have no logo column), it takes the 48dp number column, left-aligned, with no end margin. `LogoLoader.kt`: image loader for logos and programme artwork (byte-capped 12 MB LRU, 4 parallel downloads, hides the view when there is no image).
- Layout: `res/layout/activity_main.xml` holds all overlays (sign-in `login`, hint pill `hint`, and the rest). `item_channel.xml` / `item_settings.xml` are the list rows. Colours in `res/values/colors.xml`, field style `LoginField` in `themes.xml`.
- `art/make_art.py`: generates the icon, banner and round icons (see Artwork).

### Sign-in (session 5)
- A fresh install has no identity, so `MainActivity` shows a sign-in panel (Abonnementsnummer and Pincode, number keyboards). The app generates its own random deviceId, posts `credentialsStdAuth{username: abonnementsnummer, password: pincode, remember, deviceInfo}` to `USER/SESSIONS` once (the same call as the KPN web player's `loginWithTan(tan, pin)`, from its public `app.js`; the web player also invents its own deviceId, a SHA-256 over random input) and stores account number + deviceId. The pincode is never stored. From then on sessions are minted from the deviceId alone, as before.
- Only the subscription number + pincode login is built (no e-mail / KPN ID login). The account number is read back from `profileData.username` in the reply.
- If KPN refuses the stored deviceId at start-up (`AuthException`), the sign-in panel comes back with a message and registers a new deviceId.
- `deviceInfo` now comes from the TV (`Build.DEVICE`, `MANUFACTURER`, `ID`), identical to the old hardcoded values on the Philips.
- **Verified on the TV**: the panel renders with number keypads and the keyboard fits above it; the user signed in with abonnementsnummer + pincode and KPN accepted a self-made deviceId with `deviceType androidtv` and the TV's `Build` values; afterwards builds without any seed start straight into live TV from the stored identity, also after `install -r`. The seeded path (an already registered pair from `gradle.properties`) also still works.
- **Still open**: whether KPN limits the number of registered devices per account (every `pm clear` plus sign-in registers another one), and cleaning up old ones. The web player has `USER/DEVICES` (GET list, DELETE one) for that; not implemented or tried. The TV now runs on its self-registered identity, not the pair that the official app registered.
- To exercise it again: build with `-Pkpn.account= -Pkpn.deviceId=`, install, then `adb shell pm clear nl.kpn.slim` (stored identity wins over the seed, so the data must be cleared), launch. A wrong pincode shows KPN's message in red under the fields.
- No sign-out yet. To switch account, clear the app data.

### Artwork (session 5)
- Source of truth is `android/art/make_art.py` (needs `rsvg-convert` and the Inter font). Run `python3 art/make_art.py` from `android/`: it rewrites the adaptive icon vectors (`drawable/ic_foreground.xml`, `ic_background.xml`, `mipmap-anydpi-v26/ic_launcher*.xml`), the PNG fallbacks (`mipmap-*/ic_launcher*.png`), the round icons drawn by the app itself (`drawable-xhdpi/hint_icon.png` 60 px for the hint pill, `list_icon.png` 80 px for the Settings row), the TV banner (`drawable-xhdpi/banner.png`, 640x360 = 320x180 dp) and `art/preview.png`. Edit the constants at the top (colours, mark geometry) instead of the generated files.
- Look: rounded play triangle plus two broadcast arcs in KPN green on a deep green glow; banner adds a faint ring motif and the "KPN Slim / LIVE TV" wordmark.
- **The "Press back again to exit" message is no longer a system toast.** On this TV the system toast showed the stock Android robot instead of the app icon. Cause (from logcat): SystemUI looks for the APK in the install folder of an earlier install (`/data/app/~~…/nl.kpn.slim-…/base.apk` no longer exists after each `adb install -r`), cannot open it and falls back to the default icon. It is not an icon-format problem (adaptive plus PNG fallbacks made no difference) and probably clears on a TV reboot (not tried). All hints (`hint()`) are now a pill drawn by the app with its own round icon, so they look the same regardless of SystemUI.

### Remote control
- Up/Down or CH+/CH-: zap. Digits: channel number. Last channel: previous channel.
- **OK: options menu** (Pause/Play, Start from beginning, Go to live when behind, Programme info, Channel list, Settings).
- Right or Info: show the bottom banner; press again while it shows to open the options menu.
- Menu, Left, Guide: channel list (its first row, with the app icon, opens Settings).
- Play/Pause, Pause, Play media keys (and Space): pause. Red: start over (also inside the info panel). Green: programme info.
- Fast forward / Rewind media keys (also skip/step keys): seek. 10 s, and quick repeats (within 1.5 s) step up 30 s, 1 min, 2 min, 5 min. Forward past the live edge lands on live.
- Stop: back to live (instant seek to the live edge on a plain live stream, re-tune after start over). When already live it only says "You are watching live TV".
- **Leave-guard**: while not live (paused, behind live, or started over: `nonLive()`), every action that leaves the current stream needs the same key/action twice within 2.5 s, otherwise it only shows a hint ("Not live. Press again to change channel"). Implemented once in `guard(key, msg, action)`. Guarded: channel up/down and CH+/-, last channel, typed channel number (type it again), picking a channel in the list, start from beginning (red, options row, info button), Go to live (Stop, options row), the High bandwidth setting (restarts the stream). Key auto-repeat never confirms. While live nothing is guarded. Not guarded on purpose: fast forward and rewind (they stay in the same stream; forward past the live edge just lands on live), pause/play, and the initial tune on app start. Exit via Back is always a double press.
- Back: close the open overlay; with no overlay the first press shows a "Press back again to exit" hint (pill with the app icon, 2 s) and a second press within 2 s exits the app. While the sign-in panel is up, every key belongs to the form except Back, which keeps the double press to exit.
- The TV's system intercepts the Settings key, so it is not used.

### Features
- **EPG now/next**: one `GET /TRAY/EPG?filter_startTime={now}&filter_endTime={now+3h}&filter_channelIds={all}&outputFormat=EXTENDED` (about 400 KB, 0.4 s) for all 108 channels. List rows show the current programme.
- **Bottom banner** (860dp wide, rounded, floating above the bottom edge): channel number and name, live video stream info, clock, programme title, time range, "Volgend: next", progress bar. Stream info is dim; any piece that changes during the same stream (adaptive quality switch) glows green and fades over 2.5 s. A new stream starts without a glow (`lastParts` is reset right before `setMediaItem`).
- **Start from beginning**: no extra endpoint. `VIDEOURL/LIVE/{ch}/{asset}?time={programStartMs}` (exact ms, not rounded) returns a manifest that begins at that moment (URL carries `&t=<ISO>`). The window reaches back about 6 h (tested 10 min to 3 days, older is clamped). Played with `setMediaItem(item, 0)`. Only offered when the programme has `contentOptions: STARTOVER`. While not live: banner and info panel describe the programme being played (`timeshiftBase + currentPosition`), the clock shows `(-N min)`, "Go to live" appears. "Behind live" is measured against ExoPlayer's target live offset, so plain live never counts as behind.
- **Pause**: pause icon top right with the time behind live next to it (`-0:14`, updated twice a second). Live pause works because the dynamic manifest has a long window. The banner clock also shows `(-28 s)` / `(-N min)` when behind.
- **Stream info fade**: the video info in the banner fades in over 0.7 s when it first appears for a new stream (not on later quality changes, those glow instead).
- **Buffering spinner**: ExoPlayer's own one is off; a green spinner appears only after 1 s of continuous tuning/buffering (`armLoader`, `LOADER_DELAY_MS`), so quick zaps stay clean.
- **Programme info panel**: a "Start from beginning" button at the bottom (only when the programme allows it, focused by default; Up moves focus to the text, which scrolls). Title, season/episode, episode title, channel, time and length, genres, year, age rating, description, presenters/directors/cast.
- **Settings** (SharedPreferences `slim`): "High bandwidth start" (default Off) gives ExoPlayer a 20 Mbps initial bandwidth estimate so playback starts at 1080p50 / 8 Mbps instead of ramping up from 768x432. Only matters on a cold start, because the shared bandwidth meter remembers its estimate afterwards. "Show channel logos" (default On; list rows and banner). "Info banner duration" 1-15 s (default 5). Toggling the first one rebuilds the player and re-tunes.
- The TV currently has High bandwidth start **On**, Show channel logos **Off** and banner **9 s** (the user's own choices; the logos-off layout is the one the Settings row alignment was tuned on, and both modes were checked).

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
- `adb exec-out screencap -p` takes about 3 s, so keep loops short or run them in the background. Crop with `magick`. A 2 s hint is easy to miss: fire the key and the screenshot in the same command and capture right away.
- `adb install -r` keeps the app's data (stored identity, settings); `pm clear` wipes it. After a clear the settings are back to defaults, so note the user's values first (this session they were High bandwidth On, logos Off, banner 9 s) and put them back after testing.
- The system toast shows the default Android icon after `adb install -r` on this TV (SystemUI keeps the old APK path); do not chase that again, the app uses its own hint pill. `am force-stop com.android.systemui` is silently ignored for the shell user.
- Once during session 5 the app was simply gone from the TV (cause unknown, not an uninstall by us). If `am start` says the activity does not exist, reinstall; the seed or the sign-in restores the identity.
- Press keys one at a time and screenshot; the banner hides itself after the configured duration.

### Known gaps / next ideas
- Audio and subtitle track picker (manifest has a second Dutch audio track and a Dutch subtitle stream; ExoPlayer picks defaults).
- Catch-up of earlier programmes and a full EPG grid (the `TRAY/EPG` endpoint takes any time range; start-over proves play URLs accept past times).
- Artwork in the channel list rows (small programme thumbnail) or a grid-style EPG.
- Recordings/CPVR, out-of-home, trick-play thumbnails while scrubbing (no seeking UI yet).
- Sign-in works (see Sign-in). Missing: a sign-out / switch account entry in Settings, a device limit check or cleanup via `USER/DEVICES`, and handling an `AuthException` that happens mid-session (it only shows the player error; restarting the app brings up the sign-in).
- Long pause: if the manifest token expires the player errors once and the app re-tunes live (not resumed at the paused position).

## (Earlier) RE status: COMPLETE and validated.

- Full report: **`FINDINGS.md`**. Exact HTTP contract: **`API_CONTRACT.md`**.
- The live-TV API chain (EAR → deviceId session → channel list → DASH+Widevine play URL) was captured and **re-validated from plain `curl` on macOS**. Nothing else is blocking the build.
- TV was returned to stock after the capture: official KPN app reinstalled, WireGuard tunnel down, proxy cleared (the official app's data was wiped, so it would ask for its own login; our app does not depend on it).

## What's in this folder now (after cleanup)

| Path | Keep? | Note |
|---|---|---|
| `android/` | ✅ | the app (Gradle project); `android/art/` has the artwork generator and `preview.png` |
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
- Account `tan` and the deviceId are redacted here. The app keeps its own pair in its private preferences (made at the one-time sign-in); an optional seed pair can go in the git-ignored `android/gradle.properties` (see `gradle.properties.example`).
- Auth = **deviceId only** on `POST /USER/SESSIONS` once a device is registered; the first call of a new device carries abonnementsnummer + pincode (API_CONTRACT.md section 2b). AVS host discovered via EAR. Base path `/000002/1.5/A/nld/androidtv/kpn/`. Headers: cookies (`sessionId`,`up`,`avs6_cookie`) + `x-xsrf-token` + `avssite: http://www.itvonline.nl`.
- Play: `GET /CONTENT/VIDEOURL/LIVE/{channelId}/{assetId}?deviceId=..&profile=G09&time={nowMs}` → DASH `.mpd` + Widevine `licenseAcquisitionURL`.

## Original build plan (all done)

Native **Android, Kotlin, Media3/ExoPlayer**. Target < 10 MB, low memory (met: about 1.2 MB). No Flutter, Firebase, ads, consent, recordings, VOD, cast. As built: a plain `HttpURLConnection` AVS client (no OkHttp/Retrofit), a `RecyclerView` channel list overlay (no leanback browse UI), channel logos from `images.tv.kpn.com/logo/{externalId}/{height}.png`, ExoPlayer DASH with Widevine, zapping, EPG now/next.

### Resolved decisions
- **deviceId handling** (session 5): the app makes its own random deviceId and registers it with a one-time sign-in, like the KPN web player does. KPN's own serial-based derivation is not reproduced and not needed. An optional gradle seed keeps an already registered pair working without a sign-in.
- **EPG**: shipped in v0.2 (now/next), still in use.
- **Project location**: settled as `android/` in this repo.

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
