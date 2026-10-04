"""
Shared configuration for the CivicFix ML pipeline.

The category keys below MUST match the keys used by the Android app
(android/app/src/main/assets/departments.json and Category.kt).
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DATA = ROOT / "data"
RAW_DIR = DATA / "raw"               # downloaded public datasets (untouched)
REAL_DIR = DATA / "real"             # real photos, sorted into data/real/<category>/
SYNTH_DIR = DATA / "synthetic"       # generative-AI images, data/synthetic/<category>/
TEXT_DIR = DATA / "text"
OUT_DIR = ROOT / "output"
ANDROID_ASSETS = ROOT.parent / "android" / "app" / "src" / "main" / "assets"

CATEGORIES = [
    "pothole_road_damage",
    "streetlight",
    "water_leakage",
    "drainage",
    "garbage",
    "road_blockage",
    "damaged_infrastructure",
    "other",
]

SEVERITIES = ["low", "medium", "high"]

IMG_SIZE = 224
IMAGENET_MEAN = [0.485, 0.456, 0.406]
IMAGENET_STD = [0.229, 0.224, 0.225]

# ---------------------------------------------------------------------------
# Public datasets.  Each entry maps folder-name keywords -> CivicFix category.
# Keywords are matched (lower-case) against the image's relative folder path;
# the first matching rule wins, images with no match are skipped.
# ---------------------------------------------------------------------------
PUBLIC_DATASETS = [
    {
        "name": "pothole-detection-dataset",
        "kind": "kaggle",
        "ref": "atulyakumar98/pothole-detection-dataset",
        "url": "https://www.kaggle.com/datasets/atulyakumar98/pothole-detection-dataset",
        "rules": [("pothole", "pothole_road_damage"), ("normal", "other")],
    },
    {
        "name": "road-issues-detection-dataset",
        "kind": "kaggle",
        "ref": "programmerrdai/road-issues-detection-dataset",
        "url": "https://www.kaggle.com/datasets/programmerrdai/road-issues-detection-dataset",
        "rules": [
            ("pothole", "pothole_road_damage"),
            ("damaged road", "pothole_road_damage"),
            ("damaged_road", "pothole_road_damage"),
            ("garbage", "garbage"),
            ("litter", "garbage"),
            ("sign", "damaged_infrastructure"),
        ],
    },
    {
        "name": "street-light-dataset",
        "kind": "git",
        "ref": "https://github.com/Team16Project/Street-Light-Dataset.git",
        "url": "https://github.com/Team16Project/Street-Light-Dataset",
        "rules": [("light", "streetlight"), ("", "streetlight")],
    },
    {
        "name": "taco",
        "kind": "manual",
        "ref": "https://github.com/pedropro/TACO",
        "url": "http://tacodataset.org/",
        "rules": [("", "garbage")],
    },
]

# ---------------------------------------------------------------------------
# Generative-AI prompts (Stable Diffusion).  Classes that have few or no public
# photos (water leakage, drainage, blockage, infrastructure) get more images.
# ---------------------------------------------------------------------------
SD_MODEL_ID = "stabilityai/sd-turbo"

SYNTH_PER_CLASS = {
    "pothole_road_damage": 150,
    "streetlight": 150,
    "water_leakage": 400,
    "drainage": 400,
    "garbage": 150,
    "road_blockage": 400,
    "damaged_infrastructure": 400,
    "other": 200,
}

SD_SUBJECTS = {
    "pothole_road_damage": [
        "a deep pothole filled with muddy water on an asphalt road",
        "a cracked and broken asphalt road with several potholes",
        "a large pothole in the middle of a busy city street",
        "crumbling road surface with exposed gravel and cracks",
    ],
    "streetlight": [
        "a broken street light pole with a smashed lamp at night",
        "a dark street at night with a non-working street lamp",
        "a tilted damaged streetlight pole beside a road",
        "a streetlight with hanging exposed electrical wires",
    ],
    "water_leakage": [
        "water gushing from a burst pipeline on a city road",
        "a leaking water supply pipe flooding the footpath",
        "clean water spraying out of a broken underground pipe on a street",
        "a puddle spreading from a leaking municipal water valve",
    ],
    "drainage": [
        "an overflowing open drain with dirty black water on a street",
        "a clogged roadside drain blocked with plastic and silt",
        "sewage overflowing from an open manhole onto the road",
        "a waterlogged street after a blocked storm drain",
    ],
    "garbage": [
        "a large pile of garbage dumped on the roadside",
        "an overflowing municipal garbage bin with trash scattered around",
        "plastic waste and litter heaped at a street corner",
        "garbage dump in an empty plot next to houses",
    ],
    "road_blockage": [
        "a fallen tree blocking a city road",
        "construction debris and sand piled across a street blocking traffic",
        "a road blocked by an abandoned broken vehicle and rubble",
        "a collapsed wall blocking a narrow lane",
    ],
    "damaged_infrastructure": [
        "a broken public bench and damaged footpath tiles in a park",
        "a damaged concrete footbridge railing with exposed rebar",
        "an open manhole with a missing cover on a footpath",
        "a broken and bent road sign and damaged traffic divider",
    ],
    "other": [
        "a clean well maintained city street in daylight",
        "a normal smooth asphalt road with no damage",
        "a tidy public park with benches and trees",
        "a working street light on a clean road at dusk",
    ],
}

SD_STYLES = [
    "photo taken on a smartphone, Indian city",
    "realistic photograph, daytime, overcast, India",
    "realistic photograph, evening light, residential colony",
    "street-level photo, harsh sunlight, urban India",
    "wide angle photograph, monsoon season, wet ground",
    "close-up realistic photograph, natural light",
]

SD_NEGATIVE = "cartoon, illustration, painting, text, watermark, logo, blurry, distorted"
