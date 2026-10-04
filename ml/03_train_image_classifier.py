"""
Step 3 - Train the photo-recognition model.

Transfer learning: MobileNetV3-Large pre-trained on ImageNet is fine-tuned on
  real photos      (data/clean/<category>/ from 01c_clean_dataset.py,
                    or data/real/<category>/ if the cleaning step was skipped)
  synthetic photos (data/synthetic/<category>/) - Stable Diffusion output, optional

Validation/test sets use ONLY real photos, so the reported accuracy reflects
how the model behaves on genuine citizen photos, not on generated ones.

Training recipe
  * TrivialAugmentWide + random crop/flip/blur/erasing (phone photos are messy)
  * class-balanced sampling, label smoothing 0.1, AdamW + warm-up + cosine LR
  * head-only for the first 2 epochs, then the whole network (backbone at 1/5 LR)
  * mixed precision on the GPU; best epoch chosen by validation macro-F1
  * temperature scaling on the validation set, folded into the exported model,
    so the probabilities shown in the app are calibrated

Output (copied automatically into the Android app's assets/):
  civic_classifier.onnx   - model, input [1,3,224,224] float32, output logits [1,8]
  image_labels.json       - label order + preprocessing constants + test metrics
  output/image_metrics.json, output/confusion_matrix.csv

Usage:
  python 03_train_image_classifier.py --epochs 25
"""
import argparse
import json
import random
import shutil

import numpy as np
import torch
from PIL import Image
from torch import nn
from torch.utils.data import DataLoader, Dataset, WeightedRandomSampler
from torchvision import models, transforms

from config import (ANDROID_ASSETS, CATEGORIES, DATA, IMAGENET_MEAN, IMAGENET_STD,
                    IMG_SIZE, OUT_DIR, REAL_DIR, SYNTH_DIR)

IMAGE_EXT = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}
CLEAN_DIR = DATA / "clean"


def list_images(root):
    items = []
    for idx, cat in enumerate(CATEGORIES):
        d = root / cat
        if d.exists():
            items += [(p, idx) for p in sorted(d.iterdir()) if p.suffix.lower() in IMAGE_EXT]
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
        n_test, n_val = max(1, int(n * test_frac)), max(1, int(n * val_frac))
        test += cls_items[:n_test]
        val += cls_items[n_test:n_test + n_val]
        train += cls_items[n_test + n_val:]
    return train, val, test


@torch.no_grad()
def predict(model, loader, device):
    model.eval()
    logits, labels = [], []
    for x, y in loader:
        with torch.autocast(device_type=device, enabled=device == "cuda"):
            out = model(x.to(device, non_blocking=True))
        logits.append(out.float().cpu())
        labels.append(y)
    return torch.cat(logits), torch.cat(labels)


def metrics_from(logits, labels):
    n = len(CATEGORIES)
    cm = np.zeros((n, n), dtype=int)
    for t, p in zip(labels.tolist(), logits.argmax(1).tolist()):
        cm[t, p] += 1
    recall = cm.diagonal() / np.maximum(cm.sum(1), 1)
    precision = cm.diagonal() / np.maximum(cm.sum(0), 1)
    f1 = 2 * precision * recall / np.maximum(precision + recall, 1e-9)
    present = cm.sum(1) > 0
    return {"accuracy": cm.trace() / max(cm.sum(), 1), "macro_f1": float(f1[present].mean()),
            "recall": recall, "precision": precision, "f1": f1, "cm": cm}


def fit_temperature(logits, labels):
    """Temperature scaling (Guo et al. 2017): one scalar that minimises val NLL."""
    log_t = torch.zeros(1, requires_grad=True)
    opt = torch.optim.LBFGS([log_t], lr=0.1, max_iter=100)
    nll = nn.CrossEntropyLoss()

    def closure():
        opt.zero_grad()
        loss = nll(logits / log_t.exp(), labels)
        loss.backward()
        return loss
    opt.step(closure)
    return float(log_t.detach().exp().clamp(0.5, 5.0))


