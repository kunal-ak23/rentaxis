#!/usr/bin/env python3
"""Seed the "Al Ashram Demo Account" tenant with full demo data.

Repeatable, API-driven (no SQL). Exercises the same endpoints the web and
mobile apps use, so it doubles as a smoke test of the prod API.

What it creates:
  - Tenant "Al Ashram Demo Account" + TENANT_ADMIN login
  - Features enabled: LISTINGS, MEETINGS, LEASE_RENEWALS
  - 2 properties with rent-collection settings, 8 units
  - 4 renters with portal (RENTER) logins
  - 4 leases covering the cheque lifecycle:
      * cleared / deposited / collected (shows in "Cheques to deposit")
      * a bounced cheque with penalty (mark-failed)
      * a monthly lease with an overdue installment
      * a PENDING_SIGNATURE lease (payment plan visible before acceptance)
  - 4 published marketplace listings + 1 draft (vacant units)
  - 2 meetings (cheque replacement + property viewing), one approved

Credentials & IDs are printed and written to scripts/seed_demo_tenant.out.json.

Usage:
  python3 scripts/seed_demo_tenant.py                  # uses web/e2e-prod/.env.local
  PROD_BASE_URL=http://localhost:8080 \
  PROD_SUPERADMIN_EMAIL=... PROD_SUPERADMIN_PASSWORD=... \
  python3 scripts/seed_demo_tenant.py
"""
import json
import os
import re
import sys
import datetime as dt
from pathlib import Path

import requests

REPO_ROOT = Path(__file__).resolve().parent.parent
TENANT_NAME = "Al Ashram Demo Account"
ADMIN_EMAIL = "demo.admin@alashram-demo.ae"
ADMIN_PASSWORD = "AlAshram@Demo2026"
OUT_FILE = REPO_ROOT / "scripts" / "seed_demo_tenant.out.json"

TODAY = dt.date.today()


def load_env():
    env_file = REPO_ROOT / "web" / "e2e-prod" / ".env.local"
    if env_file.exists():
        for line in env_file.read_text().splitlines():
            m = re.match(r"^([A-Z_]+)=(.*)$", line.strip())
            if m and m.group(1) not in os.environ:
                os.environ[m.group(1)] = m.group(2).strip("'\"")


class Api:
    """Thin client mirroring the mobile apps' auth model: login once, then
    send X-User-* headers on every call."""

    def __init__(self, base_url):
        self.base = base_url.rstrip("/")
        self.s = requests.Session()
        self.headers = {}

    def login(self, email, password, tenant_id=None):
        body = {"email": email, "password": password}
        if tenant_id:
            body["tenantId"] = tenant_id
        r = self.s.post(f"{self.base}/api/auth/login", json=body, timeout=30)
        r.raise_for_status()
        u = r.json()
        self.headers = {
            "X-User-Id": str(u["id"]),
            "X-User-Role": u["role"],
        }
        if u.get("tenantId"):
            self.headers["X-Tenant-Id"] = str(u["tenantId"])
            self.headers["X-User-Tenant-Id"] = str(u["tenantId"])
        return u

    def login_nextauth(self, email, password):
        """NextAuth credentials login — needed for /api/proxy/* paths
        (Caddy only routes /api/v1 + /api/admin directly to the backend)."""
        csrf = self.s.get(f"{self.base}/api/auth/csrf", timeout=30).json()[
            "csrfToken"
        ]
        r = self.s.post(
            f"{self.base}/api/auth/callback/credentials",
            data={
                "csrfToken": csrf,
                "email": email,
                "password": password,
                "callbackUrl": f"{self.base}/",
                "json": "true",
            },
            timeout=30,
            allow_redirects=False,
        )
        if r.status_code >= 400:
            raise RuntimeError(f"NextAuth login failed ({r.status_code})")
        sess = self.s.get(f"{self.base}/api/auth/session", timeout=30).json()
        if not (sess or {}).get("user"):
            raise RuntimeError("NextAuth session has no user after login")

    def call(self, method, path, expect=(200, 201, 204), **kw):
        headers = kw.pop("headers", self.headers)
        r = self.s.request(
            method, f"{self.base}{path}", headers=headers, timeout=60, **kw
        )
        if r.status_code not in expect:
            raise RuntimeError(
                f"{method} {path} -> {r.status_code}: {r.text[:500]}"
            )
        if r.status_code == 204 or not r.text:
            return None
        try:
            return r.json()
        except ValueError:
            return r.text

    def get(self, path, **kw):
        return self.call("GET", path, **kw)

    def post(self, path, **kw):
        return self.call("POST", path, **kw)

    def put(self, path, **kw):
        return self.call("PUT", path, **kw)


