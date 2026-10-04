"""
Step 1b - Collect openly-licensed civic-issue photos from the web.

Sources (no API key needed):
  * Wikimedia Commons  - categories + full-text search  (CC / public domain)
  * Openverse          - Creative Commons search engine (Flickr, museums, ...)

Images are saved to data/raw/web/<category>/ as ~640 px JPEGs, with one row per
image in data/raw/web/attribution.csv (source page, licence, author) so the
dataset can be credited properly.

The results are NOISY (a search for "open drain" also returns ministers
inaugurating a drain project).  Always run 01c_clean_dataset.py afterwards:
it uses CLIP to drop images that do not show the category.

Usage:  python 01b_collect_web_images.py [--per-class 900] [--only drainage,water_leakage]
"""
import argparse
import csv
import hashlib
import io
import re
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import requests
from PIL import Image

from config import CATEGORIES, WEB_DIR, WEB_SOURCES

UA = {"User-Agent": "CivicFixDatasetBuilder/1.0 (academic project; https://github.com/Kaushal8434/CivicFix)"}
COMMONS = "https://commons.wikimedia.org/w/api.php"
OPENVERSE = "https://api.openverse.org/v1/images/"
THUMB = 640
session = requests.Session()
session.headers.update(UA)
lock = threading.Lock()


def get_json(url, params, tries=6):
    for i in range(tries):
        try:
            r = session.get(url, params=params, timeout=40)
            if r.status_code == 429:
                time.sleep(30 + 15 * i)
                continue
            r.raise_for_status()
            return r.json()
        except Exception as e:  # flaky network: back off and retry
            if i == tries - 1:
                print(f"  [give up] {url} {params.get('gsrsearch') or params.get('gcmtitle') or params.get('q')}: {e}")
                return {}
            time.sleep(3 * (i + 1))
    return {}


def strip_html(s):
    return re.sub(r"<[^>]+>", "", s or "").strip()[:200]


def commons_items(params, limit):
    """Yields image records from a Commons generator query, following 'continue'."""
    base = {"action": "query", "format": "json", "prop": "imageinfo",
            "iiprop": "url|extmetadata|mime", "iiurlwidth": THUMB}
    cont, n = {}, 0
    while n < limit:
        data = get_json(COMMONS, {**base, **params, **cont})
        for p in data.get("query", {}).get("pages", {}).values():
            ii = (p.get("imageinfo") or [{}])[0]
            if not ii.get("thumburl") or not str(ii.get("mime", "")).startswith("image/") or "svg" in ii.get("mime", ""):
                continue
            meta = ii.get("extmetadata", {})
            n += 1
            yield {"url": ii["thumburl"], "page": ii.get("descriptionurl", ""), "source": "wikimedia-commons",
                   "license": meta.get("LicenseShortName", {}).get("value", ""),
                   "author": strip_html(meta.get("Artist", {}).get("value", "")), "title": p.get("title", "")}
        if "continue" not in data:
            break
        cont = data["continue"]


def commons_subcats(cat):
    data = get_json(COMMONS, {"action": "query", "format": "json", "list": "categorymembers",
                              "cmtitle": f"Category:{cat}", "cmtype": "subcat", "cmlimit": 50})
    return [m["title"].split(":", 1)[1] for m in data.get("query", {}).get("categorymembers", [])]


def commons_category(cat, limit):
    yield from commons_items({"generator": "categorymembers", "gcmtitle": f"Category:{cat}",
                              "gcmtype": "file", "gcmlimit": 50}, limit)


def commons_search(q, limit):
    yield from commons_items({"generator": "search", "gsrsearch": f"filetype:bitmap {q}",
                              "gsrnamespace": 6, "gsrlimit": 50}, limit)


def openverse_search(q, limit):
    n = 0
    for page in range(1, 6):
        data = get_json(OPENVERSE, {"q": q, "page_size": 20, "page": page, "mature": "false"})
        results = data.get("results", [])
        for r in results:
            n += 1
            yield {"url": r.get("thumbnail") or r.get("url"), "page": r.get("foreign_landing_url", ""),
                   "source": f"openverse/{r.get('source', '')}",
                   "license": f"{r.get('license', '')} {r.get('license_version', '')}".strip(),
                   "author": (r.get("creator") or "")[:200], "title": (r.get("title") or "")[:200]}
            if n >= limit:
                return
        if len(results) < 20:
            return
        time.sleep(4)  # Openverse anonymous rate limit


def download(rec, out_dir):
    for i in range(4):
        try:
            r = session.get(rec["url"], timeout=40)
            if r.status_code == 429:
                time.sleep(10 * (i + 1))
                continue
            r.raise_for_status()
            img = Image.open(io.BytesIO(r.content)).convert("RGB")
            if min(img.size) < 160:
                return None
            img.thumbnail((THUMB, THUMB))
            buf = io.BytesIO()
            img.save(buf, "JPEG", quality=90)
            data = buf.getvalue()
            name = hashlib.md5(data).hexdigest()[:16] + ".jpg"
            path = out_dir / name
            if path.exists():
                return None
            path.write_bytes(data)
            return name
        except Exception:
            time.sleep(2 * (i + 1))
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--per-class", type=int, default=900, help="max candidate images per category")
    ap.add_argument("--only", default="", help="comma-separated category keys")
    ap.add_argument("--no-openverse", action="store_true")
    args = ap.parse_args()
    cats = [c for c in CATEGORIES if not args.only or c in args.only.split(",")]

    WEB_DIR.mkdir(parents=True, exist_ok=True)
    attr_path = WEB_DIR / "attribution.csv"
    seen_urls = set()
    if attr_path.exists():
        with attr_path.open(encoding="utf-8") as f:
            seen_urls = {row["url"] for row in csv.DictReader(f)}
    new_file = not attr_path.exists()
    attr = attr_path.open("a", newline="", encoding="utf-8")
    writer = csv.DictWriter(attr, fieldnames=["category", "file", "source", "license", "author", "page", "title", "url"])
    if new_file:
        writer.writeheader()

    pool = ThreadPoolExecutor(8)
    for cat in cats:
        spec = WEB_SOURCES[cat]
        out_dir = WEB_DIR / cat
        out_dir.mkdir(parents=True, exist_ok=True)
        have = len(list(out_dir.glob("*.jpg")))
        budget = args.per_class - have
        print(f"\n== {cat}: {have} already, collecting up to {max(budget, 0)} more")
        if budget <= 0:
            continue

        # Interleave the sources so one big category cannot use up the whole budget.
        gens = []
        for c in spec["commons_cats"]:
            gens.append(commons_category(c, 250))
            for sub in commons_subcats(c)[:8]:
                gens.append(commons_category(sub, 80))
        for q in spec["queries"]:
            gens.append(commons_search(q, 120))
            if not args.no_openverse:
                gens.append(openverse_search(q, 100))

        records = []
        while gens and len(records) < budget * 1.3:
            for g in list(gens):
                try:
                    rec = next(g)
                except StopIteration:
                    gens.remove(g)
                    continue
                if rec["url"] and rec["url"] not in seen_urls:
                    seen_urls.add(rec["url"])
                    records.append(rec)

        saved = 0
        for rec, name in zip(records, pool.map(lambda r: download(r, out_dir), records)):
            if name and saved < budget:
                saved += 1
                with lock:
                    writer.writerow({"category": cat, "file": name, **rec})
        attr.flush()
        print(f"   saved {saved} images ({len(records)} candidates)")
    attr.close()
    pool.shutdown()
    print("\nNext: python 01c_clean_dataset.py")


if __name__ == "__main__":
    main()
