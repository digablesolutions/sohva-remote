"""Compose the Play promo video from a phone screen recording (see fastlane/VIDEO.md).

Usage: python promo.py <repo root> <recording.mp4> <ffmpeg.exe>
Writes fastlane/build/promo-<locale>.mp4 (1080x1920, 30 fps, H.264), one per locale.
Captions and the end card are rendered with headless Chrome in the listing's font, so they match
the store screenshots. Caption times follow the scripted recording in VIDEO.md.
"""
import html
import pathlib
import subprocess
import sys

REPO = pathlib.Path(sys.argv[1])
RAW = pathlib.Path(sys.argv[2])
FFMPEG = sys.argv[3]
HERE = pathlib.Path(__file__).resolve().parent
WORK = HERE / "build" / "promo"
WORK.mkdir(parents=True, exist_ok=True)
OUT = REPO / "fastlane" / "build"
OUT.mkdir(exist_ok=True)
FONT = (HERE / "font" / "SchibstedGrotesk.ttf").as_uri()
CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"

W, H = 1080, 1920
# The recording (1080x2520) scaled into a phone frame under the caption band.
VID_H = 1480
VID_W = round(1080 * VID_H / 2520 / 2) * 2
VID_X, VID_Y = (W - VID_W) // 2, 396
END_SECONDS = 3

# (start, end, en, fi): seconds on the output timeline (the phone records variable frame rate, so
# take times from frames of the composed video, not by seeking in the raw recording).
CAPTIONS = [
    (0.0, 4.5, ("Your TV, from the <em>sofa</em>", "No ads, no account. Every key lands right away."),
               ("Telkkari hoituu <em>sohvalta</em>", "Ei mainoksia, ei tiliä. Painallukset perille heti.")),
    (4.5, 9.5, ("Swipe for the next <em>channel</em>", "Volume and channels respond instantly."),
               ("Pyyhkäise seuraavalle <em>kanavalle</em>", "Ääni ja kanavat vastaavat heti.")),
    (9.5, 14.3, ("One tap for <em>Emmerdale</em>", "Scenes switch the TV to the right channel."),
                ("<em>Emmerdale</em> yhdellä napautuksella", "Kohtaus vaihtaa oikealle kanavalle.")),
    (14.3, 25.2, ("Or just <em>swipe</em>", "Touchpad mode: swipe to move, tap for OK."),
                 ("Tai vain <em>pyyhkäise</em>", "Kosketuslevy: pyyhkäise ja napauta OK.")),
    (25.2, 28.3, ("Sleep timer and <em>wake-up</em>", "Fades out at night, turns the TV on at 06:15."),
                 ("Uniajastin ja <em>herätys</em>", "Hiljenee illalla, käynnistyy aamulla 6.15.")),
    (28.3, 99.0, ("Your weekday evening, <em>scheduled</em>", "18:30 Emmerdale, then the news."),
                 ("Arki-ilta <em>ajastettuna</em>", "18.30 Emmerdale, sitten uutiset.")),
]

BASE = f"""<!doctype html><html><head><meta charset="utf-8"><style>
@font-face {{ font-family: "S"; src: url("{FONT}"); font-weight: 400 900; }}
* {{ box-sizing: border-box; margin: 0; }}
html, body {{ background: #000; color: #f2f2f2; font-family: "S", sans-serif; overflow: hidden; }}
em {{ font-style: normal; color: #ffb547; }}
</style></head><body>{{body}}</body></html>"""

MARK = '<svg viewBox="0 0 32 32" width="{s}" height="{s}"><circle cx="16" cy="16" r="11" fill="none" stroke="#ffb547" stroke-width="2.4"/><circle cx="16" cy="16" r="4.8" fill="#ffb547"/></svg>'


def render(name, body, w, h):
    page = WORK / f"{name}.html"
    page.write_text(BASE.replace("{body}", body), encoding="utf-8")
    out = WORK / f"{name}.png"
    subprocess.run([CHROME, "--headless=new", "--hide-scrollbars", "--force-device-scale-factor=1",
                    "--allow-file-access-from-files", "--virtual-time-budget=2000",
                    f"--window-size={w},{h}", f"--screenshot={out}", page.as_uri()],
                   check=True, capture_output=True)
    return out


