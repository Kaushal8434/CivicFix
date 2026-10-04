"""Business rules shared by the API routes and the escalation engine."""
import math
import random
import time
from datetime import datetime

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from . import ai
from .config import DUPLICATE_RADIUS_M
from .geo import distance_m
from .models import OPEN_STATUSES, Complaint, Event, Notification, Setting, Support, User
from .workflows import CATEGORIES, WORKFLOWS, designation

HOUR_MS = 3_600_000


# ---------------------------------------------------------------- clock
def time_offset_ms(db: Session) -> int:
    s = db.get(Setting, "time_offset_ms")
    return int(s.value) if s else 0


def now_ms(db: Session) -> int:
    """Current time, plus the demo offset an admin can add to show escalation live."""
    return int(time.time() * 1000) + time_offset_ms(db)


def set_time_offset(db: Session, offset_ms: int):
    s = db.get(Setting, "time_offset_ms") or Setting(key="time_offset_ms", value="0")
    s.value = str(offset_ms)
    db.merge(s)


def new_complaint_id(db: Session, now: int) -> str:
    day = datetime.fromtimestamp(now / 1000).strftime("%Y%m%d")
    while True:
        cid = f"CF-{day}-{random.randint(1000, 9999)}"
        if db.get(Complaint, cid) is None:
            return cid


# ---------------------------------------------------------------- staff
def staff_at(db: Session, department_id: str, level: int) -> list[User]:
    return list(db.scalars(select(User).where(User.department_id == department_id, User.level == level,
                                              User.role.in_(("officer", "supervisor")), User.active)))


def least_loaded(db: Session, users: list[User], column) -> User | None:
    """The staff member with the fewest open complaints (simple load balancing)."""
    if not users:
        return None
    load = dict(db.execute(select(column, func.count()).where(Complaint.status.in_(OPEN_STATUSES), column.in_([u.id for u in users]))
                           .group_by(column)).all())
    return min(users, key=lambda u: (load.get(u.id, 0), u.id))


def owner_for_stage(db: Session, c: Complaint) -> User | None:
    """Who is answerable right now: officer (stage 0-1), higher supervisor (2), head (3)."""
    if c.escalation_stage >= 2 and c.escalated_to_id:
        return db.get(User, c.escalated_to_id)
    return db.get(User, c.officer_id) if c.officer_id else None


# ---------------------------------------------------------------- events / notifications
def add_event(db: Session, c: Complaint, title: str, note: str = "", actor: str = "System", at: int | None = None):
    db.add(Event(complaint_id=c.id, time=at or now_ms(db), title=title, note=note, actor=actor))


def notify(db: Session, user_id: int | None, c: Complaint | None, kind: str, title: str, body: str):
    if user_id:
        db.add(Notification(user_id=user_id, complaint_id=c.id if c else None, kind=kind, title=title, body=body,
                            created_at=now_ms(db)))


def label(category: str) -> str:
    return CATEGORIES.get(category, CATEGORIES["other"])["label"]


# ---------------------------------------------------------------- assignment
def auto_assign(db: Session, c: Complaint):
    """Field officer (chain level 0) and supervisor (level 1) of the responsible department."""
    officer = least_loaded(db, staff_at(db, c.department_id, 0), Complaint.officer_id)
    supervisor = least_loaded(db, staff_at(db, c.department_id, 1), Complaint.supervisor_id)
    c.officer_id = officer.id if officer else None
    c.supervisor_id = supervisor.id if supervisor else None
    c.status = "ASSIGNED" if officer else "NEW"
    wf = WORKFLOWS[c.department_id]
    who = f"{officer.display_name} ({officer.designation})" if officer else f"{wf['chain'][0]} – not yet staffed"
    add_event(db, c, "Assigned automatically", f"{wf['agency']}: {who}. Deadline {fmt(c.due_at)}")
    notify(db, c.officer_id, c, "info", f"New task {c.id}", f"{label(c.category)} at {c.address or c.location_label}. Deadline {fmt(c.due_at)}.")
    notify(db, c.supervisor_id, c, "info", f"New complaint in your team: {c.id}",
           f"{label(c.category)} assigned to {officer.display_name if officer else 'nobody'}. Deadline {fmt(c.due_at)}.")


