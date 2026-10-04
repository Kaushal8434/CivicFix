"""Sign-in / registration, the current user, metadata and AI endpoints."""
import re

from fastapi import APIRouter, Depends, File, HTTPException, UploadFile
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from . import ai
from .db import get_db
from .geo import locations
from .models import Notification, User
from .security import create_token, current_user, hash_password, verify_password
from .services import now_ms
from .workflows import CATEGORIES, SAFETY_KEYWORDS, WORKFLOWS

router = APIRouter(prefix="/api")


class RegisterIn(BaseModel):
    name: str = Field(min_length=3, max_length=40)
    password: str = Field(min_length=6, max_length=128)
    phone: str | None = None


class LoginIn(BaseModel):
    name: str
    password: str


def user_json(u: User) -> dict:
    return {"id": u.id, "username": u.username, "name": u.display_name, "phone": u.phone, "role": u.role,
            "departmentId": u.department_id, "level": u.level, "designation": u.designation}


def normalise_name(name: str) -> str:
    return re.sub(r"\s+", " ", name.strip())


@router.post("/auth/register")
def register(body: RegisterIn, db: Session = Depends(get_db)):
    """Public sign-up creates CITIZEN accounts only. Staff accounts are created by the admin."""
    name = normalise_name(body.name)
    phone = re.sub(r"[^\d+]", "", body.phone or "") or None
    if phone and not (10 <= sum(ch.isdigit() for ch in phone) <= 13):
        raise HTTPException(400, "Phone number should have 10 digits (or leave it empty)")
    if db.scalar(select(User).where(User.username == name.lower())):
        raise HTTPException(409, f'An account named "{name}" already exists – sign in instead')
    u = User(username=name.lower(), display_name=name, phone=phone, role="citizen",
             password_hash=hash_password(body.password), created_at=now_ms(db))
    db.add(u)
    db.commit()
    return {"token": create_token(u), "user": user_json(u)}


@router.post("/auth/login")
def login(body: LoginIn, db: Session = Depends(get_db)):
    u = db.scalar(select(User).where(User.username == normalise_name(body.name).lower()))
    if u is None or not verify_password(body.password, u.password_hash):
        raise HTTPException(401, "Wrong name or password")
    if not u.active:
        raise HTTPException(403, "This account has been disabled")
    return {"token": create_token(u), "user": user_json(u)}


@router.get("/me")
def me(user: User = Depends(current_user), db: Session = Depends(get_db)):
    unread = db.query(Notification).filter(Notification.user_id == user.id, Notification.read.is_(False)).count()
    return {"user": user_json(user), "unreadNotifications": unread, "serverTime": now_ms(db)}


@router.get("/meta")
def meta(db: Session = Depends(get_db)):
    return {"categories": CATEGORIES, "workflows": WORKFLOWS, "locations": locations(), "safetyKeywords": SAFETY_KEYWORDS,
            "ai": {k: v for k, v in ai.available().items()}, "serverTime": now_ms(db)}


@router.post("/ai/classify")
async def classify(photo: UploadFile = File(...)):
    probs = ai.classify(await photo.read())
    if probs is None:
        raise HTTPException(503, "Photo model not installed on the server")
    ranked = sorted(probs.items(), key=lambda kv: -kv[1])
    return {"ranked": [{"category": k, "label": CATEGORIES[k]["label"], "p": round(p, 4)} for k, p in ranked]}
