"""Distances and the City -> Zone -> Ward -> Locality tree (shared with the Android app's locations.json)."""
import json
import math
from functools import lru_cache

from .config import LOCATIONS_JSON


def distance_m(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    r = 6_371_000.0
    d_lat, d_lng = math.radians(lat2 - lat1), math.radians(lng2 - lng1)
    a = math.sin(d_lat / 2) ** 2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(d_lng / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


@lru_cache
def locations() -> dict:
    return json.loads(LOCATIONS_JSON.read_text(encoding="utf-8"))


@lru_cache
def all_localities() -> list[dict]:
    out = []
    for c in locations()["cities"]:
        for z in c["zones"]:
            for w in z["wards"]:
                for loc in w["localities"]:
                    out.append({"city_id": c["id"], "zone_id": z["id"], "ward_id": w["id"], "locality_id": loc["id"],
                                "lat": loc["lat"], "lng": loc["lng"],
                                "label": f"{loc['name']}, {w['name']}, {z['name']}, {c['name']}"})
    return out


def locality(locality_id: str) -> dict | None:
    return next((x for x in all_localities() if x["locality_id"] == locality_id), None)


def nearest_locality(lat: float, lng: float) -> tuple[dict, float]:
    best = min(all_localities(), key=lambda x: distance_m(lat, lng, x["lat"], x["lng"]))
    return best, distance_m(lat, lng, best["lat"], best["lng"])
