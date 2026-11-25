#!/usr/bin/env python3
"""Tutorial 55 fixture: the premium tenant rating (PR #467) on Oasis Crest.

LOCAL ONLY (the tutorial recording stack, backend :8084). Run it after `tutorials/recording-stack.sh seed`:

    python3 tutorials/seed/seed_rating_55.py
    tutorials/recording-stack.sh snapshot pre55     # every proof and capture of 55 starts from here

What it does, through the app's own APIs (idempotent):
  - the local super admin turns RENTER_RATING on for Oasis Crest Properties (off by default)
  - queues the rating recompute for the organisation and waits until the stored list has a row for every
    renter with a started contract, so the Tenant ratings page is not "being calculated" on camera
"""
from __future__ import annotations

import json
import os
import pathlib
import sys
import time
from urllib.parse import urlparse

import requests

REPO = pathlib.Path(__file__).resolve().parent.parent.parent
MANIFEST = pathlib.Path(os.environ.get(
    "TUTORIAL_SEED_MANIFEST", REPO / "tutorials" / "work" / "seed" / "oasis-crest.out.json"))
API = os.environ.get("TUTORIAL_BACKEND_URL", "http://localhost:8084").rstrip("/")
# DataInitializer's local super admin, as recording-stack.sh seed uses it (localhost-only test login).
SA_EMAIL = os.environ.get("TUTORIAL_SUPERADMIN_EMAIL", "admin@rentaxis.com")
SA_PASSWORD = os.environ.get("TUTORIAL_SUPERADMIN_PASSWORD", "admin123")
RATED = 3  # Ahmed, Fatima and Rajesh have posted contracts; Sara's is a draft


def log(msg):
    print(f"  • {msg}", flush=True)


def require_local(url):
    u = urlparse(url)
    if u.hostname not in ("localhost", "127.0.0.1") or u.port in (8081, 8082, 8083):
        sys.exit(f"Refusing {url}: the tutorial 55 fixture runs against the local recording stack only.")


def session(email, password, tenant=None):
    r = requests.post(f"{API}/api/auth/login", json={"email": email, "password": password}, timeout=30)
    r.raise_for_status()
    u = r.json()
    h = {"X-User-Id": str(u["id"]), "X-User-Role": u["role"], "Content-Type": "application/json"}
    tid = tenant or u.get("tenantId")
    if tid:
        h["X-Tenant-Id"] = h["X-User-Tenant-Id"] = str(tid)
    if u.get("token"):
        h["Authorization"] = f"Bearer {u['token']}"
    s = requests.Session()
    s.headers.update(h)
    return s


def main():
    require_local(API)
    seed = json.loads(MANIFEST.read_text())
    tenant = seed["tenant"]["id"]
    sa = session(SA_EMAIL, SA_PASSWORD)
    sa.put(f"{API}/api/admin/tenants/{tenant}/features/RENTER_RATING", json={"enabled": True}, timeout=30).raise_for_status()
    log("RENTER_RATING on for Oasis Crest Properties")

    sa.post(f"{API}/api/v1/admin/renter-ratings/recompute", params={"tenantId": tenant}, timeout=30).raise_for_status()
    admin = session(seed["adminLogin"]["email"], seed["adminLogin"]["password"])
    for _ in range(60):
        r = admin.get(f"{API}/api/v1/renter-ratings", params={"size": 50}, timeout=30)
        r.raise_for_status()
        rows = r.json().get("content", [])
        if len(rows) >= RATED:
            for row in rows:
                log(f"{row.get('renterName')}: {row.get('grade')} {row.get('score')}")
            return
        time.sleep(1)
    sys.exit("The rating recompute did not store the renters' rows within a minute.")


if __name__ == "__main__":
    main()
