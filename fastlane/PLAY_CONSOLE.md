# Play Console checklist: Sohva Remote

The answers below match the code as of this writing (checked against `app/` read-only). Items marked
**BLOCKER** must be fixed in the app or on the web before the first production submission.

## 0. Blockers found in the repo

1. **BLOCKER: package name.** `app/build.gradle.kts` still has `applicationId = "fi.digable.braviaremote"`.
   The listing assumes `fi.digable.sohva`. The package name is permanent once the first AAB is
   uploaded to any track (internal testing included), so change it before the first upload.
2. **BLOCKER: app name.** `app_name` in `app/src/main/res/values/strings.xml` is "Bravia Remote"
   (and `site/index.html` says "Bravia Remote"). The launcher name should match the store title
   "Sohva Remote", and "Bravia" in the name is the trademark risk we are avoiding in the title.
3. **BLOCKER: version code.** `versionCode = 2`; the release notes are `changelogs/3.txt`, so the
   first upload must be versionCode 3 (or rename the changelog file).
4. **BLOCKER: target API.** `targetSdk = 35` (`compileSdk = 36`). From 31 Aug 2026 new apps and
   updates must target API 36 (extension possible to 1 Nov 2026).
   Source: https://developer.android.com/google/play/requirements/target-sdk
5. **BLOCKER (likely): exact alarms.** The manifest declares `USE_EXACT_ALARM` (all API levels) and
   `SCHEDULE_EXACT_ALARM` only up to API 32. Play policy: apps "must only declare [USE_EXACT_ALARM]
   if their core functionality supports the need for an exact alarm"; examples are an alarm or timer
   app and a calendar app; others should "evaluate if using SCHEDULE_EXACT_ALARM as an alternative is
   an option", and apps that don't meet the criteria "will be disallowed from publishing".
   Source: https://support.google.com/googleplay/android-developer/answer/9888170 (Exact alarm section).
   A TV remote whose wake-up TV and sleep timer are secondary features is a weak fit. Recommendation:
   drop `USE_EXACT_ALARM`, declare `SCHEDULE_EXACT_ALARM` for all API levels, ask the user to grant
   "Alarms & reminders" when they turn on the wake-up TV / sleep timer / scheduled scenes, and fall
   back to inexact alarms if refused. If you keep `USE_EXACT_ALARM`, be ready to argue "wake-up TV is
   an alarm clock" in the declaration, and expect a possible rejection.
6. **BLOCKER: privacy policy page.** `site/` has no `privacy.html`. The URL
   https://digablesolutions.github.io/sohva-remote/privacy.html must be live (and match the Data
   safety answers below) before submitting.
7. **Launcher icon mismatch.** `ic_launcher_foreground.xml` is a remote outline, while the store icon
   is the amber ring + dot mark from `site/favicon.svg`. Play asks that the store icon represents the
   app; make the launcher icon the same mark.
8. **App UI is English only** (no `values-fi`). The fi-FI listing is Finnish, the screenshots show the
   English UI. That is allowed, but a Finnish UI would match the Finland-first audience.

## 1. App details (Grow > Store presence > Main store listing)

- App name: `Sohva Remote` (from `title.txt`)
- Short / full description: from `fastlane/metadata/android/<locale>/`
- Default language: English (United States) en-US; add translation Finnish fi-FI
- App or game: **App**. Free.
- **Category: Tools.** Why: the app is a utility that controls a device on the LAN; that is where
  people browse for TV remotes, and the competitor remotes sit there (e.g. other TV remote apps).
  Entertainment would put us next to streaming and media apps, where we don't serve content
  and would rank against much bigger apps.
- Tags (choose up to 5 that the console offers; closest matches): Remote control, Smart home,
  TV, Utilities, Wi-Fi tools (pick whatever of these exists in the tag picker).
- Contact email: `<support@your-domain>` (**placeholder: decide an address that Digable monitors**)
- Website: https://digablesolutions.github.io/sohva-remote/
- Privacy policy: https://digablesolutions.github.io/sohva-remote/privacy.html
- External marketing: allow (default).

## 2. App content (Policy > App content)

### Privacy policy
https://digablesolutions.github.io/sohva-remote/privacy.html

### Ads
**No, my app does not contain ads.**

### App access
Choose **"All or some functionality is restricted"** and add instructions (there is no login, so no
credentials):

