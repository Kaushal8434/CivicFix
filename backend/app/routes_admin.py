"""Admin (full access, every change audit-logged), notifications and analytics."""
import json

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from .db import get_db
from .media import delete_media
from .models import OPEN_STATUSES, STATUSES, AuditLog, Complaint, Notification, User
from .routes_auth import normalise_name, user_json
from .security import current_user, hash_password, is_admin, is_supervisor
from .services import (HOUR_MS, add_event, complaint_json, label, now_ms, run_escalation, set_time_offset,
                       time_offset_ms)
from .workflows import CATEGORIES, WORKFLOWS, department_for, designation

router = APIRouter(prefix="/api")


def audit(db: Session, admin: User, action: str, complaint_id: str | None, details: dict):
    db.add(AuditLog(time=now_ms(db), user_id=admin.id, username=admin.username, action=action,
                    complaint_id=complaint_id, details=json.dumps(details, ensure_ascii=False)))


# ------------------------------------------------------------------ admin: complaints
class ComplaintPatch(BaseModel):
    status: str | None = None
    category: str | None = None
    severity: str | None = None
    description: str | None = None
    address: str | None = None
    officer_id: int | None = None
    supervisor_id: int | None = None
    escalated_to_id: int | None = None
    escalation_level: int | None = Field(None, ge=0, le=3)
    due_at: int | None = None


@router.patch("/admin/complaints/{cid}")
def admin_edit(cid: str, body: ComplaintPatch, admin: User = Depends(is_admin), db: Session = Depends(get_db)):
    c = db.get(Complaint, cid)
    if c is None:
        raise HTTPException(404, "Complaint not found")
    changes = {}
    data = body.model_dump(exclude_unset=True)
    if "status" in data and data["status"] not in STATUSES:
        raise HTTPException(400, "Unknown status")
    if "category" in data:
        if data["category"] not in CATEGORIES:
            raise HTTPException(400, "Unknown category")
        c.department_id = department_for(data["category"])
    if "severity" in data and data["severity"] not in ("low", "medium", "high"):
        raise HTTPException(400, "Severity must be low, medium or high")
    for uid_field in ("officer_id", "supervisor_id", "escalated_to_id"):
        if data.get(uid_field) is not None and db.get(User, data[uid_field]) is None:
            raise HTTPException(400, f"{uid_field}: user not found")
    mapping = {"escalation_level": "escalation_stage"}
    for k, v in data.items():
        attr = mapping.get(k, k)
        old = getattr(c, attr)
        if old != v:
            changes[k] = {"from": old, "to": v}
            setattr(c, attr, v)
    if c.status == "CLOSED" and c.closed_at is None:
        c.closed_at = now_ms(db)
    if changes:
        add_event(db, c, "Edited by administrator", ", ".join(changes), f"{admin.display_name} (Admin)")
        audit(db, admin, "edit", c.id, changes)
        db.commit()
    db.refresh(c)
    return complaint_json(db, c, admin)


@router.delete("/admin/complaints/{cid}")
def admin_delete(cid: str, admin: User = Depends(is_admin), db: Session = Depends(get_db)):
    c = db.get(Complaint, cid)
    if c is None:
        raise HTTPException(404, "Complaint not found")
    audit(db, admin, "delete", c.id, {"category": c.category, "citizen": c.citizen_name, "status": c.status,
                                      "address": c.address or c.location_label})
    db.delete(c)
    db.query(Notification).filter(Notification.complaint_id == cid).delete()
    db.commit()
    delete_media(cid)
    return {"deleted": cid}


@router.get("/admin/audit")
def audit_log(admin: User = Depends(is_admin), db: Session = Depends(get_db)):
    rows = db.scalars(select(AuditLog).order_by(AuditLog.time.desc()).limit(300))
    return [{"time": a.time, "user": a.username, "action": a.action, "complaintId": a.complaint_id,
             "details": json.loads(a.details or "{}")} for a in rows]


# ------------------------------------------------------------------ admin: users
class StaffIn(BaseModel):
    name: str = Field(min_length=3, max_length=60)
    password: str = Field(min_length=6, max_length=128)
    role: str
    department_id: str | None = None
    level: int = Field(0, ge=0, le=3)
    phone: str | None = None


class UserPatch(BaseModel):
    active: bool | None = None
    password: str | None = Field(None, min_length=6, max_length=128)
    level: int | None = Field(None, ge=0, le=3)
    department_id: str | None = None
    phone: str | None = None


@router.get("/admin/users")
def users(admin: User = Depends(is_admin), db: Session = Depends(get_db)):
    return [{**user_json(u), "active": u.active, "createdAt": u.created_at}
            for u in db.scalars(select(User).order_by(User.role, User.department_id, User.level, User.username))]


