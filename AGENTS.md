# Sohva Remote contributor instructions

Sohva Remote (package `com.mothandgravel.sohva`, published by Moth & Gravel) is an Android phone app that
replaces the physical remote of a Sony Bravia TV (target: KD-65XF9005, 2018 Android TV) over the
local network. Keep changes small, keep key presses fast, and never add a backend.

It is headed for Google Play: the name and listing never use "Sony" or "Bravia" as our brand
(descriptive "for Sony Bravia TVs" only, plus a not-affiliated line), the Kotlin package is
`com.mothandgravel.sohva` (the same as `applicationId`), and `applicationId` must never change
again once the app is on Play (it changed once, before the first release).
Play only allows `USE_EXACT_ALARM` for alarm/calendar apps, so the app uses the user-granted
`SCHEDULE_EXACT_ALARM` and shows `ExactAlarmNotice` until it is allowed.

`site/` is the source for mothandgravel.com/sohva/ (hosted by the studio site) and is not deployed from this repo.

## Architecture

- `protocol` is pure Kotlin with no Android imports: `BraviaApi` (JSON-RPC "Scalar
  Web API" + IRCC-IP), `PinPairing`, `SsdpDiscovery`, `WakeOnLan`, `RemoteKey`, and
  the Android TV Remote pieces `AtvPairing`, `AtvWire`, `AtvTls`.
  Everything here is unit-tested against `FakeBraviaTv` (MockWebServer) and `FakeAtvTv` (TLS).
- `data` owns Android facts: `TvController` (the one live session), `TvStore`
  (DataStore), `CredentialStore` (EncryptedSharedPreferences), `AndroidDiscovery`
  (multicast lock).
- `ui` owns Compose screens and Hilt view models. Screens read state from
  `TvController` through a view model; they never touch `BraviaApi` directly.
- Key presses go through `TvController.press`, which queues them in order and sends
  on touch-down. Do not add work to that path that waits on the TV's answer.

## Protocol rules

- The TV speaks plain HTTP on the LAN. Cleartext is allowed app-wide because Android
  cannot scope it by IP range; do not add any non-LAN network call.
- Prefer the TV's own IRCC code table (`system.getRemoteControllerInfo`) over the
  defaults in `RemoteKey`; the defaults are community-documented fallbacks.
- Treat JSON-RPC error 7 and 40005 as "TV in standby", HTTP 401/403 and error 403 as
  "pair again". Never retry a failed key press automatically. Reads may retry: connect asks the
  power state once more before "Can't reach", re-reads what's playing for a few seconds after
  connecting (a just-woken TV reports nothing while live TV starts), and the visible remote asks the
  TV's power every 5 s and reconnects when it differs from the card (`TvController.watchPower`), so the
  TV's own remote switching it on or off shows up.
- All HTTP goes through `BraviaApi.execute` / `Call.awaitBuffered`, which read the whole
  response on OkHttp's thread. Never touch an OkHttp `Response` after a coroutine resumes:
  view models run on the main thread, where a socket read throws
  `NetworkOnMainThreadException` and kills the app.
- After some idle minutes the TV unbinds its IRCC key service and drops the next key while
  it restarts. `BraviaApi.warmUpIrcc` sends a code that maps to no key first (the TV answers
  it with HTTP 500, which is expected); keep it, and keep `TvController.warmUpKeys` on connect
  and resume so the first press normally doesn't wait.
- With an HDMI-CEC soundbar each volume key moves the level two steps; that is the TV and
  soundbar (a native key on the TV does the same), not the app.
- With the soundbar, `setAudioVolume` also moves one step (2) toward the target whatever value is
  asked, and the new level is reported 350-600 ms later: set a level with
  `TvController.stepVolumeTo` (repeat and read back). Any step unmutes; in standby (and from the
  moment the off command lands) volume changes are refused; mute is cleared when the TV wakes; for
  some seconds after waking the TV answers slowly (4 s timeouts). So the sleep fade cannot put the
  volume back before standby: it remembers the level (`TvStore.volumeRestore`) and restores it the
  next time the app sees the TV on, only if the volume is still at the faded level.
- "Fast keys" use the TV's Android TV Remote Service (TLS, ports 6467 pairing / 6466 keys).
  The phone's identity is a Keystore RSA key (`AtvIdentity`). Conscrypt signs the TLS 1.2
  handshake with raw RSA, so that key must allow `ENCRYPTION_PADDING_NONE` and `DIGEST_NONE`;
  without them the handshake fails with `INCOMPATIBLE_PADDING_MODE`. The TV tells paired clients
  apart by certificate subject and serial number, so each install's are random; up to 1.5.1 all
  installs shared one, and pairing a second device replaced the first one's pairing. The TV's
  certificate from pairing is kept in `CredentialStore` and marks the TV as paired.
- `TvController` keeps the fast key channel (`AtvRemote`) open only while the remote screen is
  in the foreground and the TV is on. Every key still goes through the one send queue; the
  transport is picked when its turn comes (`RemoteKey.atvKeyCode` non-null and channel open),
  and a key handed to the channel is never resent over IRCC.
- Move a key to the channel only after seeing it arrive on the TV: GUIDE (172) arrives as
  KEYCODE_UNKNOWN on the KD-65XF9005 even though IRCC Guide maps to GUIDE there.
- The TV's first ping comes about 9 s after connecting, then every 5 s; read timeouts must allow
  for that. Close TLS sockets off the main thread (the close alert is network I/O).
- Typing on the TV (`AtvTextEdit`, probed on the KD-65XF9005): the TV sends 20 (IME counter in
  1.1, text field id/text/hint in 2), then 21 with 1 = 1 when its keyboard is up (0 when it goes).
  Each edit is 21{1: newest counter, 2: newest field id, 3:{1:1, 2:{1:0, 2:0, 3: one character}}};
  the TV echoes the field in 22 with a new id, and the next edit must wait for it. A stale id or
  several characters in one edit is ignored and can make the TV drop the channel. Backspace and
  Enter are keys 67 and 66; letters as key codes are filtered (digits are not). Apps with their own
  on-screen keyboard (YouTube) never raise the system one, so there is nothing to type into.
- Quick Settings tiles, the widget and the sleep timer act with the app closed. They go through
  `TvController.awaitSession()` (the process may have just started) and wait for the key before
  letting the process go (`press()` returns a `Deferred`; receivers use `goAsync()`). Android
  firewalls a cached app (`APP_BACKGROUND` in `dumpsys netpolicy`) and destroys its sockets, so
  `awaitSession()` evicts OkHttp's pool; a reused socket fails with "Software caused connection abort".
- Ad break (`AdBreak`) never toggles mute: it unmutes only a mute it made and still holds
  (`AdBreakState.mutedByUs`). Unmuting by hand, a mute with the TV's own remote and a phone call
  (`CallMuteReceiver` takes the break's mute over first) all take it away. An exact alarm ends the
  break with the app closed; an end the TV doesn't answer is retried, and a break belongs to its TV.
- The sleep timer only ever turns the TV off (`setPowerStatus(false)`, then the discrete PowerOff
  key), never the toggling Power key: it must not wake a TV someone already switched off.
- Wake-up and scenes go through `TvController.ensureOn()`. A live process's state can be stale (the
  TV switched off with its own remote), so it asks the TV's power status before trusting Connected,
  and its wait-for-on loop must not refresh once Connected (a refresh goes back through Connecting).
- Scheduled scenes (`Scene.days` + `Scene.times`): `SceneSchedule` keeps one exact alarm for the
  soonest run of any scene and is rescheduled on app start, boot, save/delete and after each run.
  A scheduled run uses `apply(onlyIfOn = true)`: it never switches the TV on.
- Hilt receivers must reach Hilt's generated `onReceive` through a super call: extend
  `InjectedReceiver` (or `AppWidgetProvider`), not `BroadcastReceiver`.
- Cookies and PSKs live only in `CredentialStore`. Never log them, never put them in
  `SavedTv`, never back them up.

## UI rules

- One remote screen with the essentials; everything else goes in the More sheet.
- Window sizes (`RemoteScreen`): a phone gets one column, at most 440 dp wide, with the More tabs
  in a sheet. A tablet held upright (600 x 1000 dp) shows them (`MoreContent`) in a framed `Panel`
  between the card and the remote. A tablet on its side (840 x 560 dp) has three columns: the tabs as
  a `MoreTabRail`, the card over the chosen tab's `MoreTabBody` in a panel, and the remote in a
  matching full-height panel. With the tabs on screen the card has no source chips. The remote keeps
  its phone size in all three. Panels fade their lower edge (`fadeBottom`) and tabs leave
  `TAB_END_ROOM` after their last row, so content is never cut by a frame. Wide panes show inputs
  and channels two to a row and the Keys tab side by side. Phones stay portrait (manifest); `MainActivity` frees the
  orientation from smallest width 600 dp. Check a layout change on a phone and both tablet
  orientations (`wm size 1080x2520` + `wm density 420` makes a tablet act as a phone; reset after).
- Every control has a `contentDescription`, a 48 dp or larger touch target, and works
  with dynamic font size. Dark and light theme both have to read well.
- Haptics are a user setting; respect `TvStore.hapticsEnabled`.
- Every string lives in `res/values` and `res/values-fi`. The app language can differ from the phone's
  (`data/AppLanguage.kt`): format day names with `LocalConfiguration.current.locales[0]`, never
  `Locale.getDefault()`, and times with `DateFormat.getTimeFormat(context)`.

## Verification

Run before committing (needs Android SDK platform 36 and build-tools 36):

```text
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Lint errors fail the build; warnings about newer library versions are acceptable.

## Phone smoke test

- `adb devices -l`, then use `-s SERIAL` on every ADB command.
- `adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk`
- `./gradlew assembleRelease` / `bundleRelease` give minified (R8) builds. With `keystore.properties`
  (gitignored; points at the upload key `~/.android/sohva-upload.jks`) they are signed with the Play
  upload key, otherwise with the debug key. An upload-signed APK does not install over a debug build.
  Release builds log nothing; after R8-related changes smoke-test them too (fast keys, the More
  sheet's inputs, channels and app icons, the Settings tab (TVs, phone settings), saved scenes).
- On the TV: Settings › Network › Home network setup › IP control › Authentication
  "Normal and Pre-Shared Key"; Remote start On.
- Walk: discover TV → PIN pairing → power toggle → volume with on-screen and hardware
  buttons → D-pad/OK/Back/Home → an input and an app from the More sheet.
- Tiles: `adb shell cmd statusbar add-tile com.mothandgravel.sohva/.shortcuts.MuteTile`, then open the shade
  (`cmd statusbar expand-settings`) and `cmd statusbar click-tile ...`; test once with the process killed
  (`am kill`) and once cached. Sleep timer: debug builds have a 1 min chip; kill the process and force Doze
  (`dumpsys battery unplug`, `dumpsys deviceidle force-idle`) before it fires. `am force-stop` cancels alarms.
- Wake-up and scenes: test from standby (`adb -s TV shell input keyevent SLEEP`), both with the app's
  process killed and with it cached and still saying Connected. Debug "Try in 1 min" is only in the
  Timers tab, which needs the TV on to open. `deviceidle unforce` before using the UI again, or the
  first connect fails. Check the PS5 stayed asleep (`dumpsys hdmi_control`, `<Report Power Status> 40:90:01`).
- Pull `adb -s SERIAL logcat -d` and treat any crash as a failed smoke test.
