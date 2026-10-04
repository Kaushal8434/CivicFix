"""
How each kind of civic problem is handled in Delhi – the routing, officer chain and
deadlines used by CivicFix.

Based on publicly documented practice (see PROJECT_DOCUMENTATION.md, "Delhi workflow research"):
  * Roads: MCD maintains colony roads (< 60 ft wide), PWD maintains roads >= 60 ft.
    Engineering ladder JE -> AE -> EE -> SE -> Chief Engineer. MCD's stated target is to
    fill potholes within 24 h of a complaint.
  * Streetlights: split between MCD (≈3 lakh), PWD (≈2 lakh), NDMC/DSIIDC/DDA; MCD
    Electrical wing handles colony lights (JE (E) -> AE (E) -> EE (E) -> SE (E)).
  * Water supply & sewerage: Delhi Jal Board, 25 zonal "water emergency" units with
    JE, Zonal Engineer and EE, grouped into 11 circles under Superintending Engineers.
  * Garbage: MCD Department of Environment Management Services (DEMS):
    Sanitary Inspector -> Sanitary Superintendent -> Deputy Commissioner (zone) -> Director DEMS.
  * Encroachment / blockage: MCD Enforcement (with Delhi Traffic Police for traffic obstruction).
  * Anything else: Public Grievance Monitoring System – 30-day limit, 2-3 days for emergencies.

The deadlines below are this prototype's targets (hours), not official notified timelines.

Escalation rule (same for every department):
  stage 1 – deadline missed:             warning to the assigned officer, message to the supervisor
  stage 2 – missed by 2x the allowed time: complaint moves to the higher supervisor (chain level 2)
  stage 3 – still open after that:        head of department (chain level 3)
  A citizen answering "not fixed" also moves the complaint one stage up.
"""

WORKFLOWS = {
    "roads": {
        "agency": "MCD – Engineering (Maintenance)",
        "name": "Roads & Potholes",
        "alt_agency": "PWD Delhi for roads 60 ft and wider",
        "chain": ["Junior Engineer (Maintenance)", "Assistant Engineer (Maintenance)",
                  "Executive Engineer (Maintenance)", "Superintending Engineer"],
        "sla_hours": {"high": 24, "medium": 72, "low": 168},
        "helpline": "MCD 311 app / 155305",
    },
    "electrical": {
        "agency": "MCD – Electrical (Street Lighting)",
        "name": "Street Lighting",
        "alt_agency": "PWD (arterial roads), NDMC (New Delhi area), BSES/TPDDL (power supply)",
        "chain": ["Junior Engineer (Electrical)", "Assistant Engineer (Electrical)",
                  "Executive Engineer (Electrical)", "Superintending Engineer (Electrical)"],
        "sla_hours": {"high": 24, "medium": 72, "low": 120},
        "helpline": "MCD 311 app / 155305",
    },
    "water": {
        "agency": "Delhi Jal Board – Water Supply",
        "name": "Water Supply & Leakage",
        "alt_agency": None,
        "chain": ["Junior Engineer (Water)", "Zonal Engineer / Assistant Engineer (Water)",
                  "Executive Engineer (Water)", "Superintending Engineer (Circle)"],
        "sla_hours": {"high": 12, "medium": 48, "low": 96},
        "helpline": "DJB helpline 1916",
    },
    "sewerage": {
        "agency": "Delhi Jal Board – Sewerage",
        "name": "Sewer, Drains & Manholes",
        "alt_agency": "MCD / PWD for roadside storm-water drains, I&FC for large drains",
        "chain": ["Junior Engineer (Sewer)", "Assistant Engineer (Sewer)",
                  "Executive Engineer (Sewer)", "Superintending Engineer (Circle)"],
        "sla_hours": {"high": 12, "medium": 48, "low": 96},
        "helpline": "DJB helpline 1916",
    },
    "sanitation": {
        "agency": "MCD – DEMS (Sanitation)",
        "name": "Garbage & Sanitation",
        "alt_agency": None,
        "chain": ["Sanitary Inspector", "Sanitary Superintendent",
                  "Deputy Commissioner (Zone)", "Director, DEMS"],
        "sla_hours": {"high": 12, "medium": 24, "low": 48},
        "helpline": "MCD 311 app / 155305",
    },
    "enforcement": {
        "agency": "MCD – Enforcement / Delhi Traffic Police",
        "name": "Encroachment & Road Blockage",
        "alt_agency": "Delhi Traffic Police for vehicles obstructing traffic",
        "chain": ["Enforcement Inspector", "Assistant Commissioner (Enforcement)",
                  "Deputy Commissioner (Zone)", "Additional Commissioner"],
        "sla_hours": {"high": 12, "medium": 48, "low": 96},
        "helpline": "MCD 311 / Traffic Police 1095",
    },
    "civil": {
        "agency": "MCD – Engineering (Civil Works)",
        "name": "Footpaths, Railings & Public Property",
        "alt_agency": "PWD for flyovers and arterial road furniture",
        "chain": ["Junior Engineer (Civil)", "Assistant Engineer (Civil)",
                  "Executive Engineer (Civil)", "Superintending Engineer"],
        "sla_hours": {"high": 48, "medium": 240, "low": 504},
        "helpline": "MCD 311 app / 155305",
    },
    "general": {
        "agency": "Public Grievance Cell (PGMS)",
        "name": "Other Civic Issues",
        "alt_agency": None,
        "chain": ["Grievance Officer", "Nodal Grievance Officer",
                  "Deputy Commissioner (Zone)", "Public Grievances Commission"],
        "sla_hours": {"high": 72, "medium": 360, "low": 720},
        "helpline": "PGMS / 1031",
    },
}

CATEGORIES = {
    "pothole_road_damage": {"label": "Pothole / Road Damage", "emoji": "🛣️", "department": "roads"},
    "streetlight": {"label": "Streetlight Not Working", "emoji": "💡", "department": "electrical"},
    "water_leakage": {"label": "Water Leakage", "emoji": "🚰", "department": "water"},
    "drainage": {"label": "Drainage / Sewer Problem", "emoji": "🕳️", "department": "sewerage"},
    "garbage": {"label": "Garbage Accumulation", "emoji": "🗑️", "department": "sanitation"},
    "road_blockage": {"label": "Road Blockage", "emoji": "🚧", "department": "enforcement"},
    "damaged_infrastructure": {"label": "Damaged Public Infrastructure", "emoji": "🏗️", "department": "civil"},
    "other": {"label": "Other Civic Issue", "emoji": "📍", "department": "general"},
}

SAFETY_KEYWORDS = ["accident", "injur", "electrocut", "shock", "live wire", "current", "school", "children",
                   "kids", "hospital", "ambulance", "flood", "danger", "fatal", "death", "life risk",
                   "fall in", "haadsa", "emergency", "collapse", "open manhole"]


def department_for(category: str) -> str:
    return CATEGORIES.get(category, CATEGORIES["other"])["department"]


def sla_hours(category: str, severity: str) -> int:
    wf = WORKFLOWS[department_for(category)]
    return wf["sla_hours"].get(severity, wf["sla_hours"]["medium"])


def designation(department_id: str, level: int) -> str:
    chain = WORKFLOWS[department_id]["chain"]
    return chain[min(level, len(chain) - 1)]
