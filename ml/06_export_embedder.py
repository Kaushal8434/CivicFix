"""
Step 6 - Export a photo "fingerprint" (embedding) model for duplicate detection.

Takes the trained photo classifier (output/best_image_model.pt from step 3) and
exports everything except the last layer: the network maps a photo to a 1280-number
vector. Two photos of the same problem give vectors pointing in a similar
direction (high cosine similarity), so the backend can tell a citizen
"this looks like complaint CF-... 60 m away – support it instead?".

Output: backend/models/civic_embedder.onnx  (input [1,3,224,224], output [1,1280], L2-normalised)

Usage:  python 06_export_embedder.py
"""
import json

import numpy as np
import torch
from torch import nn
from torchvision import models

from config import CATEGORIES, IMG_SIZE, OUT_DIR, ROOT

BACKEND_MODELS = ROOT.parent / "backend" / "models"


class Embedder(nn.Module):
    def __init__(self, net):
        super().__init__()
        self.features, self.pool = net.features, net.avgpool
        self.proj = nn.Sequential(net.classifier[0], net.classifier[1])  # Linear 960->1280 + Hardswish

    def forward(self, x):
        v = self.proj(torch.flatten(self.pool(self.features(x)), 1))
        return nn.functional.normalize(v, dim=1)


def main():
    net = models.mobilenet_v3_large()
    net.classifier[3] = nn.Linear(net.classifier[3].in_features, len(CATEGORIES))
    net.load_state_dict(torch.load(OUT_DIR / "best_image_model.pt", map_location="cpu"))
    emb = Embedder(net).eval()

    BACKEND_MODELS.mkdir(parents=True, exist_ok=True)
    path = BACKEND_MODELS / "civic_embedder.onnx"
    torch.onnx.export(emb, torch.randn(1, 3, IMG_SIZE, IMG_SIZE), str(path), input_names=["input"],
                      output_names=["embedding"], opset_version=17, dynamo=False)

    import onnxruntime as ort
    x = torch.randn(1, 3, IMG_SIZE, IMG_SIZE)
    diff = np.abs(ort.InferenceSession(str(path)).run(None, {"input": x.numpy()})[0] - emb(x).detach().numpy()).max()
    print(f"exported {path}  (max |onnx - torch| = {diff:.2e})")
    (BACKEND_MODELS / "embedder.json").write_text(json.dumps({"dim": 1280, "input_size": IMG_SIZE}, indent=2))


if __name__ == "__main__":
    main()
