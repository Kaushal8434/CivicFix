@echo off
rem Starts the CivicFix server for the app and the website (http://localhost:8000/).
cd /d "%~dp0"
if not exist .venv (
  python -m venv .venv
  .venv\Scripts\pip install -r requirements.txt
)
.venv\Scripts\python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
