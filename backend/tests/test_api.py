"""End-to-end API tests: run with  .venv\\Scripts\\python -m pytest -q"""
import io
import os
import tempfile

os.environ["CIVICFIX_DATA"] = tempfile.mkdtemp(prefix="civicfix_test_")
os.environ["CIVICFIX_ESCALATION_INTERVAL"] = "3600"

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402
from PIL import Image  # noqa: E402

from app.db import Base, SessionLocal, engine  # noqa: E402
from app.main import app  # noqa: E402
from app.setup import DEMO_NAMES, ensure_user  # noqa: E402
from app.workflows import designation  # noqa: E402

HOUR = 3_600_000
LAT, LNG = 28.5677, 77.2433  # Lajpat Nagar


def jpeg(color=(120, 110, 100)) -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", (320, 240), color).save(buf, "JPEG")
    return buf.getvalue()


@pytest.fixture(scope="module")
def client():
    Base.metadata.create_all(engine)
    with SessionLocal() as db:
        ensure_user(db, "kkaushal", "adminpass1", "admin", designation_text="Administrator")
        for dep, names in DEMO_NAMES.items():
            for level, name in enumerate(names):
                ensure_user(db, name, "staffpass", "officer" if level == 0 else "supervisor", dep, level, designation(dep, level))
        db.commit()
    with TestClient(app) as c:
        yield c


def login(client, name, pw):
    r = client.post("/api/auth/login", json={"name": name, "password": pw})
    assert r.status_code == 200, r.text
    return {"Authorization": f"Bearer {r.json()['token']}"}


