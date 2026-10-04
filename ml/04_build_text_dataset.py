"""
Step 4 - Build the complaint-text dataset (civic_complaints.csv).

Each row is a citizen-style complaint description labelled with a category
and a severity.  Rows are produced by combining hand-written phrase banks
(English + common Hinglish phrasing used in Indian cities), place words,
duration phrases and impact phrases.  The impact phrase decides severity:
  low    - cosmetic / inconvenience
  medium - daily disruption, health nuisance
  high   - safety risk: accidents, children, electrocution, flooding, main road

To use real complaints instead / in addition, append rows to
data/text/extra_complaints.csv with the same columns (text,category,severity);
they are merged automatically.

Usage:  python 04_build_text_dataset.py --rows 8000
"""
import argparse
import csv
import random

from config import CATEGORIES, TEXT_DIR

PROBLEMS = {
    "pothole_road_damage": [
        "there is a big pothole on the road", "road is completely broken",
        "huge potholes near the market road", "the road surface has cracked badly",
        "sadak toot gayi hai", "sadak mein bade gaddhe hain", "road has caved in",
        "tar road washed away after rain", "deep crater in the middle of the lane",
        "uneven road with loose gravel", "speed breaker broken and road damaged",
        "road dug up and never repaired", "multiple potholes on the main road",
    ],
    "streetlight": [
        "street light is not working", "streetlight has been off for many nights",
        "the lamp post is broken", "street lights are fused in our lane",
        "khamba ki light kharab hai", "street light band hai", "entire road is dark at night",
        "streetlight keeps flickering", "light pole is bent and lamp missing",
        "street lamp bulb has blown", "streetlight stays on during the day",
        "wires hanging from the street light pole",
    ],
    "water_leakage": [
        "water pipeline has burst", "drinking water is leaking from the pipe",
        "paani ki pipeline leak ho rahi hai", "water supply pipe broken and water wasting",
        "clean water flowing on the road from a leak", "main water line leaking",
        "water valve leaking continuously", "pipe leakage near the tank",
        "tap connection on the road is leaking", "water gushing out of the pipeline",
        "no water supply because of pipe leakage",
    ],
    "drainage": [
        "drain is blocked and overflowing", "sewer is overflowing on the street",
        "naali jam ho gayi hai", "gutter ka paani sadak par aa raha hai",
        "manhole overflowing with dirty water", "drainage choked with plastic",
        "sewage water stagnant in the lane", "open drain full of silt",
        "storm water drain blocked causing waterlogging", "nala overflow ho raha hai",
        "bad smell from the blocked sewer line",
    ],
    "garbage": [
        "garbage has not been collected", "huge pile of garbage on the roadside",
        "kooda nahi uthaya gaya", "kachra ka dher laga hai", "dustbin is overflowing",
        "people dumping waste in the empty plot", "garbage collection van not coming",
        "trash scattered all over the street", "dead animal and waste lying on road",
        "plastic waste burning near houses", "garbage point not cleaned for days",
    ],
    "road_blockage": [
        "tree has fallen and blocked the road", "construction material blocking the street",
        "road is blocked by debris", "abandoned vehicle blocking the lane",
        "encroachment has blocked the footpath", "rasta band hai malba pada hai",
        "sand and bricks dumped on the road", "wall collapsed onto the road",
        "illegal shop extension blocking traffic", "fallen electric pole across the road",
    ],
    "damaged_infrastructure": [
        "manhole cover is missing", "footpath tiles are broken",
        "park bench and swings are damaged", "bridge railing is broken",
        "road divider is damaged", "public toilet is broken and unusable",
        "bus stop shelter is damaged", "traffic signal pole is bent",
        "road sign board fallen down", "boundary wall of the park is broken",
        "footover bridge stairs are damaged",
    ],
    "other": [
        "stray dogs are creating nuisance", "noise from loudspeakers late at night",
        "mosquito breeding, fogging needed", "illegal parking in front of gate",
        "request for new speed breaker", "trees need trimming near the park",
        "wall painting has faded", "need a new bus stop here",
        "park grass needs cutting", "request to install cctv camera",
    ],
}

PLACES = [
    "near the school", "in front of the temple", "near the bus stand", "in our colony",
    "at the main crossing", "outside the hospital", "near the market", "in sector 5",
    "behind the park", "on the main road", "near ward office", "in gali number 3",
    "near the railway crossing", "opposite the petrol pump", "in the residential lane", "",
]

DURATIONS = [
    "for the last two weeks", "since yesterday", "for over a month", "since 3 days",
    "for many days", "since the last rain", "for a week now", "", "",
]

IMPACT = {
    "low": [
        "please look into it", "kindly get it fixed", "it looks bad", "minor inconvenience",
        "please repair when possible", "it is a small issue but needs attention", "",
    ],
    "medium": [
        "residents are facing daily problems", "it is causing traffic jams",
        "bad smell everywhere", "people are facing difficulty walking",
        "mosquitoes are breeding", "vehicles are getting damaged", "bahut pareshani ho rahi hai",
        "shopkeepers are complaining", "elderly people find it hard to pass",
    ],
    "high": [
        "two bike accidents already happened", "children could fall in and get hurt",
        "live wire is exposed and dangerous", "someone may get electrocuted",
        "the whole area is flooded", "ambulances cannot pass", "it is very dangerous at night",
        "a person was injured yesterday", "risk of serious accident on the highway",
        "water entering houses", "kabhi bhi haadsa ho sakta hai", "urgent action needed, life risk",
    ],
}

