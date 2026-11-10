# KPN TV+: live TV API contract (as used by this app, validated 2026-11-09)

Backend = Nagra/MediaKind **AVS** (Agile Video Service), API version "avs67". Much of this matches the public KPN web player (`https://tv.kpn.com/app.js`).
The whole chain was validated from a plain HTTP client (curl on macOS) and from this app on a Philips Android TV. It needs a valid KPN TV+ subscription: every call runs in the subscriber's own session.

## Key fact: auth is deviceId-only once the device is registered
`POST /USER/SESSIONS` takes **only a `deviceId`** (no password/OAuth/Firebase token in the body) for a registered device. A device gets registered by its first sign-in with abonnementsnummer + pincode (section 2b), or by the official app's own pairing. Once registered, sessions mint from deviceId alone. `isDevRegistered:false` in the response does not block live playback.

- Account number (`tan`): not written here; the app keeps it in its private preferences (optional seed: git-ignored `gradle.properties`)
- `deviceId` (SHA-256 style, 64 hex): not written here; made by the app at the first sign-in
- The web player makes up its own deviceId (SHA-256 over a random number, the username and a timestamp) and it is registered at the first sign-in. This app does the same with a random one.

## Hosts
| Host | Role |
|---|---|
| `ausar.tcloud-itv-prd1.prod.aws.kpn.com` | EAR: account → AVS host discovery |
| `api-avs67.tv.prod.itvavs.prod.aws.kpn.com` | AVS REST API |
| `images.tv.kpn.com` | artwork: `/logo/{externalId}/{height}.png`, `/epg/{pictureUrl}/{w}x{h}.jpg?blurred=false` |
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
  "deviceModel":"<Build.DEVICE>","deviceVendor":"<Build.MANUFACTURER>",
  "swVersionId":"<Build.ID>","deviceFirmVersion":"<Build.ID>",
  "appVersion":"09.06.00","serialNumber":"NotAvailable","ipAddress":"127.0.0.1"}}}
→ 200, Set-Cookie: sessionId, up (JWT, Max-Age 1e6s), avs6_cookie (JWT, Max-Age 14400s=4h)
   Header: X-Xsrf-Token, Avs-Refresh-Token. Body = full user profile (resultObj.profile.profileData).
```
`up` JWT payload: userId, username(=account), userOptions [NPVR_VIEW, CATCHUP, STARTOVER, OOH_LIVE, OOH_VOD]. `avs6_cookie` is the AVS session JWT (iss "AVS").

### 2b. First sign-in (registers a new deviceId)
Taken from the KPN web player (`https://tv.kpn.com/app.js`, `loginWithTan(tan, pin)`). **Verified from our Android app on the Philips**: with a self-made deviceId and the TV's `Build` values as `deviceInfo` the server registered the device, and later deviceId-only sessions work.
```
POST /USER/SESSIONS/    (same URL as above)
{"credentialsStdAuth":{"username":"<abonnementsnummer>","password":"<pincode>","remember":true,
  "deviceInfo":{ ...same fields as in section 2... }}}
```
The reply is the same as a deviceId-only session; `resultObj.profile.profileData.username` is the account number. (The web player also has an e-mail / KPN ID login, `loginWithKpnId`, which looks up the AVS host with a POST to the EAR; not implemented.) Related web endpoints: `GET/DELETE /USER/DEVICES/` (list and remove registered devices).

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
   container.assets[0].logoBig/Medium/Small  "size_x/dtvchannels_1_226x314.png"  (dead paths, 404; use /logo/{externalId}/{height}.png on images.tv.kpn.com)
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

## 4b. Catch-up (finished programmes)
From the KPN web player (`getVideoUrl`) and verified on the Philips. The start-over stream above is clamped to about 6 h back (a request for 3 or 6 days ago returns the same manifest as 6 h ago), so older programmes use the recording:
```
GET /CONTENT/DETAIL/PROGRAM/{programId}
→ resultObj.containers[0].assets[]: {assetId, programType "SOTV" | "CUTV", videoType "SD_DASH_WV", ...}
GET /CONTENT/VIDEOURL/PROGRAM/{programId}/{CUTV assetId}?deviceId=..&profile=G09
→ same shape as the live reply (DASH + Widevine)
```
`programId` is `metadata.contentId` of the EPG item; it needs `contentOptions` to contain `CATCHUP`. The `CUTV` manifest is `type="static"` and seekable. Its length is always the programme length plus 20 min (35 min programme: 55 min, 60 min: 1 h 20), the split before and after is not given. The `SOTV` asset gives a dynamic start-over style manifest. Worked 3 days back; the archive is about a week deep.

## 5. Playback / DRM
- MPEG-DASH manifest (`.mpd`) from kpnstreaming.nl CDN; URL self-authorizes via `?token=`.
- Widevine license: ExoPlayer/Media3 `MediaItem.DrmConfiguration(Widevine, licenseUri=licenseAcquisitionURL)`. License request is a standard Widevine POST (handled natively by MediaDrm). No extra header needed beyond the URL observed (license URL embeds its own auth path segments).

## Other useful endpoints
- `GET /USER/PROFILE/`
- `GET /TRAY/EPG?filter_startTime={ms}&filter_endTime={ms}&filter_channelIds={csv}&extendedChannelMetadata=true&outputFormat=EXTENDED` (any range, past included; returns every programme overlapping it, so one that straddles the start is included; 12 channels x 3 h is about 59 KB)
- `GET /TRAY/SEARCH/PROGRAM?...filter_airingTime=now&filter_channelIds={csv}&outputFormat=EXTENDED` (now/next)
- `GET /CONTENT/DETAIL/PROGRAM/{programId}`
- `GET /CONTENT/USERDATA/LIVE/{channelId}?deviceId=...`
- `GET /USER/BOOKMARKS`, `GET /TRAY/USER/BOOKMARKS?filter_contentType=PROGRAM`

## Minimal viewer flow
EAR → SESSIONS(deviceId) → LIVECHANNELS (build grid) → on select: VIDEOURL(channelId,assetId) → ExoPlayer DASH+Widevine. EPG/now-next optional.