def test_full_flow(client):
    # citizen registers (phone optional) and reports a drainage problem with a photo
    r = client.post("/api/auth/register", json={"name": "Asha Citizen", "password": "secret1", "phone": "9876543210"})
    assert r.status_code == 200
    asha = {"Authorization": f"Bearer {r.json()['token']}"}
    assert client.post("/api/auth/register", json={"name": "asha citizen", "password": "secret1"}).status_code == 409
    assert client.post("/api/auth/login", json={"name": "Asha Citizen", "password": "nope"}).status_code == 401

    r = client.post("/api/complaints", headers=asha,
                    data={"lat": LAT, "lng": LNG, "category": "drainage", "severity": "medium",
                          "description": "Sewer overflowing near the market", "address": "Lajpat Nagar II"},
                    files={"photo": ("p.jpg", jpeg(), "image/jpeg")})
    assert r.status_code == 200, r.text
    c = r.json()
    cid = c["id"]
    assert c["departmentId"] == "sewerage" and c["agency"].startswith("Delhi Jal Board")
    assert c["officer"]["name"] == "Deepak Kumar" and c["supervisor"]["name"] == "Farah Ali"
    assert c["status"] == "ASSIGNED" and c["slaHours"] == 48

    # second citizen 40 m away gets a duplicate suggestion and does +1
    bob = {"Authorization": f"Bearer {client.post('/api/auth/register', json={'name': 'Bob', 'password': 'secret2'}).json()['token']}"}
    r = client.post("/api/complaints/check-duplicates", headers=bob,
                    data={"lat": LAT + 0.00036, "lng": LNG, "category": "drainage"}, files={"photo": ("q.jpg", jpeg(), "image/jpeg")})
    matches = r.json()["matches"]
    assert matches and matches[0]["id"] == cid and matches[0]["distanceM"] <= 50
    assert client.post(f"/api/complaints/{cid}/support", headers=bob).json()["supportCount"] == 1
    assert client.post("/api/complaints/check-duplicates", data={"lat": LAT + 0.01, "lng": LNG, "category": "drainage"}).json()["matches"] == []

    # officer sees it; citizen phone visible to staff only
    officer = login(client, "deepak kumar", "staffpass")
    tasks = client.get("/api/complaints?scope=assigned", headers=officer).json()
    assert [t["id"] for t in tasks] == [cid] and tasks[0]["citizenPhone"] == "9876543210"
    assert "citizenPhone" not in client.get(f"/api/complaints/{cid}", headers=bob).json()

    # time passes: deadline missed -> warning (stage 1); 2x late -> higher supervisor (stage 2); 3x -> head (stage 3)
    sup = login(client, "Farah Ali", "staffpass")
    client.post("/api/admin/time", headers=sup, json={"add_hours": 49})
    c = client.get(f"/api/complaints/{cid}", headers=asha).json()
    assert c["escalationLevel"] == 1 and c["overdue"]
    notes = client.get("/api/notifications", headers=officer).json()
    assert any(n["kind"] == "warning" and n["complaintId"] == cid for n in notes)
    assert any(n["kind"] == "warning" for n in client.get("/api/notifications", headers=sup).json())

    client.post("/api/admin/time", headers=sup, json={"add_hours": 48})
    c = client.get(f"/api/complaints/{cid}", headers=asha).json()
    assert c["escalationLevel"] == 2 and c["escalatedTo"]["name"] == "Manoj Tiwari"
    ee = login(client, "Manoj Tiwari", "staffpass")
    assert any(t["id"] == cid for t in client.get("/api/complaints?scope=assigned", headers=ee).json())

    # supervisor re-assigns with a new deadline
    staff = client.get("/api/staff", headers=sup).json()
    assert any(s["name"] == "Deepak Kumar" for s in staff)

    # completion requires photo AND video; proof is checked for distance and time
    now = client.get("/api/me", headers=officer).json()["serverTime"]
    r = client.post(f"/api/complaints/{cid}/resolve", headers=officer, data={"action_taken": "Desilted"},
                    files={"photo": ("a.jpg", jpeg((200, 200, 200)), "image/jpeg")})
    assert r.status_code == 422
    r = client.post(f"/api/complaints/{cid}/resolve", headers=officer,
                    data={"action_taken": "Sewer line desilted", "lat": LAT, "lng": LNG, "captured_at": now - 60_000},
                    files={"photo": ("a.jpg", jpeg((200, 200, 200)), "image/jpeg"),
                           "video": ("v.mp4", b"\x00\x00\x00\x18ftypmp42" + b"0" * 2000, "video/mp4")})
    assert r.status_code == 200, r.text
    c = r.json()
    assert c["status"] == "RESOLVED" and c["afterVideo"].endswith(".mp4") and "✅ taken 0 m" in c["proofCheck"]

    # citizen says NOT fixed -> reopened and escalated further
    c = client.post(f"/api/complaints/{cid}/verify", headers=asha, json={"fixed": False, "reason": "still overflowing"}).json()
    assert c["status"] == "REOPENED" and c["escalationLevel"] == 3

    # admin can see everything, edit and delete; every action is audit-logged
    admin = login(client, "kkaushal", "adminpass1")
    assert any(x["id"] == cid for x in client.get("/api/complaints?scope=all", headers=admin).json())
    assert client.get("/api/complaints?scope=all", headers=sup).status_code == 403
    c = client.patch(f"/api/admin/complaints/{cid}", headers=admin, json={"severity": "high", "status": "CLOSED"}).json()
    assert c["severity"] == "high" and c["status"] == "CLOSED"
    assert client.delete(f"/api/admin/complaints/{cid}", headers=asha).status_code == 403
    assert client.delete(f"/api/admin/complaints/{cid}", headers=admin).json() == {"deleted": cid}
    log = client.get("/api/admin/audit", headers=admin).json()
    assert [a["action"] for a in log[:2]] == ["delete", "edit"]

    # public map feed has no personal data
    assert all("citizenName" not in x for x in client.get("/api/public/complaints").json())


def test_staff_cannot_self_register_as_officer(client):
    r = client.post("/api/auth/register", json={"name": "Fake Officer", "password": "secret1", "role": "officer"})
    assert r.json()["user"]["role"] == "citizen"