> Sohva Remote controls a Sony Bravia TV over the local Wi-Fi network. It needs a Bravia TV with IP
> control on the same network as the phone; there is no account, cloud service or demo server, so it
> cannot be fully exercised without such a TV.
>
> Without a TV, the app opens on "Find your TV": it searches the Wi-Fi network ("Searching the Wi-Fi
> network…", with "Search again") and offers "Add by IP address". With no Bravia on the network nothing
> is found, and an address that is not a Bravia cannot be paired. The app sends nothing to any server.
>
> With a TV: on the TV, Settings > Network > Home network setup > IP control: Authentication "Normal
> and Pre-Shared Key", Remote start "On". Open the app, pick the TV from "Find your TV" (or add it by
> IP address) and enter the 4-digit code the TV shows. Optionally, "Set up fast keys" pairs the
> Android TV Remote connection with a second code shown on the TV. Tested on a KD-65XF9005 (Android
> TV). A screen recording of the full flow: <link to unlisted video>.

(Wording taken from `strings.xml` and `ui/AppNav.kt`, where the start destination is DISCOVER until
a TV is saved. Re-check if the first-run flow changes before submission.)

### Content rating (IARC questionnaire)
- Email: the contact email above. Category: **"All other app types"** (utility / not a game,
  not social, not a communication app).
- Violence, fear, sexuality, nudity, profanity/crude humour, drugs, alcohol, tobacco, gambling,
  simulated gambling: **No** to all.
- Does the app allow users to interact or exchange content with other users? **No.**
- Does the app share the user's current physical location with other users? **No.**
- Does the app allow users to purchase digital goods? **No.**
- Is the app a web browser or search engine? **No.** (Typing into the TV's own search box is
  forwarding keystrokes to the TV, not providing search.)
- Does the app contain unrestricted internet access/content? **No.**
- Expected result: PEGI 3 / IARC 3+ / Everyone.

### Target audience and content
- Target age groups: **18 and over** (optionally also 16-17). Do **not** select under-13 groups.
  The app is a household utility; ticking child age groups pulls in the Families policy for no gain.
- "Could your store listing unintentionally appeal to children?" **No.**

### News app
No.

### COVID-19 contact tracing / status
Not applicable.

### Data safety
- Does your app collect or share any of the required user data types? **Recommended: No.**
  Play's definition: "'Collect' means transmitting data from your app off a user's device", and
  "User data accessed by your app that is only processed locally on the user's device" is not
  collection. "'Sharing' refers to transferring user data collected from your app to a third party."
  Source: https://support.google.com/googleplay/android-developer/answer/10787469
  - What does leave the phone: key presses, channel/input/app choices and the text typed or dictated
    into the TV's search box, sent only to the user's own TV on the home network. Nothing ever
    reaches Digable or any server, and the TV is the user's device, not a third party.
  - The honest grey area: the TV is technically "off the device". The "No" answer rests on the
    reading that commands sent to the user's own hardware on the LAN, never to the developer, are not
    collection (this is how other LAN remote apps answer). If you want to be conservative instead,
    declare **App activity > Other user-generated content** (the typed/dictated search text):
    collected, not shared, processed ephemerally, required for app functionality, not optional.
    Decide one way and make the privacy policy say the same.
- Is all of the user data collected by your app encrypted in transit? Not shown when nothing is
  collected. (For the record: Android TV Remote pairing uses TLS with a device certificate; the Bravia
  REST API on the LAN is plain HTTP on the home network. Neither leaves the LAN.)
- Do you provide a way for users to request that their data is deleted? Not shown when nothing is
  collected. For the privacy policy text: the data that exists lives only on the phone. Remove one TV
  with its **Forget** button (in the sheet under the remote, `MoreSheet.kt`); remove everything with Android Settings > Apps > Sohva Remote > Storage >
  Clear storage, or by uninstalling. Nothing is held by Digable, so there is nothing to request.
