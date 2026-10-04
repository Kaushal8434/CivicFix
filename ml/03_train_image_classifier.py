"""
Step 3 - Train the photo-recognition model.

Transfer learning: MobileNetV3-Large pre-trained on ImageNet is fine-tuned on
  real photos     (data/real/<category>/)      - public datasets + your own
  synthetic photos (data/synthetic/<category>/) - Stable Diffusion output

Validation/test sets use ONLY real photos, so the reported accuracy reflects
how the model behaves on genuine citizen photos, not on generated ones.

Output (copied automatically into the Android app's assets/):
  civic_classifier.onnx   - model, input [1,3,224,224] float32, output logits [1,8]
  image_labels.json       - label order + preprocessing constants
  output/image_metrics.json, output/confusion_matrix.csv

Usage:
  python 03_train_image_classifier.py --epochs 12
"""
import argparse
import json
import random
import shutil
from pathlib import Path

import numpy as np
import torch
from torch import nn
from torch.utils.data import DataLoader, Dataset, WeightedRandomSampler
from torchvision import models, transforms
from PIL import Image

from config import (ANDROID_ASSETS, CATEGORIES, IMAGENET_MEAN, IMAGENET_STD,
                    IMG_SIZE, OUT_DIR, REAL_DIR, SYNTH_DIR)

IMAGE_EXT = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}


def list_images(root: Path):
    items = []
    for idx, cat in enumerate(CATEGORIES):
        d = root / cat
        if d.exists():
            items += [(p, idx) for p in d.iterdir() if p.suffix.lower() in IMAGE_EXT]
    return items


class ImageList(Dataset):
    def __init__(self, items, tf):
        self.items, self.tf = items, tf

    def __len__(self):
        return len(self.items)

    def __getitem__(self, i):
        path, label = self.items[i]
        img = Image.open(path).convert("RGB")
        return self.tf(img), label


def split_real(items, val_frac, test_frac, seed):
    rng = random.Random(seed)
    by_cls = {}
    for it in items:
        by_cls.setdefault(it[1], []).append(it)
    train, val, test = [], [], []
    for cls_items in by_cls.values():
        rng.shuffle(cls_items)
        n = len(cls_items)
        n_test, n_val = int(n * test_frac), int(n * val_frac)
        test += cls_items[:n_test]
        val += cls_items[n_test:n_test + n_val]
        train += cls_items[n_test + n_val:]
    return train, val, test


