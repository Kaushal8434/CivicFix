"""
Server-side AI – the same models the Android app bundles:
  * civic_classifier.onnx  – photo -> 8 category probabilities (website submissions, after-photo check)
  * civic_embedder.onnx    – photo -> 1280-d fingerprint (duplicate detection)
Both are produced by ml/03_train_image_classifier.py and ml/06_export_embedder.py.
"""
import io
import json
import threading

import numpy as np
from PIL import Image, ImageOps

from .config import MODELS_DIR

_lock = threading.Lock()
_sessions = {}


def _session(name: str):
    with _lock:
        if name not in _sessions:
            path = MODELS_DIR / name
            if not path.exists():
                _sessions[name] = None
            else:
                import onnxruntime as ort
                _sessions[name] = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
        return _sessions[name]


def _meta():
    f = MODELS_DIR / "image_labels.json"
    return json.loads(f.read_text()) if f.exists() else None


def _tensor(image_bytes: bytes) -> np.ndarray:
    meta = _meta() or {"input_size": 224, "mean": [0.485, 0.456, 0.406], "std": [0.229, 0.224, 0.225]}
    size = meta["input_size"]
    img = ImageOps.exif_transpose(Image.open(io.BytesIO(image_bytes))).convert("RGB").resize((size, size), Image.BILINEAR)
    x = (np.asarray(img, dtype=np.float32) / 255.0 - np.array(meta["mean"], np.float32)) / np.array(meta["std"], np.float32)
    return x.transpose(2, 0, 1)[None]


def classify(image_bytes: bytes) -> dict[str, float] | None:
    s, meta = _session("civic_classifier.onnx"), _meta()
    if s is None or meta is None:
        return None
    x = _tensor(image_bytes)
    logits = (s.run(None, {"input": x})[0][0] + s.run(None, {"input": x[:, :, :, ::-1].copy()})[0][0]) / 2
    p = np.exp(logits - logits.max())
    p /= p.sum()
    return {label: float(v) for label, v in zip(meta["labels"], p)}


def embed(image_bytes: bytes) -> bytes | None:
    s = _session("civic_embedder.onnx")
    if s is None:
        return None
    v = s.run(None, {"input": _tensor(image_bytes)})[0][0].astype(np.float32)
    return v.tobytes()


def similarity(a: bytes | None, b: bytes | None) -> float | None:
    if not a or not b:
        return None
    va, vb = np.frombuffer(a, np.float32), np.frombuffer(b, np.float32)
    if va.shape != vb.shape:
        return None
    return float(np.dot(va, vb) / (np.linalg.norm(va) * np.linalg.norm(vb) + 1e-9))


def available() -> dict:
    return {"classifier": _session("civic_classifier.onnx") is not None,
            "embedder": _session("civic_embedder.onnx") is not None,
            "metrics": _meta()}
