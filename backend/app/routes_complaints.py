"""Complaint life cycle: report -> assign -> work -> proof -> citizen verification."""
from fastapi import APIRouter, Depends, File, Form, HTTPException, Query, UploadFile
from pydantic import BaseModel
from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from . import ai
from .config import DUPLICATE_RADIUS_M, PROOF_MAX_AGE_MIN, PROOF_MAX_DISTANCE_M
from .db import get_db
from .geo import distance_m, locality, nearest_locality
from .media import save_upload
from .models import OPEN_STATUSES, Complaint, User
from .security import current_user, current_user_optional, is_staff, is_supervisor
from .services import (HOUR_MS, _apply_stage, add_event, add_support, auto_assign, complaint_json, find_duplicates, fmt,
                       label, now_ms, new_complaint_id, notify)
from .workflows import CATEGORIES, SAFETY_KEYWORDS, WORKFLOWS, department_for, sla_hours

router = APIRouter(prefix="/api")


def get_complaint(db: Session, cid: str) -> Complaint:
    c = db.get(Complaint, cid)
    if c is None:
        raise HTTPException(404, "Complaint not found")
    return c


def can_work_on(user: User, c: Complaint) -> bool:
    if user.role == "admin":
        return True
    if user.id in (c.officer_id, c.supervisor_id, c.escalated_to_id):
        return True
    return user.role == "supervisor" and user.department_id == c.department_id and user.level >= 2


# ------------------------------------------------------------------ report
@router.post("/complaints/check-duplicates")
async def check_duplicates(lat: float = Form(...), lng: float = Form(...), category: str | None = Form(None),
                           radius: float = Form(DUPLICATE_RADIUS_M), photo: UploadFile | None = File(None),
                           user: User | None = Depends(current_user_optional), db: Session = Depends(get_db)):
    """'Is this already reported?' – open complaints within 50-100 m that look like the same problem."""
    emb = ai.embed(await photo.read()) if photo is not None else None
    radius = max(20.0, min(radius, 300.0))
    found = find_duplicates(db, lat, lng, category, emb, radius)
    return {"radius": radius, "matches": [
        {**complaint_json(db, d["complaint"], user, full=False), "distanceM": d["distance_m"], "similarity": d["similarity"],
         "sameCategory": d["same_category"], "verdict": d["verdict"]} for d in found]}


@router.post("/complaints")
async def create_complaint(
    lat: float = Form(...), lng: float = Form(...), description: str = Form(""), address: str = Form(""),
    landmark: str = Form(""), category: str | None = Form(None), severity: str | None = Form(None),
    locality_id: str | None = Form(None), ai_summary: str | None = Form(None),
    photo: UploadFile | None = File(None), user: User = Depends(current_user), db: Session = Depends(get_db),
):
    if user.role != "citizen" and user.role != "admin":
        raise HTTPException(403, "Only citizens can report complaints")
    now = now_ms(db)
    cid = new_complaint_id(db, now)
    photo_path, emb, probs = None, None, None
    if photo is not None and photo.filename:
        photo_path, data = await save_upload(photo, "photo", cid)
        emb, probs = ai.embed(data), ai.classify(data)

    if category not in CATEGORIES:
        # Website users may leave it to the AI.
        category = max(probs, key=probs.get) if probs else "other"
    text = description.lower()
    safety = next((k for k in SAFETY_KEYWORDS if k in text), None)
    severity = "high" if safety else (severity if severity in ("low", "medium", "high") else "medium")

    loc = locality(locality_id) if locality_id else None
    if loc is None:
        loc, _ = nearest_locality(lat, lng)
    dept = department_for(category)
    sla = sla_hours(category, severity)
    if probs and not ai_summary:
        top = max(probs, key=probs.get)
        ai_summary = f"AI (photo): {label(top)} {round(probs[top] * 100)}%" + ("" if top == category else f" – citizen chose {label(category)}")

    c = Complaint(id=cid, citizen_id=user.id, citizen_name=user.display_name, citizen_phone=user.phone,
                  category=category, severity=severity, description=description.strip()[:2000], address=address.strip()[:500],
                  landmark=landmark.strip()[:200], lat=lat, lng=lng, city_id=loc["city_id"], zone_id=loc["zone_id"],
                  ward_id=loc["ward_id"], locality_id=loc["locality_id"], location_label=loc["label"],
                  department_id=dept, status="NEW", created_at=now, sla_hours=sla, due_at=now + sla * HOUR_MS,
                  ai_category=max(probs, key=probs.get) if probs else None,
                  ai_confidence=max(probs.values()) if probs else None, ai_summary=ai_summary,
                  before_photo=photo_path, embedding=emb)
    db.add(c)
    db.flush()
    add_event(db, c, "Complaint submitted", f"Complaint ID {cid}" + (f" · safety keyword “{safety}” → HIGH" if safety else ""), user.display_name, at=now)
    auto_assign(db, c)
    db.commit()
    db.refresh(c)
    return complaint_json(db, c, user)


