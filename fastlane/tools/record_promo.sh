#!/usr/bin/env bash
# Records the promo run on the phone (release app, English UI, D-pad mode, TV on and free to use, a
# live channel other than MTV3 on, no fast-keys warning on screen). Tap positions are for a 1080x2520
# phone (Xperia 1 IV) with the card showing a programme line; after a layout change, re-measure with
# `uiautomator dump` (see fastlane/VIDEO.md). Then compose with:
#   python fastlane/tools/promo.py . sohva.mp4 <ffmpeg>
set -e
export MSYS_NO_PATHCONV=1
P="adb -s ${PHONE:?set PHONE to the adb serial of the phone}"
$P shell settings put system show_touches 1
$P shell rm -f /sdcard/sohva.mp4
($P shell screenrecord --bit-rate 12000000 --time-limit 42 /sdcard/sohva.mp4 &)
sleep 3
for i in 1 2 3; do $P shell input tap 146 1995; sleep 0.45; done; sleep 1   # volume up x3
$P shell input swipe 600 225 140 225 260; sleep 2.6                         # swipe card: next channel
$P shell input tap 210 814; sleep 3.2                                        # Ad break: muted, countdown
$P shell input tap 235 814; sleep 2.2                                        # tap the countdown: sound back
$P shell input tap 968 983; sleep 1.2                                        # touchpad on
$P shell input swipe 300 1300 820 1300 550; sleep 1
$P shell input swipe 540 1120 540 1480 450; sleep 1
$P shell input tap 540 1300; sleep 1.5                                       # OK
$P shell input tap 968 983; sleep 1.2                                        # arrow buttons again
$P shell input tap 908 606; sleep 1.8                                        # More
$P shell input tap 630 250; sleep 3                                          # Timers
$P shell input tap 810 250; sleep 2.5                                        # Scenes
$P shell input tap 774 569; sleep 5                                          # Run Emmerdale: MTV3
while $P shell pidof screenrecord >/dev/null 2>&1; do sleep 1; done
$P shell settings put system show_touches 0
$P pull /sdcard/sohva.mp4 sohva.mp4
