"""Render Play listing images (icon, feature graphic, phone screenshots) with headless Chrome.

Usage: python -I fastlane/tools/render.py <repo root>
Needs Pillow, Google Chrome, and Schibsted Grotesk next to this script at font/SchibstedGrotesk.ttf:
  curl -L -o fastlane/tools/font/SchibstedGrotesk.ttf "https://github.com/google/fonts/raw/main/ofl/schibstedgrotesk/SchibstedGrotesk%5Bwght%5D.ttf"
Intermediate HTML/PNG go to fastlane/tools/build/ (safe to delete).
"""
import html
import pathlib
import subprocess
import sys

from PIL import Image

REPO = pathlib.Path(sys.argv[1])
HERE = pathlib.Path(__file__).resolve().parent
WORK = HERE / "build"
WORK.mkdir(exist_ok=True)
FONT = (HERE / "font" / "SchibstedGrotesk.ttf").as_uri()
CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
META = REPO / "fastlane" / "metadata" / "android"
# Phone captures per locale (the app's own language); see fastlane/VIDEO.md for demo-mode capture.
SHOTS_DIR = HERE / "shots"

BASE_CSS = f"""
@font-face {{ font-family: "Schibsted Grotesk"; src: url("{FONT}"); font-weight: 400 900; }}
:root {{ --ink:#000; --text:#f2f2f2; --muted:#a3a3a8; --amber:#ffb547; --amber-deep:#3a2a0e; --line:#2c2c30; --power:#6fe3a5; }}
* {{ box-sizing: border-box; margin: 0; }}
html, body {{ background: #000; color: var(--text); font-family: "Schibsted Grotesk", sans-serif; overflow: hidden; }}
"""

MARK = """<svg viewBox="0 0 32 32" width="{s}" height="{s}"><circle cx="16" cy="16" r="11" fill="none" stroke="#ffb547" stroke-width="2.4"/><circle cx="16" cy="16" r="4.8" fill="#ffb547"/></svg>"""


def chrome(html_text, name, w, h):
    page = WORK / f"{name}.html"
    page.write_text(f"<!doctype html><html><head><meta charset='utf-8'><style>{BASE_CSS}</style></head><body>{html_text}</body></html>", encoding="utf-8")
    out = WORK / f"{name}.png"
    subprocess.run([CHROME, "--headless=new", "--hide-scrollbars", "--force-device-scale-factor=1",
                    "--allow-file-access-from-files", "--virtual-time-budget=3000",
                    f"--window-size={w},{h}", f"--screenshot={out}", page.as_uri()],
                   check=True, capture_output=True)
    return out


def finish(png, dest, w, h, keep_alpha=False):
    img = Image.open(png)
    assert img.size == (w, h), (png, img.size)
    img = img.convert("RGBA" if keep_alpha else "RGB")
    dest.parent.mkdir(parents=True, exist_ok=True)
    img.save(dest, optimize=True)


# Source screenshots: drop the system navigation bar (bottom ~140 px) so the UI reads larger.
def crop_source(name, top=0, locale="en-US"):
    out = WORK / f"src-{locale}-{top}-{name}"
    im = Image.open(SHOTS_DIR / locale / name).convert("RGB")
    im.crop((0, top, 1080, 2380)).save(out)
    return out.as_uri()


SHOTS = [
    # (file, source, mode, en (head, sub), fi (head, sub))
    ("1", "remote.png", "top",
     ("Your TV, from the <em>sofa</em>", "No ads, no account. Pair once, and every key lands right away."),
     ("Telkkari hoituu <em>sohvalta</em>", "Ei mainoksia, ei tiliä. Yhdistä kerran, ja painallukset menevät perille heti.")),
    ("2", "touchpad.png", "top",
     ("Or just <em>swipe</em>", "Touchpad mode: swipe to move, tap for OK, rest your finger to keep going. No looking down."),
     ("Tai vain <em>pyyhkäise</em>", "Kosketuslevy: pyyhkäise liikkuaksesi, napauta OK, pidä sormi paikallaan jatkaaksesi.")),
    ("3", "scenes.png", "top",
     ("Emmerdale at <em>18:30</em>, the news at <em>19:00</em>", "Scheduled scenes change the channel on weekday evenings while the TV is on."),
     ("Emmerdale <em>18.30</em>, uutiset <em>19.00</em>", "Ajastetut kohtaukset vaihtavat kanavan arki-iltaisin, kun televisio on päällä.")),
    ("4", "timers.png", "top",
     ("Doze off after <em>Kymmenen Uutiset</em>", "The sleep timer fades the sound, then turns the TV off. Wake-up TV is ready at 6:15."),
     ("Nukahda <em>Kymmenen Uutisten</em> jälkeen", "Uniajastin hiljentää äänen ja sammuttaa television. Aamulla herätys-TV klo 6.15.")),
    ("5", "channels.png", "top",
     ("Every channel, <em>one tap</em> away", "The TV's own channel list, with the one you're watching ticked."),
     ("Kaikki kanavat <em>yhdellä napautuksella</em>", "Television oma kanavalista, katsottava kanava merkittynä.")),
    ("6", "inputs-apps.png", "top",
     ("Inputs and apps in <em>one list</em>", "Switch to the console or the soundbar without hunting for the remote."),
     ("Tulot ja sovellukset <em>samassa listassa</em>", "Vaihda pelikonsoliin tai soundbariin etsimättä kaukosäädintä.")),
    ("7", "remote.png", "bottom",
     ("Volume and channels, <em>instantly</em>", "Keys arrive in tens of milliseconds. Your phone's volume buttons work too."),
     ("Ääni ja kanavat <em>heti</em>", "Painallukset perillä kymmenissä millisekunneissa. Puhelimen äänipainikkeetkin toimivat.")),
]

