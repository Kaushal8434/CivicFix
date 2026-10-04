"""
Step 1 - Download public civic-issue photo datasets and sort them into
data/real/<category>/ using the keyword rules in config.PUBLIC_DATASETS.

Requirements:
  * Kaggle datasets: `pip install kaggle` and put your API token in
    %USERPROFILE%\\.kaggle\\kaggle.json  (Kaggle -> Settings -> Create New Token)
  * Git datasets: `git` on PATH
  * TACO ("manual"): run `python download.py` from the TACO repo yourself,
    then copy its data/ folder to ml/data/raw/taco/

You can also drop your own photos (e.g. taken in your city) into
data/real/<category>/ - they are used exactly like the public data.

Usage:  python 01_download_datasets.py [--skip-download]
"""
import argparse
import hashlib
import shutil
import subprocess
import sys

from config import CATEGORIES, PUBLIC_DATASETS, RAW_DIR, REAL_DIR

IMAGE_EXT = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}


def download(ds):
    target = RAW_DIR / ds["name"]
    if target.exists() and any(target.iterdir()):
        print(f"[skip] {ds['name']} already downloaded")
        return
    target.mkdir(parents=True, exist_ok=True)
    if ds["kind"] == "kaggle":
        try:
            from kaggle.api.kaggle_api_extended import KaggleApi
        except ImportError:
            print(f"[warn] kaggle package missing - download {ds['url']} manually into {target}")
            return
        api = KaggleApi()
        api.authenticate()
        print(f"[kaggle] {ds['ref']}")
        api.dataset_download_files(ds["ref"], path=str(target), unzip=True)
    elif ds["kind"] == "git":
        print(f"[git] {ds['ref']}")
        subprocess.run(["git", "clone", "--depth", "1", ds["ref"], str(target)], check=False)
    else:
        print(f"[manual] {ds['name']}: download from {ds['url']} into {target}")


def categorize(rel_path: str, rules):
    p = rel_path.lower()
    for keyword, category in rules:
        if keyword in p:
            return category
    return None


def sort_into_categories():
    for c in CATEGORIES:
        (REAL_DIR / c).mkdir(parents=True, exist_ok=True)
    counts = {c: 0 for c in CATEGORIES}
    for ds in PUBLIC_DATASETS:
        src = RAW_DIR / ds["name"]
        if not src.exists():
            continue
        for f in src.rglob("*"):
            if f.suffix.lower() not in IMAGE_EXT:
                continue
            rel_dir = str(f.parent.relative_to(src))
            cat = categorize(rel_dir, ds["rules"])
            if cat is None:
                continue
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
