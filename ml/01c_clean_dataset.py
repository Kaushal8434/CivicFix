"""
Step 1c - Clean and merge all real photos with CLIP (zero-shot) before training.

Inputs
  data/real/<category>/      curated public datasets (01_download_datasets.py) + your own photos
  data/raw/web/<category>/   web photos (01b_collect_web_images.py) - noisy search results

Output
  data/clean/<category>/     images that passed the checks (used by 03_train_image_classifier.py)
  output/clean_report.csv    decision for every image (kept / relabelled / dropped + CLIP scores)

Checks (OpenAI CLIP ViT-L/14, prompts in config.CLIP_PROMPTS):
  * curated images  - kept unless CLIP thinks it is junk (map, document, portrait...)
                      or strongly disagrees with the folder label (label not in CLIP's top 3)
  * web images      - kept only if CLIP's top-1 class is the searched category with
                      p >= --web-min; if CLIP is very sure (p >= --relabel-min) that the
                      image shows a DIFFERENT civic problem, it is moved to that class
  * near-duplicates - CLIP embedding cosine similarity >= --dup, only the first copy is kept
                      (prevents the same photo landing in both train and test)

Usage:  python 01c_clean_dataset.py
"""
import argparse
import csv
import shutil
from pathlib import Path

import numpy as np
import torch
from PIL import Image

from config import CATEGORIES, CLIP_JUNK, CLIP_PROMPTS, DATA, OUT_DIR, REAL_DIR, WEB_DIR

CLEAN_DIR = DATA / "clean"
IMAGE_EXT = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}


def gather():
    items = []  # (path, label, kind)
    for kind, root in (("curated", REAL_DIR), ("web", WEB_DIR)):
        for cat in CATEGORIES:
            d = root / cat
            if d.exists():
                items += [(p, cat, kind) for p in sorted(d.iterdir()) if p.suffix.lower() in IMAGE_EXT]
    return items


@torch.no_grad()
def embed_images(model, preprocess, paths, device, batch=64):
    out, ok = [], []
    for i in range(0, len(paths), batch):
        imgs, idx = [], []
        for j, p in enumerate(paths[i:i + batch]):
            try:
                imgs.append(preprocess(Image.open(p).convert("RGB")))
                idx.append(i + j)
            except Exception:
                pass
        if imgs:
            x = torch.stack(imgs).to(device)
            with torch.autocast(device_type=device, enabled=device == "cuda"):
                f = model.encode_image(x)
            out.append(torch.nn.functional.normalize(f.float(), dim=-1).cpu())
            ok += idx
        print(f"\r  embedded {min(i + batch, len(paths))}/{len(paths)}", end="", flush=True)
    print()
    return torch.cat(out).numpy(), ok


@torch.no_grad()
def embed_texts(model, tokenizer, device):
    def enc(prompts):
        f = model.encode_text(tokenizer(prompts).to(device)).float()
        f = torch.nn.functional.normalize(f, dim=-1).mean(0)
        return torch.nn.functional.normalize(f, dim=0).cpu()
    rows = [enc(CLIP_PROMPTS[c]) for c in CATEGORIES] + [enc([f"a photo of {j}"]) for j in CLIP_JUNK]
    return torch.stack(rows).numpy()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="ViT-L-14-quickgelu")  # OpenAI weights use QuickGELU
    ap.add_argument("--pretrained", default="openai")
    ap.add_argument("--web-min", type=float, default=0.35)
    ap.add_argument("--relabel-min", type=float, default=0.75)
    ap.add_argument("--dup", type=float, default=0.95)
    args = ap.parse_args()

    import open_clip
    device = "cuda" if torch.cuda.is_available() else "cpu"
    model, _, preprocess = open_clip.create_model_and_transforms(args.model, pretrained=args.pretrained, device=device)
    tokenizer = open_clip.get_tokenizer(args.model)
    model.eval()

    items = gather()
    print(f"{len(items)} candidate images")
    emb, ok = embed_images(model, preprocess, [p for p, _, _ in items], device)
    items = [items[i] for i in ok]
    text = embed_texts(model, tokenizer, device)
    probs = torch.softmax(torch.from_numpy(100.0 * emb @ text.T), dim=1).numpy()
    n_cls = len(CATEGORIES)

    if CLEAN_DIR.exists():
        shutil.rmtree(CLEAN_DIR)
    for c in CATEGORIES:
        (CLEAN_DIR / c).mkdir(parents=True)

    kept_emb, n_kept = np.zeros_like(emb), 0
    report, counts = [], {c: {"curated": 0, "web": 0} for c in CATEGORIES}
    # Curated data first, so on duplicates the curated copy wins.
    order = sorted(range(len(items)), key=lambda i: items[i][2] != "curated")
    for i in order:
        path, label, kind = items[i]
        p = probs[i]
        top = int(p.argmax())
        top_name = CATEGORIES[top] if top < n_cls else "junk:" + CLIP_JUNK[top - n_cls]
        civic = p[:n_cls]
        li = CATEGORIES.index(label)
        final, reason = None, ""

        if top >= n_cls and p[top] >= 0.5:
            reason = "junk"
        elif kind == "curated":
            rank = int((civic > civic[li]).sum())
            final, reason = (label, "ok") if rank < 3 else (None, f"disagree(rank {rank + 1})")
        else:
            best = int(civic.argmax())
            if best == li and civic[li] >= args.web_min:
                final, reason = label, "ok"
            elif best != li and CATEGORIES[best] != "other" and civic[best] >= args.relabel_min:
                final, reason = CATEGORIES[best], f"relabel {label}->{CATEGORIES[best]}"
            else:
                reason = f"low({civic[li]:.2f})"

        if final is not None and n_kept:
            if (kept_emb[:n_kept] @ emb[i]).max() >= args.dup:
                final, reason = None, "duplicate"
        if final is not None:
            kept_emb[n_kept] = emb[i]
            n_kept += 1
            src = "web" if kind == "web" else path.name.split("_")[0]
            # Saved at <= 512 px: plenty for the 224 px model, and full-resolution
            # dataset photos would otherwise exhaust RAM in the training data loader.
            img = Image.open(path).convert("RGB")
            img.thumbnail((512, 512))
            img.save(CLEAN_DIR / final / f"{kind}_{src}_{path.stem}.jpg", quality=92)
            counts[final][kind] += 1
        report.append({"file": str(path.relative_to(DATA)), "kind": kind, "label": label, "final": final or "",
                       "decision": reason, "clip_top": top_name, "clip_p_top": round(float(p[top]), 3),
                       "clip_p_label": round(float(civic[li]), 3)})

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    with (OUT_DIR / "clean_report.csv").open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(report[0].keys()))
        w.writeheader()
        w.writerows(report)

    print(f"\n{'category':<24}{'curated':>9}{'web':>7}{'total':>8}")
    for c in CATEGORIES:
        print(f"{c:<24}{counts[c]['curated']:>9}{counts[c]['web']:>7}{sum(counts[c].values()):>8}")
    dropped = sum(1 for r in report if not r["final"])
    print(f"\nkept {len(report) - dropped}, dropped {dropped}  ->  {CLEAN_DIR}")


if __name__ == "__main__":
    main()
