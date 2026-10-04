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
            ("mixed", None),
            ("pothole", "pothole_road_damage"),
            ("damaged_road", "pothole_road_damage"),
            ("garbage", "garbage"),
            ("litter", "garbage"),
            ("sign", "damaged_infrastructure"),
            ("vandalism", "damaged_infrastructure"),
            ("parking", "road_blockage"),
        ],
        # The big classes are capped so they do not drown out the rarer ones.
        "cap": {"pothole_road_damage": 1500, "damaged_infrastructure": 900, "garbage": 1500},
    },
    # --- Indian civic-issue / drainage datasets (added after real-world drainage photos were misclassified) ---
    {
        "name": "civic-issues-datasets",
        "kind": "kaggle",
        "ref": "prince8tiwari/civic-issues-datasets",
        "url": "https://www.kaggle.com/datasets/prince8tiwari/civic-issues-datasets",
        "rules": [("waterlogging", "drainage"), ("garbage", "garbage"), ("pothole", "pothole_road_damage"),
                  ("strretlight", "streetlight"), ("streetlight", "streetlight"), ("plain", "other")],
    },
    {
        "name": "waterlogging-and-flooding-images",
        "kind": "kaggle",
        "ref": "jasleenk2727/waterlogging-and-flooding-images",
        "url": "https://www.kaggle.com/datasets/jasleenk2727/waterlogging-and-flooding-images",
        "rules": [("", "drainage")],
    },
    {
        "name": "uncovered-gutters",
        "kind": "kaggle",
        "ref": "azeezmuhammed/uncovered-gutters-datasets",
        "url": "https://www.kaggle.com/datasets/azeezmuhammed/uncovered-gutters-datasets",
        "rules": [("", "drainage")],
    },
    {
        "name": "stagnant-water-1",
        "kind": "kaggle",
        "ref": "monumental610/stagnant-water-dataset2",
        "cap": {"drainage": 700},
        "url": "https://www.kaggle.com/datasets/monumental610/stagnant-water-dataset2",
        "rules": [("", "drainage")],
    },
    {
        "name": "stagnant-water-2",
        "kind": "kaggle",
        "ref": "insankamil1004/stagnant-water-dataset",
        "cap": {"drainage": 700},
        "url": "https://www.kaggle.com/datasets/insankamil1004/stagnant-water-dataset",
        "rules": [("", "drainage")],
    },
    {
        "name": "urban-community-issues",
        "kind": "kaggle",
        "ref": "rajeevpaudel1/urban-community-issues",
        "url": "https://www.kaggle.com/datasets/rajeevpaudel1/urban-community-issues",
        "rules": [("open_manhole", "drainage"), ("good_road", "other")],
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
# Openly-licensed web photos (Wikimedia Commons + Openverse), collected by
# 01b_collect_web_images.py.  Search results are noisy, so every image is
# checked by CLIP in 01c_clean_dataset.py before it is used for training.
#   "commons_cats":  Wikimedia Commons categories (walked 1 level deep)
#   "queries":       free-text searches sent to Commons and Openverse
# ---------------------------------------------------------------------------
WEB_DIR = DATA / "raw" / "web"

WEB_SOURCES = {
    "pothole_road_damage": {
        "commons_cats": ["Potholes", "Potholes in India", "Road damage", "Damaged roads", "Cracked asphalt"],
        "queries": ["pothole road", "pothole street India", "broken road surface", "damaged asphalt road", "road cracks potholes"],
    },
    "streetlight": {
        "commons_cats": ["Street lights in India", "Broken street lights", "Street lights at night", "Lamp posts", "Damaged lamp posts"],
        "queries": ["broken street light", "street light pole", "damaged lamp post", "street lamp night road", "fallen street light pole"],
    },
    "water_leakage": {
        "commons_cats": ["Water leaks", "Water main breaks", "Burst pipes", "Leaking pipes", "Water pipes in India", "Fire hydrants spraying water"],
        "queries": ["water main break street", "burst water pipe", "leaking water pipe", "water pipeline leak road", "broken water main flooding street", "leaking tap public",
                    "water leak", "pipe leak", "water main burst", "leaking hydrant", "water gushing from pipe", "water pipeline burst India",
                    "water wastage leaking pipeline", "broken pipe water spraying", "sinkhole water main"],
    },
    "drainage": {
        "commons_cats": ["Open drains", "Open drains in India", "Sewage", "Sewage overflows", "Drains in India", "Clogged drains", "Waterlogging", "Flooded streets in India", "Storm drains",
                         "Manholes without covers", "Open sewers", "Sewage in India", "Waterlogging in India", "Gutters (drainage)", "Nullahs"],
        "queries": ["open drain India", "clogged drain garbage", "sewage overflow street", "overflowing manhole sewage", "waterlogged street", "blocked storm drain", "nala drain city",
                    "open sewer", "overflowing sewer India", "gutter overflowing street", "manhole overflowing road", "open manhole road", "blocked drain plastic",
                    "nullah garbage", "waterlogged road monsoon India", "dirty drain water street", "drain choked garbage India", "sewage water on road"],
    },
    "garbage": {
        "commons_cats": ["Garbage in India", "Litter in India", "Garbage dumps", "Illegal dumping", "Overflowing waste containers", "Litter on streets"],
        "queries": ["garbage dump roadside", "overflowing garbage bin", "trash pile street", "illegal dumping", "litter street India"],
    },
    "road_blockage": {
        "commons_cats": ["Fallen trees", "Fallen trees on roads", "Road closures", "Road blocks", "Landslides on roads", "Construction debris", "Traffic barriers"],
        "queries": ["fallen tree blocking road", "tree fallen on street", "road blocked debris", "road closed barricade", "construction material blocking road", "landslide road blocked"],
    },
    "damaged_infrastructure": {
        "commons_cats": ["Damaged sidewalks", "Broken benches", "Damaged bridges", "Damaged railings", "Damaged road signs", "Broken footpaths"],
        "queries": ["damaged footpath tiles", "broken railing bridge", "broken public bench", "damaged road divider", "collapsed footpath"],
    },
    "other": {
        "commons_cats": ["Streets in India", "Roads in India", "Parks in India", "Clean streets", "Residential streets", "Markets in India"],
        "queries": ["clean street", "city street daytime", "empty road", "residential colony street India", "public park", "market street India", "building facade", "people on street"],
    },
}

# CLIP descriptions used to clean the data.  Several phrasings per class are
# averaged.  Images that look like none of the civic classes are matched by
# CLIP_JUNK and dropped (maps, documents, portraits, logos, ...).
CLIP_PROMPTS = {
    "pothole_road_damage": ["a photo of a pothole in a road", "a photo of a damaged, cracked asphalt road", "a photo of a broken road surface with holes"],
    "streetlight": ["a photo of a street light pole", "a photo of a broken street lamp", "a photo of a street lamp on a road"],
    "water_leakage": ["a photo of water leaking from a burst pipe", "a photo of a water main break flooding a street", "a photo of a leaking water pipe"],
    "drainage": ["a photo of an open drain with dirty water", "a photo of sewage overflowing on a street", "a photo of a clogged drain",
                 "a photo of a waterlogged flooded street", "a photo of an open or overflowing sewer manhole", "a photo of a gutter full of garbage and dirty water"],
    "garbage": ["a photo of a pile of garbage on the roadside", "a photo of an overflowing trash bin", "a photo of litter and waste dumped on a street"],
    "road_blockage": ["a photo of a fallen tree blocking a road", "a photo of a road blocked by debris", "a photo of a road closed with barricades", "a photo of cars parked blocking a street"],
    "damaged_infrastructure": ["a photo of a broken footpath", "a photo of a damaged road sign", "a photo of broken public property", "a photo of a damaged railing or bench"],
    "other": ["a photo of a clean city street", "a photo of a normal road in good condition", "a photo of a park", "a photo of a building", "a photo of people in a city"],
}
CLIP_JUNK = ["a map", "a document or text page", "a logo", "a portrait of a person", "a group of people at a ceremony",
             "a diagram or chart", "a painting", "a screenshot", "an indoor room", "a close-up of a face"]

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