def evaluate(model, loader, device):
    model.eval()
    n_cls = len(CATEGORIES)
    cm = np.zeros((n_cls, n_cls), dtype=int)
    with torch.no_grad():
        for x, y in loader:
            pred = model(x.to(device)).argmax(1).cpu()
            for t, p in zip(y.tolist(), pred.tolist()):
                cm[t, p] += 1
    acc = cm.trace() / max(cm.sum(), 1)
    return acc, cm


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--epochs", type=int, default=12)
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--lr", type=float, default=3e-4)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--no-synthetic", action="store_true", help="ablation: real photos only")
    args = ap.parse_args()

    torch.manual_seed(args.seed)
    device = "cuda" if torch.cuda.is_available() else "cpu"
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    real = list_images(REAL_DIR)
    synth = [] if args.no_synthetic else list_images(SYNTH_DIR)
    train_real, val, test = split_real(real, 0.15, 0.15, args.seed)
    train = train_real + synth
    if not train:
        raise SystemExit("No training images. Run 01_download_datasets.py and/or 02_generate_synthetic_images.py first.")
    print(f"train={len(train)} (real {len(train_real)}, synthetic {len(synth)})  val={len(val)}  test={len(test)}")
    if not val:
        print("[warn] no real images - validating on a synthetic hold-out; accuracy will be optimistic")
        random.Random(args.seed).shuffle(train)
        cut = max(1, len(train) // 7)
        val, test, train = train[:cut], train[cut:2 * cut], train[2 * cut:]

    train_tf = transforms.Compose([
        transforms.RandomResizedCrop(IMG_SIZE, scale=(0.6, 1.0)),
        transforms.RandomHorizontalFlip(),
        transforms.ColorJitter(0.3, 0.3, 0.3, 0.05),
        transforms.RandomRotation(10),
        transforms.ToTensor(),
        transforms.Normalize(IMAGENET_MEAN, IMAGENET_STD),
    ])
    eval_tf = transforms.Compose([
        transforms.Resize((IMG_SIZE, IMG_SIZE)),
        transforms.ToTensor(),
        transforms.Normalize(IMAGENET_MEAN, IMAGENET_STD),
    ])

    # Balance classes: rare classes are sampled more often.
    counts = np.bincount([y for _, y in train], minlength=len(CATEGORIES)).astype(float)
    weights = [1.0 / max(counts[y], 1) for _, y in train]
    sampler = WeightedRandomSampler(weights, num_samples=len(train), replacement=True)
    train_dl = DataLoader(ImageList(train, train_tf), batch_size=args.batch, sampler=sampler, num_workers=2)
    val_dl = DataLoader(ImageList(val, eval_tf), batch_size=args.batch, num_workers=2)
    test_dl = DataLoader(ImageList(test, eval_tf), batch_size=args.batch, num_workers=2)

    model = models.mobilenet_v3_large(weights=models.MobileNet_V3_Large_Weights.IMAGENET1K_V2)
    model.classifier[3] = nn.Linear(model.classifier[3].in_features, len(CATEGORIES))
    model.to(device)

    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=args.epochs)
    loss_fn = nn.CrossEntropyLoss(label_smoothing=0.1)

    best_acc, best_path = -1.0, OUT_DIR / "best_image_model.pt"
    for epoch in range(1, args.epochs + 1):
        model.train()
        # Freeze the backbone for the first 2 epochs so the new head settles first.
        for p in model.features.parameters():
            p.requires_grad = epoch > 2
        total, running = 0, 0.0
        for x, y in train_dl:
            x, y = x.to(device), y.to(device)
            opt.zero_grad()
            loss = loss_fn(model(x), y)
            loss.backward()
            opt.step()
            running += loss.item() * len(y)
            total += len(y)
        sched.step()
        val_acc, _ = evaluate(model, val_dl, device)
        print(f"epoch {epoch:2d}  loss {running / total:.4f}  val_acc {val_acc:.3f}")
        if val_acc > best_acc:
            best_acc = val_acc
            torch.save(model.state_dict(), best_path)

    model.load_state_dict(torch.load(best_path, map_location=device))
    test_acc, cm = evaluate(model, test_dl, device)
    print(f"\nTEST accuracy (real photos only): {test_acc:.3f}")

    metrics = {
        "model": "mobilenet_v3_large (ImageNet) fine-tuned",
        "train_real": len(train_real), "train_synthetic": len(synth),
        "val": len(val), "test": len(test),
        "best_val_accuracy": round(float(best_acc), 4),
        "test_accuracy": round(float(test_acc), 4),
        "per_class_recall": {c: round(float(cm[i, i] / max(cm[i].sum(), 1)), 3) for i, c in enumerate(CATEGORIES)},
    }
    (OUT_DIR / "image_metrics.json").write_text(json.dumps(metrics, indent=2))
    np.savetxt(OUT_DIR / "confusion_matrix.csv", cm, fmt="%d", delimiter=",",
               header=",".join(CATEGORIES), comments="")

    # ---- Export to ONNX for the Android app (ONNX Runtime Mobile) ----
    model.eval().cpu()
    dummy = torch.randn(1, 3, IMG_SIZE, IMG_SIZE)
    onnx_path = OUT_DIR / "civic_classifier.onnx"
    torch.onnx.export(model, dummy, str(onnx_path), input_names=["input"],
                      output_names=["logits"], opset_version=17, dynamo=False)
    labels = {"labels": CATEGORIES, "input_size": IMG_SIZE,
              "mean": IMAGENET_MEAN, "std": IMAGENET_STD, "layout": "NCHW",
              "test_accuracy": metrics["test_accuracy"]}
    (OUT_DIR / "image_labels.json").write_text(json.dumps(labels, indent=2))

    ANDROID_ASSETS.mkdir(parents=True, exist_ok=True)
    shutil.copy2(onnx_path, ANDROID_ASSETS / "civic_classifier.onnx")
    shutil.copy2(OUT_DIR / "image_labels.json", ANDROID_ASSETS / "image_labels.json")
    print(f"Exported model to {ANDROID_ASSETS}")


if __name__ == "__main__":
    main()
