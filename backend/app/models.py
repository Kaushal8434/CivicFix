"""Database tables. Times are stored as epoch milliseconds (same as the Android app)."""
from sqlalchemy import Boolean, Float, ForeignKey, Integer, LargeBinary, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship

from .db import Base

ROLES = ("citizen", "officer", "supervisor", "admin")
STATUSES = ("NEW", "ASSIGNED", "IN_PROGRESS", "RESOLVED", "CLOSED", "REOPENED")
OPEN_STATUSES = ("NEW", "ASSIGNED", "IN_PROGRESS", "REOPENED")


class User(Base):
    __tablename__ = "users"
    id: Mapped[int] = mapped_column(primary_key=True)
    username: Mapped[str] = mapped_column(String(60), unique=True, index=True)   # lower-case login name
    display_name: Mapped[str] = mapped_column(String(80))
    phone: Mapped[str | None] = mapped_column(String(20))
    role: Mapped[str] = mapped_column(String(20), default="citizen")
    department_id: Mapped[str | None] = mapped_column(String(40))
    # Staff hierarchy inside a department: 0 = field officer (JE / Sanitary Inspector),
    # 1 = supervisor (AE / Sanitary Superintendent), 2 = higher supervisor (EE / Deputy Commissioner),
    # 3 = head (SE / Director). See workflows.py.
    level: Mapped[int] = mapped_column(Integer, default=0)
    designation: Mapped[str | None] = mapped_column(String(120))
    password_hash: Mapped[str] = mapped_column(String(200))
    active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[int] = mapped_column(Integer)


class Complaint(Base):
    __tablename__ = "complaints"
    id: Mapped[str] = mapped_column(String(24), primary_key=True)
    citizen_id: Mapped[int | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"))
    citizen_name: Mapped[str] = mapped_column(String(80))
    citizen_phone: Mapped[str | None] = mapped_column(String(20))

    category: Mapped[str] = mapped_column(String(40), index=True)
    severity: Mapped[str] = mapped_column(String(10), default="medium")
    description: Mapped[str] = mapped_column(Text, default="")
    address: Mapped[str] = mapped_column(Text, default="")
    landmark: Mapped[str] = mapped_column(String(200), default="")
    lat: Mapped[float | None] = mapped_column(Float)
    lng: Mapped[float | None] = mapped_column(Float)
    city_id: Mapped[str] = mapped_column(String(40), default="")
    zone_id: Mapped[str] = mapped_column(String(40), default="")
    ward_id: Mapped[str] = mapped_column(String(40), default="")
    locality_id: Mapped[str] = mapped_column(String(40), default="")
    location_label: Mapped[str] = mapped_column(String(300), default="")

    department_id: Mapped[str] = mapped_column(String(40), index=True)
    officer_id: Mapped[int | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"))
    supervisor_id: Mapped[int | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"))
    escalated_to_id: Mapped[int | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"))
    team: Mapped[str | None] = mapped_column(String(80))
    # 0 = on time, 1 = deadline missed (officer warned, supervisor told),
    # 2 = missed by 2x the allowed time -> higher supervisor owns it, 3 = head of department
    escalation_stage: Mapped[int] = mapped_column(Integer, default=0)

    status: Mapped[str] = mapped_column(String(16), default="ASSIGNED", index=True)
    created_at: Mapped[int] = mapped_column(Integer)
    sla_hours: Mapped[int] = mapped_column(Integer)
    due_at: Mapped[int] = mapped_column(Integer)
    resolved_at: Mapped[int | None] = mapped_column(Integer)
    closed_at: Mapped[int | None] = mapped_column(Integer)
    reopen_count: Mapped[int] = mapped_column(Integer, default=0)

    ai_category: Mapped[str | None] = mapped_column(String(40))
    ai_confidence: Mapped[float | None] = mapped_column(Float)
    ai_summary: Mapped[str | None] = mapped_column(Text)

    before_photo: Mapped[str | None] = mapped_column(String(200))
    embedding: Mapped[bytes | None] = mapped_column(LargeBinary)
    after_photo: Mapped[str | None] = mapped_column(String(200))
    after_video: Mapped[str | None] = mapped_column(String(200))
    action_taken: Mapped[str | None] = mapped_column(Text)
    proof_check: Mapped[str | None] = mapped_column(Text)
    after_photo_ai_check: Mapped[str | None] = mapped_column(Text)

    events: Mapped[list["Event"]] = relationship(back_populates="complaint", cascade="all, delete-orphan", order_by="Event.time")
    supports: Mapped[list["Support"]] = relationship(back_populates="complaint", cascade="all, delete-orphan")


class Event(Base):
    """Timeline entry shown to everyone (submitted, assigned, warned, escalated, resolved, ...)."""
    __tablename__ = "events"
    id: Mapped[int] = mapped_column(primary_key=True)
    complaint_id: Mapped[str] = mapped_column(ForeignKey("complaints.id", ondelete="CASCADE"), index=True)
    time: Mapped[int] = mapped_column(Integer)
    title: Mapped[str] = mapped_column(String(120))
    note: Mapped[str] = mapped_column(Text, default="")
    actor: Mapped[str] = mapped_column(String(120), default="System")
    complaint: Mapped[Complaint] = relationship(back_populates="events")


class Support(Base):
    """A '+1' from another citizen facing the same problem."""
    __tablename__ = "supports"
    __table_args__ = (UniqueConstraint("complaint_id", "user_id"),)
    id: Mapped[int] = mapped_column(primary_key=True)
    complaint_id: Mapped[str] = mapped_column(ForeignKey("complaints.id", ondelete="CASCADE"), index=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"))
    user_name: Mapped[str] = mapped_column(String(80))
    time: Mapped[int] = mapped_column(Integer)
    complaint: Mapped[Complaint] = relationship(back_populates="supports")


class Notification(Base):
    __tablename__ = "notifications"
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    complaint_id: Mapped[str | None] = mapped_column(String(24))
    kind: Mapped[str] = mapped_column(String(20))          # info | warning | escalation | resolved
    title: Mapped[str] = mapped_column(String(160))
    body: Mapped[str] = mapped_column(Text)
    created_at: Mapped[int] = mapped_column(Integer)
    read: Mapped[bool] = mapped_column(Boolean, default=False)


class AuditLog(Base):
    """Every admin edit / delete, for accountability."""
    __tablename__ = "audit_log"
    id: Mapped[int] = mapped_column(primary_key=True)
    time: Mapped[int] = mapped_column(Integer)
    user_id: Mapped[int | None] = mapped_column(Integer)
    username: Mapped[str] = mapped_column(String(60))
    action: Mapped[str] = mapped_column(String(40))
    complaint_id: Mapped[str | None] = mapped_column(String(24))
    details: Mapped[str] = mapped_column(Text, default="")


class Setting(Base):
    __tablename__ = "settings"
    key: Mapped[str] = mapped_column(String(40), primary_key=True)
    value: Mapped[str] = mapped_column(Text)