def log(msg):
    print(f"  • {msg}", flush=True)


def iso(d):
    return d.isoformat()


def main():
    load_env()
    base = os.environ.get(
        "PROD_BASE_URL", "https://rentaxis.uaenorth.cloudapp.azure.com"
    )
    sa_email = os.environ.get("PROD_SUPERADMIN_EMAIL")
    sa_password = os.environ.get("PROD_SUPERADMIN_PASSWORD")
    if not sa_email or not sa_password:
        sys.exit(
            "PROD_SUPERADMIN_EMAIL / PROD_SUPERADMIN_PASSWORD not set "
            "(checked env and web/e2e-prod/.env.local)"
        )

    out = {"baseUrl": base, "seededAt": dt.datetime.now().isoformat()}

    print(f"Seeding '{TENANT_NAME}' on {base}")

    # ── 1. Superadmin: tenant + admin user + features ───────────────────────
    sa = Api(base)
    sa.login(sa_email, sa_password)
    log(f"logged in as superadmin {sa_email}")

    existing = [
        t for t in (sa.get("/api/admin/tenants") or []) if t["name"] == TENANT_NAME
    ]
    if existing:
        tenant = existing[0]
        log(f"tenant already exists, resuming: {tenant['id']}")
    else:
        tenant = sa.post("/api/admin/tenants", json={"name": TENANT_NAME})
        log(f"tenant created: {tenant['id']} (slug {tenant['slug']})")
    tenant_id = tenant["id"]
    out["tenant"] = {"id": tenant_id, "name": TENANT_NAME, "slug": tenant["slug"]}

    sa.put(
        f"/api/admin/tenants/{tenant_id}",
        json={
            "address": "Sheikh Zayed Road, Dubai, UAE",
            "phone": "+971-4-555-0100",
        },
    )

    for feature in ("LISTINGS", "MEETINGS", "LEASE_RENEWALS"):
        sa.put(
            f"/api/admin/tenants/{tenant_id}/features/{feature}",
            json={"enabled": True},
        )
    log("features enabled: LISTINGS, MEETINGS, LEASE_RENEWALS")

    try:
        sa.post(
            "/api/admin/users",
            json={
                "email": ADMIN_EMAIL,
                "password": ADMIN_PASSWORD,
                "name": "Al Ashram Demo Admin",
                "role": "TENANT_ADMIN",
                "tenantId": tenant_id,
                "phoneNumber": "+971501110000",
            },
        )
        log(f"tenant admin created: {ADMIN_EMAIL}")
    except RuntimeError:
        log(f"tenant admin already exists: {ADMIN_EMAIL}")
    out["adminLogin"] = {"email": ADMIN_EMAIL, "password": ADMIN_PASSWORD}

    # ── 2. Tenant admin: properties + units ─────────────────────────────────
    api = Api(base)
    admin_user = api.login(ADMIN_EMAIL, ADMIN_PASSWORD)
    admin_user_id = admin_user["id"]

    # Chart of accounts + account mappings — required before cheque
    # clear/deposit postings work. Idempotent (no-op if accounts exist).
    api.post("/api/v1/finance/accounts/seed")
    log("chart of accounts seeded")

    # GET /api/v1/properties returns summaries: {"property": {...}, vacancies...}
    existing_props = {
        s["property"]["nameEn"]: s["property"]
        for s in (api.get("/api/v1/properties") or [])
        if isinstance(s, dict) and s.get("property")
    }

    def make_property(name_en, name_ar, address):
        p = existing_props.get(name_en)
        if not p:
            p = api.post(
                "/api/v1/properties",
                json={
                    "nameEn": name_en,
                    "nameAr": name_ar,
                    "type": "RESIDENTIAL",
                    "emirate": "DUBAI",
                    "address": address,
                },
            )
        api.post(
            f"/api/v1/rent-settings/{p['id']}",
            json={
                "dueDayOfMonth": 1,
                "gracePeriodDays": 5,
                "fineBounceAmount": 1000.0,
                "fineSignatureMismatchAmount": 500.0,
                "fineAccountClosedAmount": 500.0,
                "fineGraceDays": 3,
                "finePerDayRate": 50.0,
            },
        )
        return p

    tower = make_property(
        "Al Ashram Residence Tower", "برج الأشرم السكني", "Al Barsha 1, Dubai"
    )
    marina = make_property(
        "Al Ashram Marina Heights", "أبراج الأشرم مارينا", "Dubai Marina, Dubai"
    )
    log(f"properties: {tower['nameEn']}, {marina['nameEn']}")

    def make_unit(prop, number, utype, sqft, rent):
        for u in api.get(f"/api/v1/units/property/{prop['id']}") or []:
            if u.get("unitNumber") == number:
                return u
        return api.post(
            "/api/v1/units",
            json={
                "property": {"id": prop["id"]},
                "unitNumber": number,
                "type": utype,
                "sizeSqft": sqft,
                "expectedRent": rent,
                "status": "VACANT",
            },
        )

    a101 = make_unit(tower, "A-101", "BHK2", 1150, 85000)
    a102 = make_unit(tower, "A-102", "BHK1", 780, 62000)
    a103 = make_unit(tower, "A-103", "BHK3", 1620, 120000)
    a201 = make_unit(tower, "A-201", "BHK2", 1180, 88000)
    a202 = make_unit(tower, "A-202", "STUDIO", 480, 42000)
    m1501 = make_unit(marina, "M-1501", "BHK2", 1300, 110000)
    m1502 = make_unit(marina, "M-1502", "PENTHOUSE", 3200, 320000)
    m803 = make_unit(marina, "M-803", "BHK1", 850, 78000)
    log("8 units created (3 will stay vacant for listings + 1 draft listing)")

    # ── 3. Renters with portal accounts ─────────────────────────────────────
    existing_renters = {
        r.get("email"): r for r in (api.get("/api/v1/renters") or [])
    }

    def make_renter(name_en, name_ar, email, phone):
        if email in existing_renters:
            log(f"renter {name_en} already exists (password unchanged)")
            return existing_renters[email]
        r = api.post(
            "/api/v1/renters",
            json={
                "nameEn": name_en,
                "nameAr": name_ar,
                "email": email,
                "phone": phone,
                "primaryLanguage": "EN",
                "createPortalAccount": True,
            },
        )
        log(f"renter {name_en}: portal {email} / {r.get('portalPassword')}")
        return r

    ahmed = make_renter(
        "Ahmed Hassan", "أحمد حسن", "ahmed.hassan@alashram-demo.ae", "+971501234001"
    )
    fatima = make_renter(
        "Fatima Al Zaabi", "فاطمة الزعابي", "fatima.alzaabi@alashram-demo.ae",
        "+971501234002",
    )
    rajesh = make_renter(
        "Rajesh Kumar", "راجيش كومار", "rajesh.kumar@alashram-demo.ae",
        "+971501234003",
    )
    sara = make_renter(
        "Sara Mansour", "سارة منصور", "sara.mansour@alashram-demo.ae",
        "+971501234004",
    )
    out["renterLogins"] = [
        {"name": r["nameEn"], "email": r["email"], "password": r.get("portalPassword")}
        for r in (ahmed, fatima, rajesh, sara)
    ]

    # ── 4. Leases ────────────────────────────────────────────────────────────
    year_start = dt.date(TODAY.year, 1, 1)
    year_end = dt.date(TODAY.year, 12, 31)

    existing_leases = {
        l.get("unitId"): l for l in (api.get("/api/v1/leases") or [])
    }

    def make_lease(unit, renter, rent, terms, distribution, deposit, charges=None,
                   booking=None, activate=True):
        if unit["id"] in existing_leases:
            return existing_leases[unit["id"]]
        body = {
            "unitId": unit["id"],
            "renterId": renter["id"],
            "startDate": iso(year_start),
            "endDate": iso(year_end),
            "rentAmount": rent,
            "depositAmount": deposit,
            "paymentTerms": terms,
            "installmentDistribution": distribution,
            "paymentMethod": "CHEQUE",
            "depositPaymentMethod": "CHEQUE",
            "agreementDate": iso(year_start - dt.timedelta(days=10)),
            "rentVatApplicable": False,
        }
        if charges:
            body["charges"] = charges
        if booking:
            body["bookingDeposit"] = booking
        lease = api.post("/api/v1/leases", json=body)
        if activate:
            api.put(f"/api/v1/leases/{lease['id']}/activate")
        return lease

    # Note: business rule — no cheque may exceed depositAmount, so deposits
    # here are >= the largest cheque in each plan.
    lease_ahmed = make_lease(
        a101, ahmed, 85000, 4, "LAST_LARGER", 22000,
        charges=[{"name": "Admin Fee", "amount": 1500.0, "vatApplicable": True,
                  "frequency": "ONE_TIME"}],
        booking={"amount": 5000.0, "chequeNumber": "100001",
                 "chequeDate": iso(year_start - dt.timedelta(days=12)),
                 "bankName": "Emirates NBD"},
    )
    lease_fatima = make_lease(a102, fatima, 62000, 4, "UNIFORM", 16000)
    # Deposit covers cheque (10,000 rent + 250 folded parking charge).
    lease_rajesh = make_lease(
        a103, rajesh, 120000, 12, "UNIFORM", 12000,
        charges=[{"name": "Parking", "amount": 250.0, "vatApplicable": False,
                  "frequency": "PER_INSTALLMENT"}],
    )
    log("3 active leases (quarterly LAST_LARGER, quarterly UNIFORM, monthly)")

    # PENDING_SIGNATURE lease — shows payment plan before acceptance.
    lease_sara = make_lease(m1501, sara, 110000, 4, "LAST_LARGER", 30000,
                            activate=False)
    if lease_sara.get("status") in (None, "DRAFT"):
        try:
            api.post(f"/api/v1/leases/{lease_sara['id']}/generate-contract")
            log("Sara's lease moved to PENDING_SIGNATURE "
                "(accept it live in the demo)")
        except RuntimeError as e:
            log(f"WARN generate-contract failed ({e}); lease left in DRAFT")
    out["leases"] = {
        "ahmed": lease_ahmed["id"],
        "fatima": lease_fatima["id"],
        "rajesh": lease_rajesh["id"],
        "sara": lease_sara["id"],
    }

    # ── 5. Cheque lifecycle on payment schedules ─────────────────────────────
    def rent_rows(lease_id):
        rows = api.get(f"/api/v1/payments/lease/{lease_id}") or []
        rows = [
            r for r in rows
            if not r.get("isBookingDeposit") and not r.get("isSecurityDeposit")
            and not r.get("isCharge")
        ]
        return sorted(rows, key=lambda r: r["installmentNumber"])

    def cheque_body(number, bank, date, payer):
        return {
            "chequeNumber": number,
            "bankName": bank,
            "payerName": payer,
            "chequeDate": iso(date),
        }

    def advance(row, target, body):
        """Walk a schedule row PENDING→COLLECTED→DEPOSITED→CLEARED/BOUNCED,
        skipping transitions already done (safe to re-run)."""
        rank = {"PENDING": 0, "COLLECTED": 1, "DEPOSITED": 2,
                "CLEARED": 3, "BOUNCED": 3}
        status = row.get("status", "PENDING")
        if status in ("BOUNCED", "CLEARED") or status == target:
            return
        pid = row["id"]
        if status == "PENDING" and rank[target] >= 1:
            api.put(f"/api/v1/payments/{pid}/collect", json=body)
            status = "COLLECTED"
        if status == "COLLECTED" and rank[target] >= 2:
            api.put(f"/api/v1/payments/{pid}/deposit", json={})
            status = "DEPOSITED"
        if status == "DEPOSITED" and target == "CLEARED":
            api.put(f"/api/v1/payments/{pid}/clear", json={})
        if status == "DEPOSITED" and target == "BOUNCED":
            api.post(f"/api/v1/payments/{pid}/mark-failed",
                     json={"failureReason": "BOUNCE",
                           "notes": "Insufficient funds"})

    # Ahmed: Q1 cleared, Q2 deposited, Q3 collected (banking date arrived →
    # shows in "Cheques to deposit"), Q4 pending.
    rows = rent_rows(lease_ahmed["id"])
    advance(rows[0], "CLEARED",
            cheque_body("200101", "Emirates NBD", year_start, "Ahmed Hassan"))
    advance(rows[1], "DEPOSITED",
            cheque_body("200102", "Emirates NBD", dt.date(TODAY.year, 4, 1),
                        "Ahmed Hassan"))
    advance(rows[2], "COLLECTED",
            cheque_body("200103", "Emirates NBD", TODAY - dt.timedelta(days=2),
                        "Ahmed Hassan"))
    log("Ahmed: cleared + deposited + collected-awaiting-deposit cheques")

    # Fatima: Q1 cleared; Q2 bounced (mark-failed → penalty).
    rows = rent_rows(lease_fatima["id"])
    advance(rows[0], "CLEARED",
            cheque_body("300201", "FAB", year_start, "Fatima Al Zaabi"))
    advance(rows[1], "BOUNCED",
            cheque_body("300202", "FAB", dt.date(TODAY.year, 4, 1),
                        "Fatima Al Zaabi"))
    bounced_payment_id = rows[1]["id"]
    log("Fatima: bounced Q2 cheque (penalty auto-created)")

    # Rajesh: Jan–May cleared, June left pending → overdue with penalty accruing.
    rows = rent_rows(lease_rajesh["id"])
    for i, row in enumerate(rows[:5]):
        d = dt.date(TODAY.year, i + 1, 1)
        advance(row, "CLEARED",
                cheque_body(f"4003{i:02d}", "Dubai Islamic Bank", d,
                            "Rajesh Kumar"))
    log("Rajesh: 5 cleared monthly cheques; June installment overdue")

    # ── 6. Marketplace listings for vacant units ─────────────────────────────
    # Caddy doesn't route /api/listings to the backend (only /api/v1 and
    # /api/admin), so go through the Next.js proxy with a NextAuth session.
    api.login_nextauth(ADMIN_EMAIL, ADMIN_PASSWORD)
    LISTINGS = "/api/proxy/listings"

    listed_units = set()
    page = api.get(f"{LISTINGS}?page=0&size=100", headers={}) or {}
    for l in page.get("content", []):
        listed_units.add(l.get("title"))

    def make_listing(unit, title, beds, baths, rent, furnishing, view, amenities,
                     publish=True, description=None):
        if title in listed_units:
            return None
        listing = api.post(
            LISTINGS,
            headers={},
            json={
                "unitId": unit["id"],
                "titleEn": title,
                "descriptionEn": description or
                f"{title} — well-maintained unit in a prime Dubai location, "
                "managed by Al Ashram Properties.",
                "bedrooms": beds,
                "bathrooms": baths,
                "sizeSqft": unit.get("sizeSqft"),
                "furnishing": furnishing,
                "viewType": view,
                "annualRent": rent,
                "securityDeposit": round(rent * 0.05, 2),
                "minLeaseMonths": 12,
                "chequesAccepted": 4,
                "availableFrom": iso(TODAY + dt.timedelta(days=14)),
                "amenities": [{"amenity": a} for a in amenities],
            },
        )
        if publish:
            api.post(f"{LISTINGS}/{listing['id']}/publish", headers={})
        return listing

    make_listing(a201, "Bright 2BR in Al Barsha", 2, 2, 88000,
                 "SEMI_FURNISHED", "CITY",
                 ["GYM", "POOL", "COVERED_PARKING", "BALCONY"])
    make_listing(a202, "Cozy Studio near Mall of the Emirates", 0, 1, 42000,
                 "FULLY_FURNISHED", "COMMUNITY",
                 ["GYM", "NEAR_MALL", "NEAR_METRO"])
    make_listing(m1502, "Luxury Marina Penthouse with Sea View", 4, 5, 320000,
                 "FULLY_FURNISHED", "SEA",
                 ["POOL", "GYM", "SAUNA", "CONCIERGE", "SEA_VIEW", "SMART_HOME"])
    make_listing(m803, "Marina 1BR with Partial Sea View", 1, 1, 78000,
                 "UNFURNISHED", "SEA", ["GYM", "POOL", "NEAR_METRO"],
                 publish=False)
    log("4 listings created (3 published, 1 draft)")

    # ── 7. Meetings ──────────────────────────────────────────────────────────
    def at_hour(days_ahead, hour):
        d = dt.datetime.combine(
            TODAY + dt.timedelta(days=days_ahead), dt.time(hour, 0)
        )
        # UAE is UTC+4; send as UTC instant.
        return (d - dt.timedelta(hours=4)).strftime("%Y-%m-%dT%H:%M:%SZ")

    existing_meetings = {
        m.get("title") for m in (api.get("/api/v1/meetings") or [])
        if isinstance(m, dict)
    }

    def meeting_exists(title):
        return title in existing_meetings

    if not meeting_exists("Replacement cheque — Fatima Al Zaabi (A-102)"):
        m1 = api.post(
            "/api/v1/meetings",
            json={
                "type": "OFFICE_VISIT",
                "purpose": "CHEQUE_REPLACEMENT",
                "title": "Replacement cheque — Fatima Al Zaabi (A-102)",
                "notes": "Bounced Q2 cheque; renter bringing a replacement.",
                "slotStart": at_hour(1, 10),
                "hostUserId": admin_user_id,
                "leaseId": lease_fatima["id"],
                "propertyId": tower["id"],
                "unitId": a102["id"],
                "paymentScheduleIds": [bounced_payment_id],
            },
        )
        api.put(f"/api/v1/meetings/{m1['id']}/approve")
    if not meeting_exists("Penthouse viewing — M-1502"):
        api.post(
            "/api/v1/meetings",
            json={
                "type": "PROPERTY_VISIT",
                "purpose": "PROPERTY_VIEWING",
                "title": "Penthouse viewing — M-1502",
                "notes": "Prospect viewing for the marina penthouse listing.",
                "slotStart": at_hour(2, 15),
                "hostUserId": admin_user_id,
                "propertyId": marina["id"],
                "unitId": m1502["id"],
            },
        )
    log("2 meetings ensured (cheque replacement approved, viewing requested)")

    # ── Done ─────────────────────────────────────────────────────────────────
    OUT_FILE.write_text(json.dumps(out, indent=2))
    print(f"\nDone. Credentials and IDs written to {OUT_FILE}")
    print(f"  Tenant admin : {ADMIN_EMAIL} / {ADMIN_PASSWORD}")
    for r in out["renterLogins"]:
        print(f"  Renter       : {r['email']} / {r['password']}")


if __name__ == "__main__":
    main()
