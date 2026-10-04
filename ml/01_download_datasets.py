"""
Step 1 - Download public civic-issue photo datasets and sort them into
data/real/<category>/ using the keyword rules in config.PUBLIC_DATASETS.

Requirements:
  * Kaggle datasets: `pip install kagglehub` - public datasets download
    anonymously, no API token needed.
  * Git datasets: `git` on PATH
  * TACO ("manual"): run `python download.py` from the TACO repo yourself,
    then copy its data/ folder to ml/data/raw/taco/

You can also drop your own photos (e.g. taken in your city) into
data/real/<category>/ - they are used exactly like the public data.

More photos for the rare classes come from 01b_collect_web_images.py, and
01c_clean_dataset.py checks every image with CLIP before training.

Usage:  python 01_download_datasets.py [--skip-download]
"""
import argparse
import hashlib
import random
import shutil
import subprocess
import sys
import time
import zipfile
from pathlib import Path

from config import CATEGORIES, PUBLIC_DATASETS, RAW_DIR, REAL_DIR

IMAGE_EXT = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}


def extract_flat(archive: Path, target: Path):
    """Extracts only the images, with short file names.

    kagglehub's own extraction fails on Windows for datasets with long folder
    names (MAX_PATH), so the folder path is flattened into one short level.
    """
    with zipfile.ZipFile(archive) as z:
        names = [n for n in z.namelist() if Path(n).suffix.lower() in IMAGE_EXT]
        for i, n in enumerate(names):
            folder = str(Path(n).parent).replace("\\", "/").replace("/", "__").replace(" ", "_")
            out = target / folder
            out.mkdir(parents=True, exist_ok=True)
            dst = out / f"{i:06d}{Path(n).suffix.lower()}"
            if not dst.exists():
                dst.write_bytes(z.read(n))


def download(ds):
    target = RAW_DIR / ds["name"]
    if target.exists() and any(target.iterdir()):
        print(f"[skip] {ds['name']} already downloaded")
        return
    target.mkdir(parents=True, exist_ok=True)
    if ds["kind"] == "kaggle":
        try:
            import kagglehub
        except ImportError:
            print(f"[warn] kagglehub missing (pip install kagglehub) - download {ds['url']} manually into {target}")
            return
        print(f"[kaggle] {ds['ref']}")
        for attempt in range(6):
            try:
                kagglehub.dataset_download(ds["ref"])
                break
            except Exception as e:  # extraction error on long paths still leaves the archive in the cache
                print(f"  attempt {attempt + 1}: {type(e).__name__}")
                time.sleep(5)
        cache = Path.home() / ".cache" / "kagglehub" / "datasets" / ds["ref"]
        archives = sorted(cache.glob("*.archive"))
        if not archives:
            print(f"[error] {ds['ref']}: download failed")
            return
        extract_flat(archives[-1], target)
    elif ds["kind"] == "git":
        print(f"[git] {ds['ref']}")
        subprocess.run(["git", "clone", "--depth", "1", ds["ref"], str(target)], check=False)
    else:
        print(f"[manual] {ds['name']}: download from {ds['url']} into {target}")


def categorize(rel_path: str, rules):
    """First matching keyword wins.  A rule mapped to None excludes the folder."""
    p = rel_path.lower()
    for keyword, category in rules:
        if keyword in p:
            return category
    return None


def sort_into_categories(seed=42):
    for c in CATEGORIES:
        (REAL_DIR / c).mkdir(parents=True, exist_ok=True)
    counts = {c: 0 for c in CATEGORIES}
    for ds in PUBLIC_DATASETS:
        src = RAW_DIR / ds["name"]
        if not src.exists():
            continue
        by_cat = {}
        for f in sorted(src.rglob("*")):
            if f.suffix.lower() not in IMAGE_EXT:
                continue
            cat = categorize(str(f.parent.relative_to(src)), ds["rules"])
            if cat is not None:
                by_cat.setdefault(cat, []).append(f)
        for cat, files in by_cat.items():
            cap = ds.get("cap", {}).get(cat)
            if cap and len(files) > cap:
                files = random.Random(seed).sample(files, cap)
            for f in files:
                # Content hash in the name => re-running never duplicates files.
                digest = hashlib.md5(f.read_bytes()).hexdigest()[:12]
                dst = REAL_DIR / cat / f"{ds['name']}_{digest}{f.suffix.lower()}"
                if not dst.exists():
                    shutil.copy2(f, dst)
                counts[cat] += 1
    print("\nReal images per category:")
    for c in CATEGORIES:
        total = len(list((REAL_DIR / c).glob("*")))
        print(f"  {c:<24} {total:>6}  (+{counts[c]} from public datasets)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--skip-download", action="store_true")
    args = ap.parse_args()
    if not args.skip_download:
        for ds in PUBLIC_DATASETS:
            try:
                download(ds)
            except Exception as e:  # keep going with the other datasets
                print(f"[error] {ds['name']}: {e}", file=sys.stderr)
    sort_into_categories()


if __name__ == "__main__":
    main()
