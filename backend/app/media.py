"""Saving uploaded photos / videos under data/media/ with safe, random file names."""
import secrets
from pathlib import Path

from fastapi import HTTPException, UploadFile

from .config import MAX_UPLOAD_MB, MEDIA_DIR

PHOTO_TYPES = {"image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp", "image/heic": ".heic"}
VIDEO_TYPES = {"video/mp4": ".mp4", "video/3gpp": ".3gp", "video/quicktime": ".mov", "video/webm": ".webm"}


async def save_upload(file: UploadFile, kind: str, complaint_id: str) -> tuple[str, bytes]:
    """Returns (relative path, bytes). kind = 'photo' | 'video'."""
    allowed = PHOTO_TYPES if kind == "photo" else VIDEO_TYPES
    ext = allowed.get((file.content_type or "").split(";")[0].lower())
    if ext is None:
        name_ext = Path(file.filename or "").suffix.lower()
        ext = name_ext if name_ext in allowed.values() else None
    if ext is None:
        raise HTTPException(400, f"Unsupported {kind} type: {file.content_type}")
    data = await file.read()
    if len(data) > MAX_UPLOAD_MB * 1024 * 1024:
        raise HTTPException(413, f"{kind.title()} is larger than {MAX_UPLOAD_MB} MB")
    if not data:
        raise HTTPException(400, f"Empty {kind}")
    folder = MEDIA_DIR / complaint_id
    folder.mkdir(parents=True, exist_ok=True)
    rel = f"{complaint_id}/{kind}_{secrets.token_hex(6)}{ext}"
    (MEDIA_DIR / rel).write_bytes(data)
    return rel, data


def delete_media(complaint_id: str):
    folder = MEDIA_DIR / complaint_id
    if folder.is_dir() and folder.resolve().parent == MEDIA_DIR.resolve():
        for f in folder.iterdir():
            f.unlink(missing_ok=True)
        folder.rmdir()