# Lexicon-based phrases ("<object> <defect>") widen the vocabulary so the
# model learns individual words, not just whole template sentences.
LEXICON = {
    "pothole_road_damage": (["road", "sadak", "street", "lane", "highway", "tar road", "asphalt", "colony road", "service road"],
                            ["pothole", "potholes", "gaddha", "crack", "cracks", "damaged", "broken", "uneven", "sunk", "caved", "tyre burst", "bumpy", "resurfacing needed", "patchwork failed"]),
    "streetlight": (["street light", "streetlight", "lamp", "pole light", "lamp post", "tube light on pole", "light", "bulb", "led light"],
                    ["not working", "fused", "off", "dark", "blinking", "flickering", "broken", "dead", "on in daytime", "wire hanging", "no light at night", "unsafe in darkness"]),
    "water_leakage": (["water pipe", "pipeline", "water line", "supply pipe", "water meter", "valve", "tap", "paani", "drinking water"],
                      ["leaking", "leak", "burst", "wastage", "cracked", "gushing", "contaminated", "dirty supply", "low pressure due to leak", "spraying"]),
    "drainage": (["drain", "nali", "naali", "sewer", "gutter", "nala", "manhole", "storm drain", "sewage line"],
                 ["blocked", "choked", "overflowing", "jam", "stinking", "waterlogging", "water not flowing", "stagnant water", "backflow", "silt filled"]),
    "garbage": (["garbage", "kooda", "kachra", "trash", "waste", "litter", "dustbin", "dump", "plastic waste", "rubbish"],
                ["not collected", "piled up", "scattered", "overflowing", "dumped", "burning", "smoke", "stinking", "lying around", "not lifted"]),
    "road_blockage": (["road", "lane", "street", "way", "rasta", "path", "entry gate"],
                      ["blocked by fallen tree", "blocked by debris", "blocked by sand pile", "blocked by vehicles", "encroached", "blocked by carts",
                       "obstructed", "vehicles cannot move", "blocked by rubble", "closed due to malba"]),
    "damaged_infrastructure": (["manhole cover", "footpath", "bench", "railing", "divider", "bus shelter", "public toilet", "swing", "sign board", "boundary wall", "slab", "park gate"],
                               ["missing", "broken", "damaged", "loose", "collapsed", "bent", "cracked", "open and uncovered", "rusted", "unusable"]),
    "other": (["stray dogs", "loud music", "noise", "mosquitoes", "illegal parking", "trees", "cctv", "stray cattle", "monkeys", "dj"],
              ["nuisance", "late at night", "need fogging", "request", "please plant", "need trimming", "install", "menace", "problem", "disturbance"]),
}

IMPACT["low"] += ["small issue", "not urgent", "whenever possible", "minor", "cosmetic problem", "please check"]
IMPACT["medium"] += ["hard to ride bikes", "foul odour", "health problem", "daily inconvenience", "many days pending",
                     "traffic slow", "residents upset", "shops affected", "waste of water"]
IMPACT["high"] += ["people may trip and fall", "accident risk", "unsafe for pedestrians", "breathing problems for residents", "shock hazard",
                   "traffic completely stopped", "homes flooded", "injury happened", "danger", "emergency", "very risky", "fatal"]

# Some problems are inherently more dangerous: bias their severity upward.
SEVERITY_PRIOR = {
    "pothole_road_damage": [0.25, 0.4, 0.35], "streetlight": [0.3, 0.45, 0.25],
    "water_leakage": [0.25, 0.5, 0.25], "drainage": [0.2, 0.5, 0.3],
    "garbage": [0.35, 0.5, 0.15], "road_blockage": [0.15, 0.4, 0.45],
    "damaged_infrastructure": [0.25, 0.4, 0.35], "other": [0.6, 0.35, 0.05],
}


def make_row(rng, cat):
    sev = rng.choices(["low", "medium", "high"], weights=SEVERITY_PRIOR[cat])[0]
    if rng.random() < 0.5:
        objs, defects = LEXICON[cat]
        problem = f"{rng.choice(objs)} {rng.choice(defects)}"
    else:
        problem = rng.choice(PROBLEMS[cat])
    parts = [problem, rng.choice(PLACES), rng.choice(DURATIONS)]
    impact = rng.choice(IMPACT[sev])
    text = " ".join(p for p in parts if p).strip()
    if impact:
        text += rng.choice([". ", ", ", " - "]) + impact
    if rng.random() < 0.3:
        text = text.capitalize()
    if rng.random() < 0.2:
        text += rng.choice([" please help", " sir please", " !!", "."])
    return text, cat, sev


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rows", type=int, default=8000)
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()
    rng = random.Random(args.seed)

    rows, seen = [], set()
    per_cat = args.rows // len(CATEGORIES)
    for cat in CATEGORIES:
        tries = 0
        n = 0
        while n < per_cat and tries < per_cat * 20:
            tries += 1
            r = make_row(rng, cat)
            if r[0].lower() in seen:
                continue
            seen.add(r[0].lower())
            rows.append(r)
            n += 1

    extra = TEXT_DIR / "extra_complaints.csv"
    if extra.exists():
        with open(extra, encoding="utf-8") as fh:
            for r in csv.DictReader(fh):
                if r["category"] in CATEGORIES:
                    rows.append((r["text"], r["category"], r["severity"]))

    rng.shuffle(rows)
    TEXT_DIR.mkdir(parents=True, exist_ok=True)
    out = TEXT_DIR / "civic_complaints.csv"
    with open(out, "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["text", "category", "severity"])
        w.writerows(rows)
    print(f"Wrote {len(rows)} rows to {out}")


if __name__ == "__main__":
    main()
