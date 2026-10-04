"""
One-time setup on your own PC:

  python -m app.setup --admin kkaushal            # asks for the admin password (typed, never stored as text)
  python -m app.setup --demo-staff                # adds one officer + supervisors per department (demo)
  python -m app.setup --demo-staff --staff-password demo1234

The admin password is only ever stored as a PBKDF2 hash in the database (data/civicfix.db),
which is not part of the git repository.
"""
import argparse
import getpass
import sys

from sqlalchemy import select

from .db import Base, SessionLocal, engine
from .models import User
from .security import hash_password
from .services import now_ms
from .workflows import WORKFLOWS, designation

# Demo staff: one field officer (level 0), one supervisor (1), a higher supervisor (2) and a head (3) per department.
DEMO_NAMES = {
    "roads": ["Ravi Sharma", "Anil Verma", "Sunita Rao", "Vikram Malhotra"],
    "electrical": ["Imran Khan", "Pooja Mehta", "Rajesh Gupta", "Neha Kapoor"],
    "water": ["Suresh Yadav", "Meena Joshi", "Arvind Singh", "Kavita Nair"],
    "sewerage": ["Deepak Kumar", "Farah Ali", "Manoj Tiwari", "Rekha Bansal"],
    "sanitation": ["Ramesh Pal", "Geeta Devi", "Sanjay Chauhan", "Alok Mishra"],
    "enforcement": ["Harish Rawat", "Priya Saxena", "Naveen Jain", "Shalini Arora"],
    "civil": ["Mohit Bhatia", "Asha Kulkarni", "Prakash Rana", "Usha Menon"],
    "general": ["Tarun Sethi", "Nidhi Agarwal", "Gaurav Dutta", "Seema Chopra"],
}


def ensure_user(db, name, password, role, department_id=None, level=0, designation_text=None):
    u = db.scalar(select(User).where(User.username == name.lower()))
    if u is None:
        u = User(username=name.lower(), display_name=name, role=role, department_id=department_id, level=level,
                 designation=designation_text, password_hash=hash_password(password), created_at=now_ms(db))
        db.add(u)
        return u, True
    return u, False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--admin", help="create (or reset the password of) this admin account")
    ap.add_argument("--demo-staff", action="store_true")
    ap.add_argument("--staff-password", help="password for all demo staff accounts (asked if omitted)")
    ap.add_argument("--password-stdin", action="store_true", help="read the admin password from standard input (scripts)")
    ap.add_argument("--demo-complaints", action="store_true", help="add sample complaints across Delhi")
    args = ap.parse_args()
    Base.metadata.create_all(engine)

    with SessionLocal() as db:
        if args.admin:
            if args.password_stdin:
                # Windows PowerShell may prepend a byte-order mark when piping text.
                pw = sys.stdin.readline().rstrip("\r\n").lstrip("﻿").replace("ï»¿", "")
            else:
                pw = getpass.getpass(f"Password for admin '{args.admin}': ")
                if pw != getpass.getpass("Repeat password: "):
                    sys.exit("Passwords do not match")
            if len(pw) < 6:
                sys.exit("Password must have at least 6 characters")
            u = db.scalar(select(User).where(User.username == args.admin.lower()))
            if u is None:
                ensure_user(db, args.admin, pw, "admin", designation_text="Administrator")
                print(f"Admin '{args.admin}' created.")
            else:
                u.role, u.password_hash, u.active, u.designation = "admin", hash_password(pw), True, "Administrator"
                print(f"Admin '{args.admin}' updated.")
        if args.demo_staff:
            pw = args.staff_password or getpass.getpass("Password for all demo staff accounts: ")
            created = 0
            for dep, names in DEMO_NAMES.items():
                for level, name in enumerate(names):
                    role = "officer" if level == 0 else "supervisor"
                    _, new = ensure_user(db, name, pw, role, dep, level, designation(dep, level))
                    created += new
            print(f"{created} demo staff accounts created ({len(WORKFLOWS)} departments x 4 levels).")
        db.commit()
        if args.demo_complaints:
            seed_complaints(db)


# (locality id, category, severity, description, age in hours)
DEMO_COMPLAINTS = [
    ("del-connaught", "pothole_road_damage", "high", "Deep pothole near Connaught Place inner circle, bikes skidding.", 30),
    ("del-karolbagh", "garbage", "medium", "Garbage not lifted for 4 days near Karol Bagh market.", 10),
    ("del-lajpat", "drainage", "high", "Sewer manhole overflowing near Lajpat Nagar metro gate.", 20),
    ("del-lajpat", "streetlight", "medium", "Three street lights not working in the lane behind the market.", 80),
    ("har-cybercity", "water_leakage", "medium", "Pipeline leaking on the main road, water wasted all day.", 6),
    ("del-connaught", "road_blockage", "medium", "Construction material dumped on the road, traffic jam every evening.", 2),
    ("del-karolbagh", "damaged_infrastructure", "low", "Footpath tiles broken and railing bent near the bus stop.", 100),
]


def seed_complaints(db):
    from .geo import locality
    from .models import Complaint
    from .services import HOUR_MS, add_event, auto_assign, new_complaint_id
    from .workflows import department_for, sla_hours

    citizen, _ = ensure_user(db, "Demo Citizen", "democitizen", "citizen")
    db.flush()
    now = now_ms(db)
    for loc_id, cat, sev, desc, age_h in DEMO_COMPLAINTS:
        loc = locality(loc_id)
        if loc is None:
            continue
        created = now - age_h * HOUR_MS
        sla = sla_hours(cat, sev)
        c = Complaint(id=new_complaint_id(db, created), citizen_id=citizen.id, citizen_name=citizen.display_name, category=cat,
                      severity=sev, description=desc, address=f'{loc["label"].split(",")[0]}, {loc["label"].split(",")[-1].strip()}',
                      lat=loc["lat"], lng=loc["lng"],
                      city_id=loc["city_id"], zone_id=loc["zone_id"], ward_id=loc["ward_id"], locality_id=loc["locality_id"],
                      location_label=loc["label"], department_id=department_for(cat), created_at=created, sla_hours=sla,
                      due_at=created + sla * HOUR_MS)
        db.add(c)
        db.flush()
        add_event(db, c, "Complaint submitted", f"Complaint ID {c.id}", citizen.display_name, at=created)
        auto_assign(db, c)
    db.commit()
    from .services import run_escalation
    print(f"{len(DEMO_COMPLAINTS)} demo complaints added; escalation applied to: {', '.join(run_escalation(db)) or 'none'}")


if __name__ == "__main__":
    main()