def caption(name, head, sub):
    body = f"""<div style="width:{W}px;height:{VID_Y - 20}px;padding:84px 84px 0">
<h1 style="font-size:70px;line-height:1.04;letter-spacing:-.03em;font-weight:800;text-wrap:balance">{head}</h1>
<p style="margin-top:20px;font-size:34px;line-height:1.3;color:#a3a3a8;font-weight:450">{html.escape(sub)}</p></div>"""
    return render(name, body, W, VID_Y - 20)


def background():
    # The phone body behind the recording, with the same amber glow as the screenshots.
    body = f"""<div style="position:relative;width:{W}px;height:{H}px">
<div style="position:absolute;left:{VID_X - 16}px;top:{VID_Y - 16}px;width:{VID_W + 32}px;height:{VID_H + 32}px;
 border-radius:64px;background:#1b1b1e;box-shadow:0 0 0 2px #2c2c30, 0 60px 160px -40px rgba(255,181,71,.35)"></div></div>"""
    return render("background", body, W, H)


def end_card(locale):
    line = {"en-US": ("A remote for Sony Bravia TVs, on your home network.", "Not affiliated with Sony."),
            "fi-FI": ("Kaukosäädin Sony Bravia -televisioille kotiverkossa.", "Ei sidoksissa Sonyyn.")}[locale]
    body = f"""<div style="width:{W}px;height:{H}px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:36px;text-align:center;
 background:radial-gradient(700px 520px at 50% 45%, rgba(255,181,71,.16), transparent 70%), #000">
{MARK.format(s=200)}
<div style="font-size:92px;font-weight:800;letter-spacing:-.03em">Sohva Remote</div>
<p style="font-size:38px;line-height:1.35;color:#d8d8dc;max-width:820px">{html.escape(line[0])}</p>
<p style="font-size:28px;color:#a3a3a8">{html.escape(line[1])}</p></div>"""
    return render(f"end-{locale}", body, W, H)


def duration(path):
    probe = subprocess.run([FFMPEG, "-i", str(path)], capture_output=True, text=True).stderr
    h, m, s = probe.split("Duration: ")[1].split(",")[0].split(":")
    return int(h) * 3600 + int(m) * 60 + float(s)


def compose(locale):
    clip = duration(RAW)
    bg = background()
    caps = [caption(f"cap-{locale}-{i}", *(en if locale == "en-US" else fi)) for i, (_, _, en, fi) in enumerate(CAPTIONS)]
    end = end_card(locale)
    inputs = ["-loop", "1", "-t", f"{clip:.2f}", "-i", str(bg), "-i", str(RAW)]
    for c in caps:
        inputs += ["-loop", "1", "-t", f"{clip:.2f}", "-i", str(c)]
    inputs += ["-loop", "1", "-t", str(END_SECONDS), "-i", str(end)]
    end_index = 2 + len(caps)
    chain = [f"[1:v]fps=30,scale={VID_W}:{VID_H},setsar=1[rec]", "[0:v]fps=30,setsar=1[bg]", f"[bg][rec]overlay={VID_X}:{VID_Y}[s0]"]
    for i, (start, stop, _, _) in enumerate(CAPTIONS):
        chain.append(f"[s{i}][{i + 2}:v]overlay=0:0:enable='between(t,{start},{min(stop, clip)})'[s{i + 1}]")
    last = f"s{len(CAPTIONS)}"
    chain.append(f"[{last}]trim=duration={clip:.2f},setpts=PTS-STARTPTS[main]")
    chain.append(f"[{end_index}:v]fps=30,setsar=1,fade=t=in:st=0:d=0.4[end]")
    chain.append("[main][end]concat=n=2:v=1:a=0,format=yuv420p[out]")
    out = OUT / f"promo-{locale}.mp4"
    subprocess.run([FFMPEG, "-y", "-v", "error", *inputs, "-filter_complex", ";".join(chain), "-map", "[out]",
                    "-c:v", "libx264", "-preset", "slow", "-crf", "20", "-movflags", "+faststart", str(out)], check=True)
    print(out, f"{duration(out):.1f} s", f"{out.stat().st_size // 1024} KB")


for loc in ["en-US", "fi-FI"]:
    compose(loc)