def fmt(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000).strftime("%d %b %Y, %H:%M")


# ---------------------------------------------------------------- escalation engine
def run_escalation(db: Session) -> list[str]:
    """
    Applies the escalation rule to every open complaint (called every minute):
      stage 1  deadline missed               -> warn officer, message supervisor
      stage 2  late by 2x the allowed time   -> hand over to higher supervisor (chain level 2)
      stage 3  late by 3x the allowed time   -> head of department (chain level 3)
    Returns the ids of complaints that changed (for logging/tests).
    """
    now = now_ms(db)
    changed = []
    for c in db.scalars(select(Complaint).where(Complaint.status.in_(OPEN_STATUSES), Complaint.due_at < now)):
        late = now - c.due_at
        allowed = c.sla_hours * HOUR_MS
        target = 1 if late < allowed else (2 if late < 2 * allowed else 3)
        while c.escalation_stage < target:
            c.escalation_stage += 1
            _apply_stage(db, c, now)
            changed.append(c.id)
    db.commit()
    return changed


def _apply_stage(db: Session, c: Complaint, now: int):
    wf = WORKFLOWS[c.department_id]
    officer = db.get(User, c.officer_id) if c.officer_id else None
    supervisor = db.get(User, c.supervisor_id) if c.supervisor_id else None
    what = f"{label(c.category)} at {c.address or c.location_label}"
    if c.escalation_stage == 1:
        add_event(db, c, "Deadline missed – warning issued",
                  f"Warning to {officer.display_name if officer else 'assigned officer'}; supervisor {supervisor.display_name if supervisor else ''} informed.", at=now)
        notify(db, c.officer_id, c, "warning", f"⚠️ Deadline missed: {c.id}",
               f"{what} was due {fmt(c.due_at)}. Complete it now – if it is delayed by twice the allowed time it goes to the higher supervisor.")
        notify(db, c.supervisor_id, c, "warning", f"Overdue in your team: {c.id}",
               f"{what} assigned to {officer.display_name if officer else 'nobody'} missed its deadline ({fmt(c.due_at)}).")
    else:
        level = c.escalation_stage            # 2 -> chain level 2, 3 -> chain level 3
        boss = least_loaded(db, staff_at(db, c.department_id, level), Complaint.escalated_to_id)
        c.escalated_to_id = boss.id if boss else c.escalated_to_id
        title = designation(c.department_id, level)
        add_event(db, c, f"Escalated to {title}",
                  f"Late by {'2' if level == 2 else '3'}x the allowed time. Now handled by {boss.display_name if boss else title}.", at=now)
        notify(db, c.escalated_to_id, c, "escalation", f"🔺 Escalated to you: {c.id}",
               f"{what} is late by {(now - c.due_at) // HOUR_MS} h. Officer: {officer.display_name if officer else '-'}, supervisor: {supervisor.display_name if supervisor else '-'}.")
        notify(db, c.supervisor_id, c, "escalation", f"Escalated: {c.id}", f"{what} has been escalated to the {title}.")
        notify(db, c.officer_id, c, "escalation", f"Escalated: {c.id}", f"{what} has been escalated to the {title} because of the delay.")
    notify(db, c.citizen_id, c, "info", f"Your complaint {c.id} has been escalated",
           f"The department missed the deadline, so it has been raised to a senior officer ({wf['agency']}).")