SHOT_CSS = """
body { display:flex; flex-direction:column; width:1080px; height:1920px; }
.cap { flex:none; padding:92px 84px 0; }
.cap h1 { font-size:74px; line-height:1.04; letter-spacing:-.03em; font-weight:800; text-wrap:balance; }
.cap h1 em { font-style:normal; color:var(--amber); }
.cap p { margin-top:22px; font-size:35px; line-height:1.3; color:var(--muted); font-weight:450; text-wrap:pretty; }
.stage { position:relative; flex:1; margin-top:56px; overflow:hidden; }
.phone { position:absolute; left:50%; width:780px; margin-left:-390px; padding:16px; border-radius:72px;
  background:#1b1b1e; box-shadow:0 0 0 2px var(--line), 0 60px 160px -40px rgba(255,181,71,.35); }
.phone img { display:block; width:748px; border-radius:56px; }
.top .phone { top:0; }
.bottom .phone { bottom:56px; }
.bottom::before { content:""; position:absolute; inset:0 0 auto 0; height:160px; z-index:2;
  background:linear-gradient(#000, rgba(0,0,0,0)); }
"""


def screenshot(locale, idx, src, mode, head, sub):
    css = SHOT_CSS
    body = f"""<style>{css}</style>
<div class="cap"><h1>{head}</h1><p>{html.escape(sub)}</p></div>
<div class="stage {mode}"><div class="phone"><img src="{crop_source(src, locale=locale)}"></div></div>"""
    png = chrome(body, f"shot-{locale}-{idx}", 1080, 1920)
    finish(png, META / locale / "images" / "phoneScreenshots" / f"{idx}.png", 1080, 1920)


FG = {
    "en-US": ("A fast TV remote for the <em>sofa</em>.", "No ads, no account. Pair once, on your home network."),
    "fi-FI": ("Nopea kaukosäädin <em>sohvalle</em>.", "Ei mainoksia, ei tiliä. Yhdistä kerran kotiverkossa."),
}


def feature_graphic(locale):
    head, sub = FG[locale]
    body = f"""<style>
.fg {{ position:relative; width:1024px; height:500px; overflow:hidden;
  background: radial-gradient(600px 420px at 82% 40%, rgba(255,181,71,.16), transparent 70%), #000; }}
.col {{ position:absolute; left:72px; top:0; bottom:0; width:540px; display:flex; flex-direction:column; justify-content:center; gap:26px; }}
.brand {{ display:flex; align-items:center; gap:18px; font-size:34px; font-weight:700; }}
.fg h1 {{ font-size:58px; line-height:1.04; letter-spacing:-.03em; font-weight:800; text-wrap:balance; }}
.fg h1 em {{ font-style:normal; color:var(--amber); }}
.fg p {{ width:480px; font-size:25px; line-height:1.35; color:var(--muted); }}
.phone {{ position:absolute; left:640px; top:54px; width:320px; padding:8px; border-radius:40px; background:#1b1b1e;
  box-shadow:0 0 0 1.5px var(--line), 0 30px 90px -20px rgba(255,181,71,.35); }}
.phone img {{ display:block; width:304px; border-radius:32px; }}
</style>
<div class="fg">
  <div class="col"><div class="brand">{MARK.format(s=52)}Sohva Remote</div>
  <h1>{head}</h1><p>{html.escape(sub)}</p></div>
  <div class="phone"><img src="{crop_source('remote.png', top=72, locale=locale)}"></div>
</div>"""
    png = chrome(body, f"feature-{locale}", 1024, 500)
    finish(png, META / locale / "images" / "featureGraphic.png", 1024, 500)


def icon(locale):
    # Full-bleed square; Play applies its own rounded mask. Mark kept well inside the safe zone.
    body = """<div style="width:512px;height:512px;background:#000;display:grid;place-items:center">
<svg viewBox="0 0 512 512" width="512" height="512"><circle cx="256" cy="256" r="158" fill="none" stroke="#ffb547" stroke-width="35"/><circle cx="256" cy="256" r="69" fill="#ffb547"/></svg></div>"""
    png = chrome(body, "icon", 512, 512)
    finish(png, META / locale / "images" / "icon.png", 512, 512, keep_alpha=True)


for loc_i, locale in enumerate(["en-US", "fi-FI"]):
    icon(locale)
    feature_graphic(locale)
    for idx, src, mode, en, fi in SHOTS:
        head, sub = en if locale == "en-US" else fi
        screenshot(locale, idx, src, mode, head, sub)

for p in sorted(META.rglob("*.png")):
    im = Image.open(p)
    print(f"{p.relative_to(META)}  {im.size[0]}x{im.size[1]}  {im.mode}  {p.stat().st_size // 1024} KB")
