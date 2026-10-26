# Promo video storyboard (Sohva Remote)

**Done 2026-10-26 (1.5.0):** `fastlane/video/promo-en-US.mp4` and `promo-fi-FI.mp4` (43.2 s,
1080x1920, captions burned in, end card). Recorded with `tools/record_promo.sh` (scripted phone run,
System UI demo mode at 18:30, D-pad mode, chat bubbles off) and composed with `tools/promo.py`
(ffmpeg from the `imageio-ffmpeg` pip package). The run: volume up, swipe the card to the next
channel, Ad break (muted, countdown, tap to come back), touchpad, the Timers tab, the Scenes tab,
and Run on Emmerdale, which switches the TV to MTV3. Pinned apps push the Emmerdale chip off-screen,
so the scene runs from the Scenes tab. Voice was left out: speech can't be scripted over adb.
**To publish:** upload each to YouTube as Unlisted, embedding on, monetisation off, not "made for
kids"; put the URL in `metadata/android/<locale>/video.txt`.

Target: 25 to 30 s, portrait phone capture, no voice-over needed, subtitles burned in only if the
Finnish and English versions are separate uploads. The first 10 s must show the real experience
(Play autoplays the start muted), so no logo intro.

## Capture

Record when the TV is free (nobody watching, it will change channels and turn off), in the
evening light you want on screen if you film the TV too.

```sh
adb devices -l                                  # phone and TV are both on adb; pick the phone
adb -s <PHONE_SERIAL> shell settings put system show_touches 1
adb -s <PHONE_SERIAL> shell screenrecord --bit-rate 12000000 --time-limit 40 /sdcard/sohva.mp4
adb -s <PHONE_SERIAL> pull /sdcard/sohva.mp4
adb -s <PHONE_SERIAL> shell settings put system show_touches 0
```

- Set the phone to Do Not Disturb with a full battery. For a clean status bar use System UI demo mode:
  `adb shell settings put global sysui_demo_allowed 1`, then
  `adb shell am broadcast -a com.android.systemui.demo -e command enter`,
  `adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1830`,
  `adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false`;
  leave with `-e command exit`.
- Optional second camera: film the TV with a phone on a tripod at the same time, and cut between
  the phone capture and the TV reacting. This sells the speed far better than the phone alone.
  Don't show the Sony logo on the bezel in close-up; keep the TV picture itself in frame.
- `screenrecord` is limited to 3 minutes per file; record each shot separately and edit.

## Shots

| Time | Phone (screen recording) | TV (optional cut-in) | Caption (en / fi) |
|------|--------------------------|----------------------|-------------------|
| 0-3 s | Open the app: the remote is ready at once, card shows the current channel | TV on | "Your TV, from the sofa" / "Telkkari hoituu sohvalta" |
| 3-7 s | Press volume up three times, then swipe the card to change channel twice | Volume bar and channel change follow at once | "No ads. Keys land right away." / "Ei mainoksia. Painallukset perille heti." |
| 7-10 s | Tap the "Emmerdale" scene chip | TV switches to MTV3 | "One tap for Emmerdale" / "Emmerdale yhdellä napautuksella" |
| 10-14 s | Touchpad mode: swipe through a menu, tap for OK | Focus moves on the TV | "Touchpad mode" / "Kosketuslevy" |
| 14-18 s | Mic button: say "kanava 3" | TV changes channel | "Voice: \"kanava 3\"" / "Puhe: \"kanava 3\"" |
| 18-23 s | Scenes tab: show Mon-Fri 18:30, 19:00, 20:30, 22:00 | - | "Your weekday evening, scheduled" / "Arki-ilta ajastettuna" |
| 23-27 s | Timers tab: tap 30 min sleep timer | - | "Fades out, then turns off" / "Hiljenee ja sammuu" |
| 27-30 s | End card: amber ring mark + "Sohva Remote", line "Not affiliated with Sony." | - | same in fi: "Ei sidoksissa Sonyyn." |

## Upload

- YouTube, **Unlisted** (or public), **embedding allowed**, **monetisation/ads off**, not
  age-restricted, not "made for kids" unless that is true (it is not).
- Paste the URL into `fastlane/metadata/android/<locale>/video.txt` (one line, nothing else).
- Don't show third-party app logos (Netflix etc.) prominently, and don't claim Sony endorsement.
