"""Regenerates the crawler-facing files of the landing page from index.html.

Usage: python site/tools/seo.py
- Writes the JSON-LD block in index.html (SoftwareApplication, Organization, WebSite, FAQPage).
  The FAQ answers are taken from the page's own <details> elements, so the two cannot drift.
- Writes sitemap.xml with today's date for pages whose content changed.
Run it after editing the FAQ or the feature list, before committing site/.
"""
import datetime
import html
import json
import pathlib
import re

SITE = pathlib.Path(__file__).resolve().parent.parent
# The pages are hosted on the Moth & Gravel studio site; this folder is their source.
BASE = "https://mothandgravel.com/sohva/"
INDEX = SITE / "index.html"

FEATURES = [
    "Fast keys over the TV's Android TV Remote protocol: D-pad, volume and channels in tens of milliseconds",
    "Touchpad mode: swipe to move, tap for OK, rest the finger to keep moving",
    "Swipe the card to change channel; jump back to the previous channel",
    "Ad break: mute the commercials with one tap; the sound comes back by itself",
    "The TV's inputs, installed apps and channel list; pin apps as one-tap buttons and launcher shortcuts",
    "Type or dictate into the TV's search box; voice commands in Finnish and English",
    "Scenes and scheduled scenes (they act only while the TV is on)",
    "Sleep timer that fades the sound over a minute; wake-up TV at a set time",
    "Quick Settings tiles, home-screen widget, remote in notifications and on the lock screen",
    "Mute the TV during phone calls; the phone's volume buttons control the TV",
    "English or Finnish, chosen in the app whatever the phone's language",
    "Phones and tablets: on a tablet the More tabs sit beside the remote",
    "Home network only: no ads, no account, no cloud, no analytics",
]


def text(fragment):
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", "", fragment))).strip()


def faq(page):
    pairs = re.findall(r"<details[^>]*>\s*<summary[^>]*>(.*?)</summary>(.*?)</details>", page, re.S)
    return [{"@type": "Question", "name": text(q), "acceptedAnswer": {"@type": "Answer", "text": text(a)}} for q, a in pairs]


def json_ld(page):
    org = {"@type": "Organization", "@id": BASE + "#publisher", "name": "Moth & Gravel", "url": "https://mothandgravel.com/"}
    app = {
        "@type": "SoftwareApplication",
        "@id": BASE + "#app",
        "name": "Sohva Remote",
        "alternateName": "Sohva Remote for Sony Bravia TVs",
        "description": "A fast, ad-free Android phone remote for Sony Bravia TVs that works on the home network only. "
                       "Not affiliated with Sony.",
        "applicationCategory": "UtilitiesApplication",
        "applicationSubCategory": "TV remote control",
        "operatingSystem": "Android 8.0 or newer",
        "softwareVersion": "1.5.2",
        "inLanguage": ["en", "fi"],
        "isAccessibleForFree": True,
        "license": "https://www.gnu.org/licenses/gpl-3.0.html",
        "offers": {"@type": "Offer", "price": "0", "priceCurrency": "EUR"},
        "url": BASE,
        "image": BASE + "img/og.jpg",
        "screenshot": [BASE + f"img/{n}.png" for n in ("remote", "scenes", "timers", "channels", "inputs-apps", "tablet", "tablet-upright")],
        "featureList": FEATURES,
        "author": {"@id": org["@id"]},
        "publisher": {"@id": org["@id"]},
    }
    site = {"@type": "WebSite", "@id": BASE + "#site", "url": BASE, "name": "Sohva Remote", "inLanguage": "en",
            "publisher": {"@id": org["@id"]}}
    graph = [app, org, site]
    questions = faq(page)
    if questions:
        graph.append({"@type": "FAQPage", "@id": BASE + "#faq", "mainEntity": questions})
    return json.dumps({"@context": "https://schema.org", "@graph": graph}, ensure_ascii=False, indent=2)


def main():
    page = INDEX.read_text(encoding="utf-8")
    block = f'<script type="application/ld+json" id="structured-data">\n{json_ld(page)}\n</script>'
    if 'id="structured-data"' in page:
        page = re.sub(r'<script type="application/ld\+json" id="structured-data">.*?</script>', lambda _: block, page, flags=re.S)
    else:
        page = page.replace("</head>", block + "\n</head>", 1)
    INDEX.write_text(page, encoding="utf-8")

    today = datetime.date.today().isoformat()
    urls = [("", "1.0"), ("privacy/", "0.3"), ("privacy/fi/", "0.3"), ("llms.txt", "0.2")]
    entries = "\n".join(
        f"  <url><loc>{BASE}{path}</loc><lastmod>{today}</lastmod><priority>{prio}</priority></url>" for path, prio in urls
    )
    (SITE / "sitemap.xml").write_text(
        f'<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n{entries}\n</urlset>\n',
        encoding="utf-8",
    )
    print("structured data + sitemap.xml written;", len(faq(page)), "FAQ entries")


if __name__ == "__main__":
    main()
