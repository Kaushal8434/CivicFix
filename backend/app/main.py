"""
CivicFix backend – one server for the Android app and the website.

Run:   cd backend && .venv\\Scripts\\python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
Docs:  http://localhost:8000/docs      Website: http://localhost:8000/
"""
import asyncio
import contextlib
import logging

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from .config import ESCALATION_INTERVAL_S, MEDIA_DIR, WEB_DIR
from .db import Base, SessionLocal, engine
from .routes_admin import router as admin_router
from .routes_auth import router as auth_router
from .routes_complaints import router as complaints_router
from .services import run_escalation

log = logging.getLogger("civicfix")


async def escalation_loop():
    """Checks every open complaint's deadline once a minute (warnings + escalation)."""
    while True:
        try:
            with SessionLocal() as db:
                changed = await asyncio.to_thread(run_escalation, db)
                if changed:
                    log.info("escalation: %s", ", ".join(changed))
        except Exception:  # never let the loop die
            log.exception("escalation check failed")
        await asyncio.sleep(ESCALATION_INTERVAL_S)


@contextlib.asynccontextmanager
async def lifespan(_app: FastAPI):
    Base.metadata.create_all(engine)
    task = asyncio.create_task(escalation_loop())
    yield
    task.cancel()


app = FastAPI(title="CivicFix API", version="2.0", lifespan=lifespan)
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])
app.include_router(auth_router)
app.include_router(complaints_router)
app.include_router(admin_router)
app.mount("/media", StaticFiles(directory=MEDIA_DIR), name="media")
app.mount("/static", StaticFiles(directory=WEB_DIR), name="static")


@app.get("/", include_in_schema=False)
def website():
    return FileResponse(WEB_DIR / "index.html")


@app.get("/api/health")
def health():
    return {"ok": True}