class Calibrated(nn.Module):
    """Wraps the network so the exported logits are already divided by T."""
    def __init__(self, net, temperature):
        super().__init__()
        self.net, self.t = net, temperature

    def forward(self, x):
        return self.net(x) / self.t


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--epochs", type=int, default=25)
    ap.add_argument("--batch", type=int, default=48)
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--no-synthetic", action="store_true", help="ablation: real photos only")
    args = ap.parse_args()

    random.seed(args.seed)
    torch.manual_seed(args.seed)
    device = "cuda" if torch.cuda.is_available() else "cpu"
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    real_root = CLEAN_DIR if CLEAN_DIR.exists() and any(CLEAN_DIR.rglob("*.*")) else REAL_DIR
    real = list_images(real_root)
    synth = [] if args.no_synthetic else list_images(SYNTH_DIR)
    train_real, val, test = split_real(real, 0.12, 0.15, args.seed)
    train = train_real + synth
    if not train:
        raise SystemExit("No training images. Run 01_download_datasets.py / 01b / 01c first.")
    print(f"data: {real_root}")
    print(f"train={len(train)} (real {len(train_real)}, synthetic {len(synth)})  val={len(val)}  test={len(test)}")
    print("per class (train/val/test):")
    for i, c in enumerate(CATEGORIES):
        print(f"  {c:<24} {sum(y == i for _, y in train):>5} {sum(y == i for _, y in val):>5} {sum(y == i for _, y in test):>5}")

    norm = transforms.Normalize(IMAGENET_MEAN, IMAGENET_STD)
    train_tf = transforms.Compose([
        transforms.RandomResizedCrop(IMG_SIZE, scale=(0.45, 1.0), ratio=(0.6, 1.66)),
        transforms.RandomHorizontalFlip(),
        transforms.TrivialAugmentWide(),
        transforms.RandomApply([transforms.GaussianBlur(5, sigma=(0.1, 2.0))], p=0.2),
        transforms.ToTensor(),
        norm,
        transforms.RandomErasing(p=0.25, scale=(0.02, 0.15)),
    ])
    # Same as the app: the whole photo squeezed to 224x224 (no crop).
    eval_tf = transforms.Compose([transforms.Resize((IMG_SIZE, IMG_SIZE)), transforms.ToTensor(), norm])

    counts = np.bincount([y for _, y in train], minlength=len(CATEGORIES)).astype(float)
    # Square-root balancing: rare classes are sampled more often, without
    # repeating the few images of a tiny class dozens of times per epoch.
    weights = [1.0 / np.sqrt(max(counts[y], 1)) for _, y in train]
    sampler = WeightedRandomSampler(weights, num_samples=len(train), replacement=True)
    dl = dict(num_workers=args.workers, pin_memory=device == "cuda", persistent_workers=args.workers > 0)
    train_dl = DataLoader(ImageList(train, train_tf), batch_size=args.batch, sampler=sampler, drop_last=True, **dl)
    val_dl = DataLoader(ImageList(val, eval_tf), batch_size=64, **dl)
    test_dl = DataLoader(ImageList(test, eval_tf), batch_size=64, **dl)

    model = models.mobilenet_v3_large(weights=models.MobileNet_V3_Large_Weights.IMAGENET1K_V2)
    model.classifier[2] = nn.Dropout(0.3)
    model.classifier[3] = nn.Linear(model.classifier[3].in_features, len(CATEGORIES))
    model.to(device)

    opt = torch.optim.AdamW([
        {"params": model.features.parameters(), "lr": args.lr / 5},
        {"params": model.classifier.parameters(), "lr": args.lr},
    ], weight_decay=0.02)
    steps = args.epochs * len(train_dl)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=[args.lr / 5, args.lr], total_steps=steps, pct_start=0.1)
    loss_fn = nn.CrossEntropyLoss(label_smoothing=0.1)
    scaler = torch.amp.GradScaler(enabled=device == "cuda")

    best_f1, best_path = -1.0, OUT_DIR / "best_image_model.pt"
    for epoch in range(1, args.epochs + 1):
        model.train()
        for p in model.features.parameters():
            p.requires_grad = epoch > 2
        total, running = 0, 0.0
        for x, y in train_dl:
            x, y = x.to(device, non_blocking=True), y.to(device, non_blocking=True)
            opt.zero_grad(set_to_none=True)
            with torch.autocast(device_type=device, enabled=device == "cuda"):
                loss = loss_fn(model(x), y)
            scaler.scale(loss).backward()
            scaler.step(opt)
            scaler.update()
            sched.step()
            running += loss.item() * len(y)
            total += len(y)
        m = metrics_from(*predict(model, val_dl, device))
        flag = ""
        if m["macro_f1"] > best_f1:
            best_f1, flag = m["macro_f1"], "  *best*"
            torch.save(model.state_dict(), best_path)
        print(f"epoch {epoch:2d}  loss {running / total:.4f}  val_acc {m['accuracy']:.3f}  val_macroF1 {m['macro_f1']:.3f}{flag}", flush=True)

    model.load_state_dict(torch.load(best_path, map_location=device))
    val_logits, val_labels = predict(model, val_dl, device)
    temperature = fit_temperature(val_logits, val_labels)
    test_logits, test_labels = predict(model, test_dl, device)
    m = metrics_from(test_logits, test_labels)
    conf = torch.softmax(test_logits / temperature, 1).max(1).values
    correct = test_logits.argmax(1) == test_labels
    print(f"\nTEST (real photos only): accuracy {m['accuracy']:.3f}  macro-F1 {m['macro_f1']:.3f}  temperature {temperature:.2f}")
    for i, c in enumerate(CATEGORIES):
        print(f"  {c:<24} precision {m['precision'][i]:.2f}  recall {m['recall'][i]:.2f}  n={m['cm'][i].sum()}")
    hi = conf >= 0.45
    print(f"  predictions with confidence >= 45%: {hi.float().mean():.0%} of photos, {correct[hi].float().mean():.1%} correct")

    metrics = {
        "model": "mobilenet_v3_large (ImageNet) fine-tuned",
        "data": str(real_root.relative_to(DATA.parent)),
        "train_real": len(train_real), "train_synthetic": len(synth), "val": len(val), "test": len(test),
        "best_val_macro_f1": round(best_f1, 4),
        "test_accuracy": round(float(m["accuracy"]), 4),
        "test_macro_f1": round(m["macro_f1"], 4),
        "temperature": round(temperature, 3),
        "confident_share": round(float(hi.float().mean()), 3),
        "confident_accuracy": round(float(correct[hi].float().mean()), 4),
        "per_class": {c: {"precision": round(float(m["precision"][i]), 3), "recall": round(float(m["recall"][i]), 3),
                          "test_images": int(m["cm"][i].sum())} for i, c in enumerate(CATEGORIES)},
    }
    (OUT_DIR / "image_metrics.json").write_text(json.dumps(metrics, indent=2))
    np.savetxt(OUT_DIR / "confusion_matrix.csv", m["cm"], fmt="%d", delimiter=",",
               header=",".join(CATEGORIES), comments="")

    # ---- Export to ONNX for the Android app (ONNX Runtime Mobile) ----
    export = Calibrated(model.eval().cpu(), temperature).eval()
    dummy = torch.randn(1, 3, IMG_SIZE, IMG_SIZE)
    onnx_path = OUT_DIR / "civic_classifier.onnx"
    torch.onnx.export(export, dummy, str(onnx_path), input_names=["input"],
                      output_names=["logits"], opset_version=17, dynamo=False)

    import onnxruntime as ort
    sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    x, _ = next(iter(DataLoader(ImageList(test[:8], eval_tf), batch_size=8)))
    diff = max(float(np.abs(sess.run(None, {"input": x[i:i + 1].numpy()})[0] - export(x[i:i + 1]).detach().numpy()).max())
               for i in range(len(x)))
    print(f"ONNX check: max |onnx - torch| = {diff:.2e}")
    if diff > 1e-3:
        raise SystemExit("ONNX export mismatch - not copying the model into the app")

    labels = {"labels": CATEGORIES, "input_size": IMG_SIZE,
              "mean": IMAGENET_MEAN, "std": IMAGENET_STD, "layout": "NCHW", "resize": "squash",
              "test_accuracy": metrics["test_accuracy"], "test_macro_f1": metrics["test_macro_f1"],
              "test_images": len(test), "train_images": len(train), "temperature": metrics["temperature"],
              "per_class_recall": {c: v["recall"] for c, v in metrics["per_class"].items()}}
    (OUT_DIR / "image_labels.json").write_text(json.dumps(labels, indent=2))

    ANDROID_ASSETS.mkdir(parents=True, exist_ok=True)
    shutil.copy2(onnx_path, ANDROID_ASSETS / "civic_classifier.onnx")
    shutil.copy2(OUT_DIR / "image_labels.json", ANDROID_ASSETS / "image_labels.json")
    print(f"Exported model to {ANDROID_ASSETS}")


if __name__ == "__main__":
    main()