# ------------------------------------------------------------------ read
@router.get("/complaints")
def list_complaints(scope: str = Query("mine"), status: str | None = None, department: str | None = None,
                    overdue: bool = False, limit: int = Query(200, le=1000),
                    user: User = Depends(current_user), db: Session = Depends(get_db)):
    """scope: mine | community | assigned (officer) | team (supervisor) | all (admin / senior supervisor)."""
    q = select(Complaint)
    if scope == "mine":
        q = q.where(or_(Complaint.citizen_id == user.id, Complaint.supports.any(user_id=user.id)))
    elif scope == "assigned":
        q = q.where(or_(Complaint.officer_id == user.id, Complaint.escalated_to_id == user.id))
    elif scope == "team":
        if user.role not in ("supervisor", "admin"):
            raise HTTPException(403, "Supervisors only")
        if user.role == "supervisor":
            cond = [Complaint.supervisor_id == user.id, Complaint.escalated_to_id == user.id]
            if user.level >= 2:
                cond.append(Complaint.department_id == user.department_id)
            q = q.where(or_(*cond))
    elif scope == "all":
        if user.role != "admin":
            raise HTTPException(403, "Admin only")
    elif scope != "community":
        raise HTTPException(400, "Unknown scope")
    if status:
        q = q.where(Complaint.status.in_(status.split(",")))
    if department:
        q = q.where(Complaint.department_id == department)
    rows = list(db.scalars(q.order_by(Complaint.created_at.desc()).limit(limit)))
    now = now_ms(db)
    if overdue:
        rows = [c for c in rows if c.status in OPEN_STATUSES and c.due_at < now]
    return [complaint_json(db, c, user, full=False) for c in rows]


@router.get("/public/complaints")
def public_complaints(db: Session = Depends(get_db)):
    """Anonymous map feed for the website: no names or phone numbers."""
    rows = db.scalars(select(Complaint).order_by(Complaint.created_at.desc()).limit(500))
    now = now_ms(db)
    return [{"id": c.id, "category": c.category, "categoryLabel": label(c.category), "status": c.status,
             "lat": c.lat, "lng": c.lng, "address": c.address or c.location_label, "createdAt": c.created_at,
             "supportCount": len(c.supports), "overdue": c.status in OPEN_STATUSES and now > c.due_at,
             "agency": WORKFLOWS[c.department_id]["agency"]} for c in rows]


@router.get("/complaints/{cid}")
def get_one(cid: str, user: User = Depends(current_user), db: Session = Depends(get_db)):
    return complaint_json(db, get_complaint(db, cid), user)


# ------------------------------------------------------------------ citizen actions
@router.post("/complaints/{cid}/support")
def support(cid: str, user: User = Depends(current_user), db: Session = Depends(get_db)):
    c = get_complaint(db, cid)
    if c.status not in OPEN_STATUSES:
        raise HTTPException(400, "This complaint is already resolved")
    if add_support(db, c, user):
        notify(db, c.citizen_id, c, "info", f"+1 on your complaint {c.id}", f"{user.display_name} is facing the same problem.")
        db.commit()
    db.refresh(c)
    return complaint_json(db, c, user)


class VerifyIn(BaseModel):
    fixed: bool
    reason: str = ""