@router.post("/admin/users")
def create_user(body: StaffIn, admin: User = Depends(is_admin), db: Session = Depends(get_db)):
    if body.role not in ("officer", "supervisor", "admin", "citizen"):
        raise HTTPException(400, "Unknown role")
    if body.role in ("officer", "supervisor") and body.department_id not in WORKFLOWS:
        raise HTTPException(400, "Choose a department")
    name = normalise_name(body.name)
    if db.scalar(select(User).where(User.username == name.lower())):
        raise HTTPException(409, "Name already taken")
    level = 0 if body.role == "officer" else (max(1, body.level) if body.role == "supervisor" else 0)
    u = User(username=name.lower(), display_name=name, phone=body.phone, role=body.role,
             department_id=body.department_id if body.role in ("officer", "supervisor") else None, level=level,
             designation=designation(body.department_id, level) if body.department_id else ("Administrator" if body.role == "admin" else None),
             password_hash=hash_password(body.password), created_at=now_ms(db))
    db.add(u)
    audit(db, admin, "create_user", None, {"user": name, "role": body.role, "department": body.department_id, "level": level})
    db.commit()
    return user_json(u)


@router.patch("/admin/users/{uid}")
def patch_user(uid: int, body: UserPatch, admin: User = Depends(is_admin), db: Session = Depends(get_db)):
    u = db.get(User, uid)
    if u is None:
        raise HTTPException(404, "User not found")
    data = body.model_dump(exclude_unset=True)
    if "password" in data:
        u.password_hash = hash_password(data.pop("password"))
        data["password"] = "(changed)"
    for k in ("active", "level", "department_id", "phone"):
        if k in body.model_fields_set and k != "password":
            setattr(u, k, getattr(body, k))
    if u.department_id and u.role in ("officer", "supervisor"):
        u.designation = designation(u.department_id, u.level)
    audit(db, admin, "edit_user", None, {"user": u.username, **data})
    db.commit()
    return user_json(u)


# ------------------------------------------------------------------ demo clock / escalation
class TimeIn(BaseModel):
    add_hours: int = 0
    reset: bool = False


@router.post("/admin/time")
def demo_time(body: TimeIn, user: User = Depends(is_supervisor), db: Session = Depends(get_db)):
    """Move the server clock forward (demo of deadline warnings and escalation)."""
    offset = 0 if body.reset else time_offset_ms(db) + body.add_hours * HOUR_MS
    set_time_offset(db, offset)
    db.commit()
    changed = run_escalation(db)
    return {"offsetHours": offset // HOUR_MS, "now": now_ms(db), "escalated": changed}


# ------------------------------------------------------------------ notifications
@router.get("/notifications")
def notifications(user: User = Depends(current_user), db: Session = Depends(get_db)):
    rows = db.scalars(select(Notification).where(Notification.user_id == user.id)
                      .order_by(Notification.read, Notification.created_at.desc()).limit(60))
    return [{"id": n.id, "complaintId": n.complaint_id, "kind": n.kind, "title": n.title, "body": n.body,
             "createdAt": n.created_at, "read": n.read} for n in rows]


@router.post("/notifications/read")
def mark_read(ids: list[int] | None = None, user: User = Depends(current_user), db: Session = Depends(get_db)):
    q = db.query(Notification).filter(Notification.user_id == user.id)
    if ids:
        q = q.filter(Notification.id.in_(ids))
    q.update({Notification.read: True}, synchronize_session=False)
    db.commit()
    return {"ok": True}


# ------------------------------------------------------------------ analytics
@router.get("/stats")
def stats(user: User = Depends(current_user), db: Session = Depends(get_db)):
    now = now_ms(db)
    all_c = list(db.scalars(select(Complaint)))
    open_c = [c for c in all_c if c.status in OPEN_STATUSES]
    resolved = [c for c in all_c if c.resolved_at]
    by_dept = {}
    for d, wf in WORKFLOWS.items():
        cs = [c for c in all_c if c.department_id == d]
        res = [c for c in cs if c.resolved_at]
        by_dept[d] = {"agency": wf["agency"], "total": len(cs), "open": sum(c.status in OPEN_STATUSES for c in cs),
                      "overdue": sum(c.status in OPEN_STATUSES and c.due_at < now for c in cs),
                      "escalated": sum(c.escalation_stage >= 2 and c.status in OPEN_STATUSES for c in cs),
                      "avgResolutionHours": round(sum((c.resolved_at - c.created_at) for c in res) / len(res) / HOUR_MS, 1) if res else None,
                      "onTimePct": round(100 * sum(c.resolved_at <= c.due_at for c in res) / len(res)) if res else None}
    hot = {}
    for c in all_c:
        hot.setdefault(c.location_label.split(",")[0], 0)
        hot[c.location_label.split(",")[0]] += 1 + len(c.supports)
    return {
        "total": len(all_c), "open": len(open_c), "overdue": sum(c.due_at < now for c in open_c),
        "escalated": sum(c.escalation_stage >= 2 for c in open_c), "resolved": len(resolved),
        "closed": sum(c.status == "CLOSED" for c in all_c), "reopened": sum(c.reopen_count > 0 for c in all_c),
        "onTimePct": round(100 * sum(c.resolved_at <= c.due_at for c in resolved) / len(resolved)) if resolved else None,
        "byCategory": {k: {"label": label(k), "count": sum(c.category == k for c in all_c)} for k in CATEGORIES},
        "byDepartment": by_dept,
        "hotspots": sorted(hot.items(), key=lambda kv: -kv[1])[:8],
        "timeOffsetHours": time_offset_ms(db) // HOUR_MS,
    }
