# KPN TV+ Android TV — Reverse-engineering findings

Target: `com.kpn.kpnandroidtv` **v09.06.00** (versionCode 302364), "KPN TV+ Android TV".
Source artifact: `com.kpn.kpnandroidtv_09.06.00-302364_2arch_1dpi_24lang_...apkmirror.com.apkm` (APKMirror split bundle).
Date: 2026-11-09. Device used: Philips Android TV (model `TPM191E` / `PH9M_EA_5599`), Android 12, armeabi-v7a, **not rooted**, adb at `192.168.2.21:5555`.

Goal driving this work: build a **minimal, fast, low-memory native Android (Kotlin + Media3/ExoPlayer) app** that only views the live TV channels, without the bloat.

> The exact HTTP wire contract (endpoints, bodies, headers, responses) lives in **`API_CONTRACT.md`**. This file is the broader report: what the app is, how it was analysed, and what it means for the rebuild.

---

## 1. What the app is

- **Flutter app.** Manifest shows `flutterEmbedding`, `io.flutter.embedding.*`, a single real activity `com.kpn.tvplusapp.MainActivity` (leanback launcher), `android:name="android.app.Application"`. Dart package is `pca_app` (KPN's internal name = "PCA").
- Consequence: **the business logic is NOT in the decompiled Java.** It is compiled to Dart AOT in `lib/<abi>/libapp.so` (~33 MB). The jadx Java output (`jadx-out/sources/`) is only Flutter plugins, Firebase, and obfuscated libraries.
- Heavy third-party stack (all strippable for our goal): Firebase (Auth, Firestore, Crashlytics, Messaging, Remote Config, Performance, App Check, In-App Messaging), Google IMA + FreeWheel ads, OneTrust consent, Cast, in-app WebView, recordings/NPVR, VOD, partner shop, app badges, biometric/secure-device checks.
- Anti-tamper present but weak in practice: `scottyab` RootBeer, `flutter_security_checker`, `safe_device`, `libtoolChecker.so`, Firebase App Check — **but App Check runs as a placeholder** (`No AppCheckProvider installed`), and a re-signed/debuggable build ran and played fine.

### Backend
Nagra/MediaKind **AVS** (Agile Video Service), API generation "avs67". Classic AVS REST (`/USER`, `/TRAY`, `/CONTENT`). Streaming is **MPEG-DASH** with **Widevine** DRM.

| Host | Role |
|---|---|
| `ausar.tcloud-itv-prd1.prod.aws.kpn.com` | EAR — maps account → AVS host |
| `api-avs67.tv.prod.itvavs.prod.aws.kpn.com` | AVS REST API |
| `images.tv.kpn.com` | channel logos / artwork |
| `*.video.kpnstreaming.nl` | DASH streaming CDN |
| `drm-proxy.streaming.kpn.com` | Widevine license server |
| `inloggen.kpn.com` | KPN OAuth (one-time device pairing only) |

---

## 2. How it was analysed (methodology)

1. **Unpack** the `.apkm` (zip of split APKs): `base.apk` + per-ABI/dpi/lang splits.
2. **Decompile** `base.apk` with jadx → confirmed Flutter, mapped plugins via `GeneratedPluginRegistrant`.
3. **Mine `libapp.so`** (Dart AOT) string table → recovered endpoint paths, hosts, DRM keywords. This gave the *shape* (endpoints, params) but not request/response bodies.
4. **On-device data read.** Rebuilt a `debuggable=true` universal APK (APKEditor merge + manifest edit + re-sign), installed, used `adb run-as` to read the app's files. Got Firebase Remote Config (feature flags, ad tags), `FlutterSharedPreferences` (channel IDs are small ints, account id, flavor), but the session is AES-encrypted at rest (Keystore-sealed) and the channel list / play URLs are not persisted.
5. **Live TLS capture (the breakthrough).** Flutter/Dart ignores the Android system proxy *and* system CA store, so an ordinary MITM sees nothing. Solution:
   - **reFlutter** patch of `libflutter.so` → disables Dart TLS cert verification.
   - Merge splits → universal APK (APKEditor) so reFlutter can patch + we install one APK; added `debuggable=true`; re-signed with debug keystore.
   - **mitmproxy in WireGuard mode** (`mitmdump --mode wireguard --allow-hosts 'aws\.kpn\.com'`); the TV runs the stock **WireGuard** app as a client (config in `capture/kpn-wg.conf`) → transparent redirect of *all* TV traffic, including Dart's direct connections. `--allow-hosts` MITMs only the AVS hosts and tunnels Firebase/Google/login untouched (so login keeps working).
   - Reinstalled the capture build over the data build with `-r` (same debug key) → **login preserved**.
6. **Independent validation.** Replayed the whole flow from plain `curl` on macOS using only the device ID — session, 108-channel list, and a live DASH+Widevine play URL all succeeded. Proves the minimal app needs nothing but this contract.

### Tooling
`jadx` 1.5.3, `APKEditor` 1.4.3, `reflutter` (pipx), `mitmproxy` 12.3.1 (pipx), Android build-tools 36 (`zipalign`/`apksigner`), platform-tools `adb`. Artifacts under `tools/` and `capture/`.

---

## 3. The streaming / DRM model

- **Container/transport:** MPEG-DASH (`.mpd`) served from `*.video.kpnstreaming.nl` edge servers. The manifest URL self-authorizes via a `?token=` query param returned by the VIDEOURL call.
- **DRM:** Widevine. `VIDEOURL` returns `contentProtection.widevine.licenseAcquisitionURL` (`drm-proxy.streaming.kpn.com/drm4/...`). Native ExoPlayer/MediaDrm performs the license exchange; no special headers beyond the URL were required.
- **Quality/asset:** each channel exposes `assets[]`; live asset `assetId=10`, `videoType=SD_DASH_WV`. The asset id is a path segment in VIDEOURL.
- The official app's standard Flutter `video_player` is backed by ExoPlayer/Media3, which does Widevine natively — a native Kotlin app uses the same engine directly via `MediaItem.DrmConfiguration`.

---

## 4. The auth model (key simplification)

- One-time registration of a device. The official TV app does it through KPN OAuth (`inloggen.kpn.com`) + a Firebase custom token (`kpn_<id>`); we did not capture or reproduce that. The KPN **web player** shows a simpler way that our app uses: log in once with abonnementsnummer + pincode (`loginWithTan(tan, pin)` in `https://tv.kpn.com/app.js`), sending a deviceId the client made up itself (the web player hashes a random number, the username and a timestamp with SHA-256).
- Steady state: **`POST /USER/SESSIONS` authenticates with the `deviceId` alone** (a 64-hex SHA-256 style id; the official TV app derives it from the serial, we never needed that). No password, OAuth, or Firebase token in the request body. The response sets `sessionId` / `up` / `avs6_cookie` cookies + `x-xsrf-token`, which authorize all subsequent calls.
- The account number is used in the EAR host-discovery call and as the sign-in username.

For the minimal app: **verified on the Philips (session 5)**. The app makes its own random deviceId, the user signs in once with abonnementsnummer + pincode, KPN registers that deviceId (with `deviceType androidtv` and the TV's own `Build` values), and from then on sessions work from the deviceId alone. The pincode is never stored. Not reproducing KPN's serial-based derivation was deliberate. Open: a possible per-account device limit; the web player has `USER/DEVICES` (list, delete).

---

## 5. Request chain for a minimal live-TV viewer

```
1. GET  ausar .../public/v1/ear?type=ott&tan={account}            → AVS host
2. POST {avs}/000002/1.5/A/nld/androidtv/kpn/USER/SESSIONS/        → cookies + x-xsrf-token   (body: deviceInfo{deviceId,...})
3. GET  {avs}/.../TRAY/LIVECHANNELS?...from=0&to=999...            → containers[] (channelId, name, logo, assets[].assetId)
4. GET  {avs}/.../CONTENT/VIDEOURL/LIVE/{channelId}/{assetId}?deviceId=..&profile=G09&time={nowMs}
                                                                   → DASH .mpd url + widevine licenseAcquisitionURL + token
5. ExoPlayer/Media3: MediaItem(dash url) + DrmConfiguration(Widevine, licenseUri)
   (optional) EPG now/next from /TRAY/SEARCH/PROGRAM or /TRAY/EPG
```
Full headers/bodies/examples: see `API_CONTRACT.md`.

---

## 6. Recommended build for the minimal app

- **Native Android, Kotlin, Media3/ExoPlayer.** Drops Flutter engine (`libflutter.so` ~11 MB + `libapp.so` ~33 MB + Dart heap). APK target < 10 MB, far lower idle memory.
- **UI:** leanback `BrowseFragment` or a simple `RecyclerView` channel grid (logos from `images.tv.kpn.com`), + a fullscreen player with up/down channel zapping.
- **Networking:** OkHttp/Retrofit (or ktor) + a cookie jar; one small AVS client implementing steps 1–4.
- **Player:** Media3 `ExoPlayer`, `DashMediaSource`, `DefaultDrmSessionManager` (Widevine) with `licenseUri` from the VIDEOURL response.
- **Strip everything else:** no Firebase, ads, consent, recordings, VOD, cast, analytics.
- **Scope v1 (built, now at v0.8):** sign-in once + deviceId sessions + channel list + play + zap, plus EPG now/next (one `TRAY/EPG` call for all channels), start over, pause, programme info, images. See `NEXT_STEPS.md`.

---

## 7. Repo layout

| Path | What |
|---|---|
| `extracted/` | unpacked `base.apk`, arm64 split, `libapp.so` |
| `jadx-out/` | jadx decompilation (Java side; mostly plugins/libs) |
| `libs/libapp.so` | Dart AOT snapshot (string-mined) |
| `tools/` | APKEditor jar, universal/merged/patched/signed APK builds |
| `capture/` | mitmproxy flows + dumps, WireGuard config, validated `channels.json`/session files |
| `android/` | the native app (Kotlin + Media3), `android/art/` the icon and banner generator |
| `NEXT_STEPS.md` | project state, code map, remote keys, gotchas, ideas (read first when resuming) |
| `API_CONTRACT.md` | exact HTTP contract |
| `FINDINGS.md` | this report |

---

## 8. Caveats / legal

- This documents interoperation with a service the account holder subscribes to, for a personal minimal client. It does **not** bypass DRM (Widevine still required) or circumvent authentication: the app signs in with the account holder's own abonnementsnummer and pincode once, and uses the resulting device-based session.
- The account number and deviceId are deliberately not written in these docs.
- Tokens/cookies captured are short-lived (`avs6_cookie` ~4 h) and account-specific; the DASH manifest URLs are time- and token-limited.
- The capture build (reFlutter + debuggable, debug-signed) is for analysis only. The TV should be returned to the official app.