@router.post("/complaints/{cid}/verify")
def verify(cid: str, body: VerifyIn, user: User = Depends(current_user), db: Session = Depends(get_db)):
    c = get_complaint(db, cid)
    if c.citizen_id != user.id:
        raise HTTPException(403, "Only the citizen who reported it can verify")
    if c.status != "RESOLVED":
        raise HTTPException(400, "Complaint is not waiting for verification")
    now = now_ms(db)
    if body.fixed:
        c.status, c.closed_at = "CLOSED", now
        add_event(db, c, "Verified fixed", "Citizen confirmed the repair", user.display_name)
        for uid in {c.officer_id, c.supervisor_id, c.escalated_to_id}:
            notify(db, uid, c, "info", f"✅ {c.id} verified fixed", "The citizen confirmed the problem is fixed.")
    else:
        c.status, c.reopen_count, c.resolved_at = "REOPENED", c.reopen_count + 1, None
        c.due_at = now + max(1, c.sla_hours // 2) * HOUR_MS
        add_event(db, c, "Citizen: NOT fixed – reopened", body.reason or "Problem still exists", user.display_name)
        c.escalation_stage = min(3, c.escalation_stage + 1)
        _apply_stage(db, c, now)
        add_event(db, c, "New deadline", fmt(c.due_at))
    db.commit()
    db.refresh(c)
    return complaint_json(db, c, user)


# ------------------------------------------------------------------ staff actions
class StartIn(BaseModel):
    team: str | None = None


@router.post("/complaints/{cid}/start")
def start(cid: str, body: StartIn, user: User = Depends(is_staff), db: Session = Depends(get_db)):
    c = get_complaint(db, cid)
    if not can_work_on(user, c):
        raise HTTPException(403, "Not your complaint")
    if c.status not in ("NEW", "ASSIGNED", "REOPENED"):
        raise HTTPException(400, f"Cannot start work when status is {c.status}")
    c.status, c.team = "IN_PROGRESS", body.team or c.team
    add_event(db, c, "Work started", f"{c.team or 'Field team'} is on site", f"{user.display_name} ({user.designation or user.role})")
    notify(db, c.citizen_id, c, "info", f"Work started on {c.id}", "The department has started working on your complaint.")
    db.commit()
    db.refresh(c)
    return complaint_json(db, c, user)


class AssignIn(BaseModel):
    officer_id: int
    due_at: int | None = None
    team: str | None = None
    note: str = ""


@router.post("/complaints/{cid}/assign")
def assign(cid: str, body: AssignIn, user: User = Depends(is_supervisor), db: Session = Depends(get_db)):
    """Supervisor assigns (or re-assigns) a task to an officer, optionally with a new deadline."""
    c = get_complaint(db, cid)
    if not can_work_on(user, c) and not (user.role == "supervisor" and user.department_id == c.department_id):
        raise HTTPException(403, "Not your department")
    officer = db.get(User, body.officer_id)
    if officer is None or officer.role not in ("officer", "supervisor") or officer.department_id != c.department_id:
        raise HTTPException(400, "Choose an officer of the responsible department")
    now = now_ms(db)
    c.officer_id, c.team = officer.id, body.team or c.team
    if c.status in ("NEW", "REOPENED"):
        c.status = "ASSIGNED"
    if body.due_at:
        if body.due_at <= now:
            raise HTTPException(400, "Deadline must be in the future")
        c.due_at = body.due_at
        c.escalation_stage = 0 if c.escalation_stage < 2 else c.escalation_stage
    add_event(db, c, "Assigned by supervisor", f"To {officer.display_name} ({officer.designation}). Deadline {fmt(c.due_at)}. {body.note}".strip(),
              f"{user.display_name} ({user.designation or user.role})")
    notify(db, officer.id, c, "info", f"Task assigned: {c.id}",
           f"{label(c.category)} at {c.address or c.location_label}. Deadline {fmt(c.due_at)}. {body.note}".strip())
    db.commit()
    db.refresh(c)
    return complaint_json(db, c, user)


@router.post("/complaints/{cid}/resolve")
async def resolve(cid: str, photo: UploadFile = File(...), video: UploadFile = File(...), action_taken: str = Form(...),
                  lat: float | None = Form(None), lng: float | None = Form(None), captured_at: int | None = Form(None),
                  user: User = Depends(is_staff), db: Session = Depends(get_db)):
    """Completion needs a LIVE photo and video of the finished work, taken at the spot."""
    c = get_complaint(db, cid)
    if not can_work_on(user, c):
        raise HTTPException(403, "Not your complaint")
    if c.status not in OPEN_STATUSES:
        raise HTTPException(400, "Complaint is not open")
    if not action_taken.strip():
        raise HTTPException(400, "Describe the action taken")
    photo_path, photo_bytes = await save_upload(photo, "photo", c.id)
    video_path, _ = await save_upload(video, "video", c.id)
    now = now_ms(db)

    checks = []
    if lat is not None and lng is not None and c.lat is not None:
        dist = distance_m(lat, lng, c.lat, c.lng)
        checks.append(f"{'✅' if dist <= PROOF_MAX_DISTANCE_M else '⚠️'} taken {round(dist)} m from the complaint location")
    else:
        checks.append("⚠️ no GPS location attached to the proof")
    if captured_at:
        age_min = (now - captured_at) / 60000
        checks.append(f"{'✅' if -5 <= age_min <= PROOF_MAX_AGE_MIN else '⚠️'} captured {max(0, round(age_min))} min before upload")
    else:
        checks.append("⚠️ capture time unknown")
    c.proof_check = " · ".join(checks)

    probs = ai.classify(photo_bytes)
    if probs:
        still = probs.get(c.category, 0.0)
        top = max(probs, key=probs.get)
        c.after_photo_ai_check = (f"⚠️ AI: after-photo still looks like {label(c.category)} ({round(still * 100)}%) – verify carefully."
                                  if c.category != "other" and still >= 0.6 else
                                  f"✅ AI: problem no longer visible (top guess {label(top)} {round(probs[top] * 100)}%).")
    c.after_photo, c.after_video, c.action_taken = photo_path, video_path, action_taken.strip()[:2000]
    c.status, c.resolved_at = "RESOLVED", now
    add_event(db, c, "Marked resolved with live photo + video", f"{c.action_taken}. Proof: {c.proof_check}",
              f"{user.display_name} ({user.designation or user.role})")
    notify(db, c.citizen_id, c, "resolved", f"Complaint {c.id} resolved",
           "The department uploaded photo and video of the completed work. Is the problem actually fixed? Please verify.")
    for s in c.supports:
        notify(db, s.user_id, c, "resolved", f"Complaint {c.id} resolved", "A problem you supported has been marked resolved.")
    notify(db, c.supervisor_id, c, "info", f"{c.id} resolved by {user.display_name}", c.proof_check)
    db.commit()
    db.refresh(c)
    return complaint_json(db, c, user)


class CategoryIn(BaseModel):
    category: str


@router.post("/complaints/{cid}/category")
def correct_category(cid: str, body: CategoryIn, user: User = Depends(is_staff), db: Session = Depends(get_db)):
    """Officer corrects the category – the complaint is re-routed to the right department."""
    c = get_complaint(db, cid)
    if body.category not in CATEGORIES:
        raise HTTPException(400, "Unknown category")
    if not can_work_on(user, c):
        raise HTTPException(403, "Not your complaint")
    old = label(c.category)
    c.category, c.department_id = body.category, department_for(body.category)
    c.escalated_to_id, c.escalation_stage = None, 0
    c.sla_hours = sla_hours(c.category, c.severity)
    c.due_at = now_ms(db) + c.sla_hours * HOUR_MS
    add_event(db, c, "Category corrected", f"{old} → {label(c.category)}; re-routed to {WORKFLOWS[c.department_id]['agency']}",
              f"{user.display_name} ({user.designation or user.role})")
    auto_assign(db, c)
    db.commit()
    db.refresh(c)
    return complaint_json(db, c, user)


@router.get("/staff")
def staff(department: str | None = None, user: User = Depends(is_supervisor), db: Session = Depends(get_db)):
    q = select(User).where(User.role.in_(("officer", "supervisor")), User.active)
    dep = department or (user.department_id if user.role == "supervisor" else None)
    if dep:
        q = q.where(User.department_id == dep)
    return [{"id": u.id, "name": u.display_name, "designation": u.designation, "level": u.level,
             "departmentId": u.department_id, "phone": u.phone,
             "open": db.query(Complaint).filter(Complaint.officer_id == u.id, Complaint.status.in_(OPEN_STATUSES)).count()}
            for u in db.scalars(q.order_by(User.department_id, User.level, User.display_name))]
