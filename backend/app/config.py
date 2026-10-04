"""Paths and settings for the CivicFix backend (override with environment variables)."""
import os
import secrets
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent          # backend/
REPO_DIR = BASE_DIR.parent                                   # CivicFix/
DATA_DIR = Path(os.environ.get("CIVICFIX_DATA", BASE_DIR / "data"))
MEDIA_DIR = DATA_DIR / "media"
MODELS_DIR = BASE_DIR / "models"
WEB_DIR = BASE_DIR / "web"
LOCATIONS_JSON = REPO_DIR / "android" / "app" / "src" / "main" / "assets" / "locations.json"

DATA_DIR.mkdir(parents=True, exist_ok=True)
MEDIA_DIR.mkdir(parents=True, exist_ok=True)

DATABASE_URL = os.environ.get("DATABASE_URL", f"sqlite:///{(DATA_DIR / 'civicfix.db').as_posix()}")


def _secret_key() -> str:
    """JWT signing key: from the environment, or generated once and kept in data/ (never committed)."""
    if os.environ.get("CIVICFIX_SECRET"):
        return os.environ["CIVICFIX_SECRET"]
    f = DATA_DIR / ".secret_key"
    if not f.exists():
        f.write_text(secrets.token_urlsafe(48))
    return f.read_text().strip()


SECRET_KEY = _secret_key()
TOKEN_DAYS = 14

# Duplicate detection (report: "same problem within 50-100 m")
DUPLICATE_RADIUS_M = 100
# Completion proof must be captured near the complaint and recently
PROOF_MAX_DISTANCE_M = 300
PROOF_MAX_AGE_MIN = 30
# How often the escalation engine checks deadlines
ESCALATION_INTERVAL_S = int(os.environ.get("CIVICFIX_ESCALATION_INTERVAL", "60"))
MAX_UPLOAD_MB = 60