# ---------------------------------------------------------------- duplicates
def find_duplicates(db: Session, lat: float, lng: float, category: str | None, embedding: bytes | None,
                    radius_m: float = DUPLICATE_RADIUS_M, exclude: str | None = None) -> list[dict]:
    """Open complaints within the radius that probably show the same problem."""
    d_lat = radius_m / 111_000
    d_lng = radius_m / (111_000 * max(0.2, abs(math.cos(math.radians(lat)))))
    rows = db.scalars(select(Complaint).where(Complaint.status.in_(OPEN_STATUSES), Complaint.lat.between(lat - d_lat, lat + d_lat),
                                              Complaint.lng.between(lng - d_lng, lng + d_lng)))
    out = []
    for c in rows:
        if c.id == exclude or c.lat is None:
            continue
        dist = distance_m(lat, lng, c.lat, c.lng)
        if dist > radius_m:
            continue
        sim = ai.similarity(embedding, c.embedding)
        same_cat = category is not None and c.category == category
        if sim is not None:
            score = sim + (0.1 if same_cat else 0.0)
            verdict = "same" if score >= 0.80 else ("possible" if score >= 0.62 or same_cat else None)
        else:
            verdict = "possible" if same_cat else None
        if verdict:
            out.append({"complaint": c, "distance_m": round(dist), "similarity": None if sim is None else round(sim, 3),
                        "same_category": same_cat, "verdict": verdict})
    out.sort(key=lambda d: (d["verdict"] != "same", -(d["similarity"] or 0), d["distance_m"]))
    return out[:5]


# ---------------------------------------------------------------- serialisation
def media_url(path: str | None) -> str | None:
    return f"/media/{path}" if path else None


def user_brief(db: Session, uid: int | None) -> dict | None:
    u = db.get(User, uid) if uid else None
    return None if u is None else {"id": u.id, "name": u.display_name, "designation": u.designation, "phone": u.phone}


def complaint_json(db: Session, c: Complaint, viewer: User | None = None, full: bool = True) -> dict:
    now = now_ms(db)
    staff = viewer is not None and viewer.role in ("officer", "supervisor", "admin")
    wf = WORKFLOWS[c.department_id]
    d = {
        "id": c.id, "citizenName": c.citizen_name, "category": c.category, "categoryLabel": label(c.category),
        "severity": c.severity, "description": c.description, "address": c.address, "landmark": c.landmark,
        "lat": c.lat, "lng": c.lng, "cityId": c.city_id, "zoneId": c.zone_id, "wardId": c.ward_id, "localityId": c.locality_id,
        "locationLabel": c.location_label, "departmentId": c.department_id, "agency": wf["agency"], "departmentName": wf["name"],
        "status": c.status, "createdAt": c.created_at, "dueAt": c.due_at, "slaHours": c.sla_hours,
        "resolvedAt": c.resolved_at, "closedAt": c.closed_at, "reopenCount": c.reopen_count,
        "escalationLevel": c.escalation_stage, "overdue": c.status in OPEN_STATUSES and now > c.due_at,
        "supportCount": len(c.supports), "supporters": [s.user_name for s in c.supports],
        "officer": user_brief(db, c.officer_id), "supervisor": user_brief(db, c.supervisor_id),
        "escalatedTo": user_brief(db, c.escalated_to_id), "assignedTeam": c.team,
        "beforePhoto": media_url(c.before_photo), "afterPhoto": media_url(c.after_photo), "afterVideo": media_url(c.after_video),
        "actionTaken": c.action_taken, "proofCheck": c.proof_check, "afterPhotoAiCheck": c.after_photo_ai_check,
        "aiSummary": c.ai_summary, "isMine": viewer is not None and viewer.id == c.citizen_id,
        "hasSupported": viewer is not None and any(s.user_id == viewer.id for s in c.supports),
    }
    if staff:
        d["citizenPhone"] = c.citizen_phone
    if full:
        d["timeline"] = [{"time": e.time, "title": e.title, "note": e.note, "actor": e.actor} for e in c.events]
    return d


def add_support(db: Session, c: Complaint, user: User) -> bool:
    if user.id == c.citizen_id or any(s.user_id == user.id for s in c.supports):
        return False
    db.add(Support(complaint_id=c.id, user_id=user.id, user_name=user.display_name, time=now_ms(db)))
    add_event(db, c, "Another citizen reported this (+1)", f"{user.display_name} is facing the same problem", "Citizen")
    return True
