# KPN TV+ — Live TV API contract (reverse-engineered, validated 2026-11-09)

App: `com.kpn.kpnandroidtv` v09.06.00 (Flutter, Dart pkg `pca_app`). Backend = Nagra/MediaKind **AVS** (Agile Video Service), API version "avs67".
Captured via WireGuard-mode mitmproxy + reFlutter SSL-bypass build on a Philips Android TV. **Whole chain re-validated from a plain HTTP client (curl on macOS)** — no app/device needed beyond a registered `deviceId`.

## Key fact: auth is deviceId-only
`POST /USER/SESSIONS` takes **only a `deviceId`** (no password/OAuth/Firebase token in the body). The device is pre-registered to the account during the one-time KPN login/pairing. Once registered, sessions mint from deviceId alone. `isDevRegistered:false` in the response does not block live playback.

- Account number (`tan`): `20000010487262`
- This TV's `deviceId` (SHA-256, 64 hex): `131837EB582CC64E8510763B027C5976E795F9F88096036DD5B1D2D1644A46C8`
- Derivation (from binary): serial-based → SHA-256 ("serialBasedDeviceId", "deviceId migrated to full-length SHA-256").

## Hosts
| Host | Role |
|---|---|
| `ausar.tcloud-itv-prd1.prod.aws.kpn.com` | EAR: account → AVS host discovery |
| `api-avs67.tv.prod.itvavs.prod.aws.kpn.com` | AVS REST API |
| `images.tv.kpn.com` | channel logos / artwork (prefix for relative logo paths) |
| `*.video.kpnstreaming.nl` | DASH streaming CDN (edge servers) |
| `drm-proxy.streaming.kpn.com` | Widevine license server |

## Path prefix
`/{retailerID}/1.5/A/{lang}/{platform}/{operator}/...`
Observed: `/000002/1.5/A/nld/androidtv/kpn/` (retailerID `000002` from profile; `nld`; `androidtv`; `kpn`). Every request also carries `&0_0=09.06.00` (app version).

## Common headers
Request: `avssite: http://www.itvonline.nl`, `content-type: application/json`, `user-agent: Dart/3.9 (dart:io)`.
After session: `cookie: sessionId=<uuid>;up=<JWT>;avs6_cookie=<JWT>` + `x-xsrf-token: <token>`.

## 1. EAR — discover AVS host
```
GET https://ausar.tcloud-itv-prd1.prod.aws.kpn.com/public/v1/ear?type=ott&tan={account}&0_0=09.06.00
→ {"result":{"url":"api-avs67.tv.prod.itvavs.prod.aws.kpn.com"},"code":"Ok"}
```

## 2. Create session (deviceId-only)
```
POST https://{avsHost}/000002/1.5/A/nld/androidtv/kpn/USER/SESSIONS/?0_0=09.06.00
body:
{"credentialsStdAuth":{"deviceInfo":{
  "deviceId":"<SHA256>","deviceIdType":"DEVICEID","deviceType":"androidtv",
  "deviceModel":"PH9M_EA_5599","deviceVendor":"Philips",
  "swVersionId":"STT2.230526.001","deviceFirmVersion":"STT2.230526.001",
  "appVersion":"09.06.00","serialNumber":"NotAvailable","ipAddress":"127.0.0.1"}}}
→ 200, Set-Cookie: sessionId, up (JWT, Max-Age 1e6s), avs6_cookie (JWT, Max-Age 14400s=4h)
   Header: X-Xsrf-Token, Avs-Refresh-Token. Body = full user profile (resultObj.profile.profileData).
```
`up` JWT payload: userId, username(=account), userOptions [NPVR_VIEW, CATCHUP, STARTOVER, OOH_LIVE, OOH_VOD]. `avs6_cookie` is the AVS session JWT (iss "AVS").

## 3. Live channel list
```
GET /TRAY/LIVECHANNELS?orderBy=orderId&sortOrder=asc&from=0&to=999&dfilter_channels=subscription&filter_excludedGenres=Erotiek&filter_isAdult=false&0_0=09.06.00
→ resultObj.total (108), resultObj.containers[]:
   container.id                     "18"
   container.metadata.channelId     18
   container.metadata.channelName   "NPO 1"
   container.metadata.callLetter    "NPO1"
   container.metadata.orderId       1
   container.metadata.externalId    "dtvchannels_1"
   container.assets[0].assetId      10          <- used in VIDEOURL
   container.assets[0].videoType    "SD_DASH_WV"
   container.assets[0].logoBig/Medium/Small  "size_x/dtvchannels_1_226x314.png"  (prefix images.tv.kpn.com)
```

## 4. Get play URL (live)
```
GET /CONTENT/VIDEOURL/LIVE/{channelId}/{assetId}?deviceId={deviceId}&profile=G09&time={epochMillis}&0_0=09.06.00
→ resultObj.src.sources:
   .src       https://<edge>.video.kpnstreaming.nl/session/.../{ch}.mpd?token=...   (MPEG-DASH)
   .type      application/dash+xml
   .contentProtection.widevine.licenseAcquisitionURL  https://drm-proxy.streaming.kpn.com/drm4/gw1/...
   resultObj.token  (opaque playback token)
```
`profile=G09` ~ device/quality profile. `time` = current epoch millis (live). assetId 10 = SD_DASH_WV.

## 5. Playback / DRM
- MPEG-DASH manifest (`.mpd`) from kpnstreaming.nl CDN; URL self-authorizes via `?token=`.
- Widevine license: ExoPlayer/Media3 `MediaItem.DrmConfiguration(Widevine, licenseUri=licenseAcquisitionURL)`. License request is a standard Widevine POST (handled natively by MediaDrm). No extra header needed beyond the URL observed (license URL embeds its own auth path segments).

## Other useful endpoints (captured)
- `GET /USER/PROFILE/`
- `GET /TRAY/EPG?filter_startTime={ms}&filter_endTime={ms}&filter_channelIds={csv}&extendedChannelMetadata=true&outputFormat=EXTENDED`
- `GET /TRAY/SEARCH/PROGRAM?...filter_airingTime=now&filter_channelIds={csv}&outputFormat=EXTENDED` (now/next)
- `GET /CONTENT/DETAIL/PROGRAM/{programId}`
- `GET /CONTENT/USERDATA/LIVE/{channelId}?deviceId=...`
- `GET /USER/BOOKMARKS`, `GET /TRAY/USER/BOOKMARKS?filter_contentType=PROGRAM`

## Minimal viewer flow
EAR → SESSIONS(deviceId) → LIVECHANNELS (build grid) → on select: VIDEOURL(channelId,assetId) → ExoPlayer DASH+Widevine. EPG/now-next optional.
