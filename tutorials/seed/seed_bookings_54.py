#!/usr/bin/env python3
"""Tutorial 54 fixture: booking fees charged through journal vouchers (PR #468).

LOCAL ONLY (the tutorial recording stack, backend :8084). Run it after `tutorials/recording-stack.sh seed`:

    python3 tutorials/seed/seed_bookings_54.py
    tutorials/recording-stack.sh snapshot pre54     # every proof and capture of 54 starts from here

What it sets, through the app's own APIs (idempotent, never deletes):
  - the Residents Fitness Centre on Oasis Crest Residence Tower: PER_DAY, AED 30 (the renter's request
    shows the fee for the chosen dates)
  - parking bay B2-18 back to FREE with no income account: the video sets it to Per month on camera
"""
from __future__ import annotations

import json
import os
import pathlib
import sys
from urllib.parse import urlparse

import requests

REPO = pathlib.Path(__file__).resolve().parent.parent.parent
MANIFEST = pathlib.Path(os.environ.get(
    "TUTORIAL_SEED_MANIFEST", REPO / "tutorials" / "work" / "seed" / "oasis-crest.out.json"))
API = os.environ.get("TUTORIAL_BACKEND_URL", "http://localhost:8084").rstrip("/")
AMENITY_FEE = 30


def log(msg):
    print(f"  • {msg}", flush=True)


def require_local(url):
    u = urlparse(url)
    if u.hostname not in ("localhost", "127.0.0.1") or u.port in (8081, 8082, 8083):
        sys.exit(f"Refusing {url}: the tutorial 54 fixture runs against the local recording stack only.")


def session(login):
    r = requests.post(f"{API}/api/auth/login", json={"email": login["email"], "password": login["password"]}, timeout=30)
    r.raise_for_status()
    u = r.json()
    h = {"X-User-Id": str(u["id"]), "X-User-Role": u["role"], "Content-Type": "application/json"}
    if u.get("tenantId"):
        h["X-Tenant-Id"] = h["X-User-Tenant-Id"] = str(u["tenantId"])
    if u.get("token"):
        h["Authorization"] = f"Bearer {u['token']}"
    s = requests.Session()
    s.headers.update(h)
    return s


def main():
    require_local(API)
    seed = json.loads(MANIFEST.read_text())
    admin = session(seed["adminLogin"])
    tower = seed["properties"]["tower"]

    amenity_id = seed["amenityId"]
    r = admin.put(f"{API}/api/v1/amenities/{amenity_id}", json={"feeType": "PER_DAY", "feeAmount": AMENITY_FEE}, timeout=30)
    r.raise_for_status()
    a = r.json()
    assert a["feeType"] == "PER_DAY" and float(a["feeAmount"]) == AMENITY_FEE, a
    log(f"{a['nameEn']}: AED {AMENITY_FEE} per day")

    spot_id = seed["parkingSpotId"]
    r = admin.put(f"{API}/api/v1/parking-spots/{spot_id}", json={"feeType": "FREE", "feeAmount": 0, "clearIncomeAccount": True}, timeout=30)
    r.raise_for_status()
    s = r.json()
    assert s["feeType"] == "FREE", s
    log(f"parking bay {s['spotNumber']}: free (set to Per month on camera)")
    assert s["propertyId"] == tower


if __name__ == "__main__":
    main()