- What stays on the device (describe in the privacy policy, not in the form):
  - TV address, name, channel/input/app lists, scenes, timers, settings: DataStore preferences.
  - Pairing secrets (auth cookies, per-TV IRCC code tables, the TVs' Android TV Remote certificates):
    `EncryptedSharedPreferences`, encrypted at rest with an Android Keystore key
    (`data/CredentialStore.kt`). The app's own TLS client key for Android TV Remote pairing is
    generated inside the Android Keystore and never leaves it (`data/AtvIdentity.kt`).
  - Backup and device transfer are disabled for all app data (`allowBackup="false"`,
    `data_extraction_rules.xml` excludes every domain).
  - Voice input uses the system speech recognizer through `RecognizerIntent`; the app has no
    microphone permission and gets back only the recognised text.
  - Phone state (opt-in call mute) is read on the device to mute/unmute the TV; it is not stored or
    sent anywhere.
  - Third-party libraries: AndroidX, Hilt, OkHttp, Coil, kotlinx. No analytics, crash reporting or ad
    SDKs.

### Government apps / Financial features / Health
No / None / No.

### Permissions declarations
- **Exact alarms**: see Blocker 5. If the manifest keeps `USE_EXACT_ALARM`, Play Console will ask for
  the exact-alarm declaration; describe it as: "Wake-up TV: turns the user's TV on at a user-set time
  (an alarm clock for the TV). Sleep timer: turns the TV off at a user-set time. Both must fire on
  time with the app closed." If you switch to `SCHEDULE_EXACT_ALARM` (recommended), no declaration
  form is needed; the user grants "Alarms & reminders" in system settings.
- **READ_PHONE_STATE**: not a Play "restricted permission" (that is the SMS/Call Log group), so there
  is no Permissions Declaration form. It is a runtime permission asked only when the user turns on
  "Mute the TV during calls". Mention it in the privacy policy.
- **Foreground service types** (app targets 34+, so Play asks for an FGS declaration under App content):
  - `SleepFadeService` and `WakeUpService` use `foregroundServiceType="shortService"`.
  - developer.android.com: shortService needs no type-specific permission ("Doesn't require a
    type-specific permission, though it still requires the FOREGROUND_SERVICE permission"), runtime
    prerequisites "None", runs "about 3 minutes". The same page says: "If your app targets Android 14
    or higher, you'll need to declare your app's foreground service types in the Play Console's app
    content page (Policy > App content)."
    Source: https://developer.android.com/develop/background-work/services/fgs/service-types
  - Play Help "Understanding foreground service and full-screen intent requirements" lists types that
    need a declaration in a "non-exhaustive list" (camera, connected device, data sync, health,
    location, media playback, media projection, media processing, microphone, phone call, remote
    messaging) and does not mention shortService.
    Source: https://support.google.com/googleplay/android-developer/answer/13392821
  - **Finding:** shortService has no special-permission declaration, but the console's FGS form asks
    about every type the app uses. If the form lists shortService (or offers "other"), describe:
    "Sleep timer: fades the TV volume over the final minute and then sends power-off to the TV on the
    local network (about 60 s). Wake-up TV: turns the TV on via Wake-on-LAN and sets channel and
    volume (a few seconds). Both are started by a user-set alarm, finish well within 3 minutes, and
    show a notification while running." Keep a short screen recording ready in case review asks.

## 3. Pre-launch checklist

- [x] Blockers 1-6 above resolved (package name, app name, versionCode 3, targetSdk 36, exact alarm
      permission, privacy page live) - done 2026-10-25.
- [ ] Organization developer account for Digable verified (D-U-N-S, address, phone).
- [ ] Build a signed **Android App Bundle** (`./gradlew bundleRelease`), R8 on, no debug logs.
- [ ] **App signing: use our own key** (decided 2026-10-25). The GitHub release APK is signed with
      `~/.android/sohva-upload.jks` (alias `upload`, SHA-256 A0:3B:3F:9F:...:C4:26), so Play must
      sign with the same key or sideloaded installs could never update from Play. In Play Console,
      when creating the release choose *Change app signing key* -> *Use the same key as another app /
      Export and upload a key from Java keystore* (PEPK tool), and upload this key as the **app
      signing key**. Then register a separate upload key (or reuse it). This key can never be
      replaced: keep the .jks and its password (keystore.properties) in two safe places.
- [ ] Upload first to **Internal testing** (family testers), then **Closed testing**. Note: new
      *personal* developer accounts must run a closed test with 12+ testers for 14 days before
      production; organization accounts are exempt, but a closed test is still worth running.
- [ ] Check the **pre-launch report** (it runs on devices without a Bravia; make sure the app does not
      crash on the first-run / no-TV path).
- [ ] Store listing: upload `icon.png`, `featureGraphic.png`, `phoneScreenshots/1-6.png` for en-US and
      fi-FI (or `fastlane supply --skip_upload_apk --skip_upload_aab`).
- [ ] Release notes from `changelogs/3.txt`.
- [ ] Countries: Finland first (plus the Nordics/EU if wanted). The app works anywhere with a Bravia.
- [ ] Optional: promo video URL in `video.txt` (see `fastlane/VIDEO.md`).
- [ ] After approval: staged rollout (e.g. 20%) and watch Android vitals for ANRs/crashes.
