#!/usr/bin/env python3
"""Seed a named demo tenant with full demo data.

Repeatable, API-driven (no SQL). Exercises the same endpoints the web and
mobile apps use, so it doubles as a smoke test of the prod API.

What it creates:
  - Named demo tenant + TENANT_ADMIN login
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
TENANT_NAME = os.environ.get("DEMO_TENANT_NAME", "Al Ashram Demo Account")
DEMO_BRAND = os.environ.get("DEMO_BRAND", "Al Ashram")
DEMO_EMAIL_DOMAIN = os.environ.get("DEMO_EMAIL_DOMAIN", "alashramdemo.com")
# One easy password everywhere — this is throwaway demo data.
DEMO_PASSWORD = os.environ.get("DEMO_PASSWORD", "Demo@1234")
ADMIN_EMAIL = os.environ.get("DEMO_ADMIN_EMAIL", f"admin@{DEMO_EMAIL_DOMAIN}")
ADMIN_PASSWORD = DEMO_PASSWORD
OUT_FILE = Path(
    os.environ.get(
        "DEMO_OUTPUT_FILE",
        str(REPO_ROOT / "scripts" / "seed_demo_tenant.out.json"),
    )
)

TODAY = dt.date.today()
REDACT_CREDENTIALS = "--redact-credentials" in sys.argv


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


# Royalty-free Unsplash photos (hotlink-permitted CDN), curated per listing.
# Each entry: (photo id, caption). Downloaded once into /tmp and validated.
_PHOTO_SETS = {
    "Bright 2BR in Al Barsha": [
        ("photo-1522708323590-d24dbb6b0267", "Living room"),
        ("photo-1560185007-cde436f6a4d0", "Master bedroom"),
        ("photo-1556912173-3bb406ef7e77", "Kitchen"),
        ("photo-1493809842364-78817add7ffb", "Dining area"),
        ("photo-1512917774080-9991f1c4c750", "Building exterior"),
    ],
    "Cozy Studio near Mall of the Emirates": [
        ("photo-1502672260266-1c1ef2d93688", "Studio living space"),
        ("photo-1484154218962-a197022b5858", "Kitchenette"),
        ("photo-1505693416388-ac5ce068fe85", "Sleeping area"),
        ("photo-1554995207-c18c203602cb", "Lounge corner"),
    ],
    "Luxury Marina Penthouse with Sea View": [
        ("photo-1512453979798-5ea266f8880c", "Dubai Marina view"),
        ("photo-1567767292278-a4f21aa2d36e", "Living room"),
        ("photo-1571902943202-507ec2618e8f", "Infinity pool"),
        ("photo-1540518614846-7eded433c457", "Master suite"),
        ("photo-1613490493576-7fde63acd811", "Terrace at dusk"),
    ],
    "Marina 1BR with Partial Sea View": [
        ("photo-1546412414-e1885259563a", "Marina skyline"),
        ("photo-1522771739844-6a9f6d5f14af", "Living area"),
        ("photo-1560185127-6ed189bf02f4", "Bedroom"),
        ("photo-1556909114-f6e7ad7d3136", "Kitchen"),
    ],
}

_LISTING_COORDS = {
    "Bright 2BR in Al Barsha": (25.1124, 55.1965),
    "Cozy Studio near Mall of the Emirates": (25.1181, 55.2004),
    "Luxury Marina Penthouse with Sea View": (25.0805, 55.1403),
    "Marina 1BR with Partial Sea View": (25.0772, 55.1385),
}


def _curated_photos(title):
    """Download the curated photo set for a listing into /tmp (cached).
    Returns [(path, caption)] with only successfully validated images."""
    out_dir = Path("/tmp/rentaxis-listing-photos")
    out_dir.mkdir(exist_ok=True)
    results = []
    for photo_id, caption in _PHOTO_SETS.get(title, []):
        p = out_dir / f"{photo_id}.jpg"
        if not p.exists() or p.stat().st_size < 30_000:
            url = f"https://images.unsplash.com/{photo_id}?w=1600&q=80&fm=jpg"
            try:
                r = requests.get(url, timeout=60)
                if (r.status_code == 200
                        and r.headers.get("content-type", "").startswith("image")
                        and len(r.content) > 30_000):
                    p.write_bytes(r.content)
                else:
                    print(f"  ! photo {photo_id} unavailable "
                          f"({r.status_code}), skipping")
                    continue
            except requests.RequestException as e:
                print(f"  ! photo {photo_id} failed: {e}")
                continue
        results.append((str(p), caption))
    return results


def _listing_images(title, listing_id):
    """Generate two presentable placeholder photos (cover skyline + interior)
    for a listing. Pure-PIL, deterministic per listing id."""
    from PIL import Image, ImageDraw, ImageFont

    out_dir = Path("/tmp/rentaxis-listing-media")
    out_dir.mkdir(exist_ok=True)
    seed_val = int(str(listing_id).replace("-", "")[:8], 16)

    def font(size, bold=False):
        path = ("/System/Library/Fonts/Supplemental/Arial Bold.ttf" if bold
                else "/System/Library/Fonts/Supplemental/Arial.ttf")
        try:
            return ImageFont.truetype(path, size)
        except OSError:
            return ImageFont.load_default()

    paths = []
    palettes = [
        ((16, 42, 84), (120, 170, 215)),    # cover: navy → sky
        ((242, 236, 226), (196, 178, 150)), # interior: warm neutrals
    ]
    for idx, (top, bottom) in enumerate(palettes):
        w, h = 1200, 800
        img = Image.new("RGB", (w, h))
        d = ImageDraw.Draw(img)
        for y in range(h):  # vertical gradient
            t = y / h
            d.line([(0, y), (w, y)], fill=tuple(
                int(top[c] + (bottom[c] - top[c]) * t) for c in range(3)))
        if idx == 0:
            # simple skyline silhouette
            x = 0
            n = seed_val
            while x < w:
                n = (n * 1103515245 + 12345) % (2 ** 31)
                bw = 60 + n % 120
                bh = 180 + (n >> 8) % 320
                d.rectangle([x, h - bh, x + bw, h], fill=(10, 24, 48))
                for wy in range(h - bh + 20, h - 20, 36):
                    for wx in range(x + 10, x + bw - 10, 28):
                        if (wx + wy + n) % 3 == 0:
                            d.rectangle([wx, wy, wx + 10, wy + 14],
                                        fill=(235, 200, 120))
                x += bw + 14
        else:
            # interior: floor line + window + furniture blocks
            d.rectangle([0, 540, w, h], fill=(168, 144, 112))
            d.rectangle([120, 120, 520, 460], fill=(200, 224, 240),
                        outline=(120, 110, 95), width=8)
            d.line([(320, 120), (320, 460)], fill=(120, 110, 95), width=8)
            d.rectangle([640, 380, 1080, 560], fill=(94, 76, 60))
            d.rectangle([660, 300, 1060, 390], fill=(120, 98, 78))
        band_h = 96
        d.rectangle([0, h - band_h, w, h], fill=(12, 28, 56))
        d.text((30, h - band_h + 16), title[:48], font=font(34, bold=True),
               fill=(255, 255, 255))
        d.text((30, h - band_h + 58), f"{DEMO_BRAND} Properties · Dubai",
               font=font(22), fill=(220, 190, 120))
        p = out_dir / f"{listing_id}-{idx}.jpg"
        img.save(p, "JPEG", quality=88)
        paths.append(str(p))
    return paths


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
    if "--delete-only" in sys.argv:
        if not existing:
            log(f"tenant does not exist: {TENANT_NAME}")
            return
        if len(existing) != 1:
            raise RuntimeError(
                f"Refusing to delete {len(existing)} tenants named {TENANT_NAME!r}"
            )
        old_id = existing[0]["id"]
        sa.s.delete(
            f"{sa.base}/api/admin/tenants/{old_id}",
            params={"confirmName": TENANT_NAME},
            headers=sa.headers,
            timeout=120,
        ).raise_for_status()
        log(f"tenant deleted: {old_id}")
        return
    if existing and "--reset" in sys.argv:
        old_id = existing[0]["id"]
        sa.s.delete(
            f"{sa.base}/api/admin/tenants/{old_id}",
            params={"confirmName": TENANT_NAME},
            headers=sa.headers,
            timeout=120,
        ).raise_for_status()
        log(f"tenant wiped for reseed: {old_id}")
        existing = []
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

    for feature in (
        "LISTINGS",
        "MEETINGS",
        "EMAIL_NOTIFICATIONS",
        "LEASE_RENEWALS",
        "GATEPASS",
    ):
        sa.put(
            f"/api/admin/tenants/{tenant_id}/features/{feature}",
            json={"enabled": True},
        )
    log("features enabled: LISTINGS, MEETINGS, EMAIL_NOTIFICATIONS, "
        "LEASE_RENEWALS, GATEPASS")

    try:
        sa.post(
            "/api/admin/users",
            json={
                "email": ADMIN_EMAIL,
                "password": ADMIN_PASSWORD,
                "name": f"{DEMO_BRAND} Demo Admin",
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
        f"{DEMO_BRAND} Residence Tower", "برج رنت أكسيس السكني", "Al Barsha 1, Dubai"
    )
    marina = make_property(
        f"{DEMO_BRAND} Marina Heights", "أبراج رنت أكسيس مارينا", "Dubai Marina, Dubai"
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
    out["properties"] = {"tower": tower["id"], "marina": marina["id"]}
    out["units"] = {
        "a101": a101["id"], "a102": a102["id"], "a103": a103["id"],
        "a201": a201["id"], "a202": a202["id"],
        "m1501": m1501["id"], "m1502": m1502["id"], "m803": m803["id"],
    }
    log("8 units created (3 will stay vacant for listings + 1 draft listing)")

    # ── 3. Renters with portal accounts ─────────────────────────────────────
    existing_renters = {
        r.get("email"): r for r in (api.get("/api/v1/renters") or [])
    }

    def make_renter(name_en, name_ar, email, phone):
        r = existing_renters.get(email)
        if r is None:
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
        # Reset the auto-generated portal password to the shared demo one.
        # updateUser overwrites every field, so resend the full identity.
        if r.get("userId"):
            api.put(
                f"/api/admin/users/{r['userId']}",
                json={
                    "email": email,
                    "password": DEMO_PASSWORD,
                    "name": name_en,
                    "role": "RENTER",
                    "tenantId": tenant_id,
                    "phoneNumber": phone,
                },
            )
        if REDACT_CREDENTIALS:
            log(f"renter {name_en}: portal account ready")
        else:
            log(f"renter {name_en}: portal {email} / {DEMO_PASSWORD}")
        return r

    ahmed = make_renter(
        "Ahmed Hassan", "أحمد حسن", f"ahmed@{DEMO_EMAIL_DOMAIN}", "+971501234001"
    )
    fatima = make_renter(
        "Fatima Al Zaabi", "فاطمة الزعابي", f"fatima@{DEMO_EMAIL_DOMAIN}",
        "+971501234002",
    )
    rajesh = make_renter(
        "Rajesh Kumar", "راجيش كومار", f"rajesh@{DEMO_EMAIL_DOMAIN}",
        "+971501234003",
    )
    sara = make_renter(
        "Sara Mansour", "سارة منصور", f"sara@{DEMO_EMAIL_DOMAIN}",
        "+971501234004",
    )
    out["renterLogins"] = [
        {"name": r["nameEn"], "email": r["email"], "password": DEMO_PASSWORD}
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
        skipping transitions already done (safe to re-run). Each transition
        carries a realistic value date derived from the cheque date (handed
        over on the cheque date, banked next day, cleared/bounced a few days
        later) — never in the future."""
        rank = {"PENDING": 0, "COLLECTED": 1, "DEPOSITED": 2,
                "CLEARED": 3, "BOUNCED": 3}
        status = row.get("status", "PENDING")
        if status in ("BOUNCED", "CLEARED") or status == target:
            return
        pid = row["id"]
        cheque_date = dt.date.fromisoformat(body["chequeDate"])

        def eff(days_after):
            return iso(min(cheque_date + dt.timedelta(days=days_after), TODAY))

        if status == "PENDING" and rank[target] >= 1:
            api.put(f"/api/v1/payments/{pid}/collect",
                    json={**body, "effectiveDate": eff(0)})
            status = "COLLECTED"
        if status == "COLLECTED" and rank[target] >= 2:
            api.put(f"/api/v1/payments/{pid}/deposit",
                    json={"effectiveDate": eff(1)})
            status = "DEPOSITED"
        if status == "DEPOSITED" and target == "CLEARED":
            api.put(f"/api/v1/payments/{pid}/clear",
                    json={"effectiveDate": eff(4)})
        if status == "DEPOSITED" and target == "BOUNCED":
            api.post(f"/api/v1/payments/{pid}/mark-failed",
                     json={"failureReason": "BOUNCE",
                           "notes": "Insufficient funds",
                           "effectiveDate": eff(5)})

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

    # Security deposits, booking deposit and charges on the ACTIVE leases were
    # handed over at signing — clear them so the dashboard collection trend
    # tracks expected. (Rent rows above keep their scripted lifecycle; Sara's
    # PENDING_SIGNATURE lease is intentionally untouched.)
    banks = {lease_ahmed["id"]: ("Emirates NBD", "Ahmed Hassan"),
             lease_fatima["id"]: ("FAB", "Fatima Al Zaabi"),
             lease_rajesh["id"]: ("Dubai Islamic Bank", "Rajesh Kumar")}
    seq = 900001
    for lease_id, (bank, payer) in banks.items():
        for row in api.get(f"/api/v1/payments/lease/{lease_id}") or []:
            non_rent = (row.get("isBookingDeposit") or row.get("isSecurityDeposit")
                        or row.get("isCharge"))
            if not non_rent:
                continue
            advance(row, "CLEARED", cheque_body(str(seq), bank, year_start, payer))
            seq += 1
    log("deposit / charge rows cleared for active leases")

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
                f"managed by {DEMO_BRAND} Properties.",
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

    # SEO metadata + placeholder photos for every listing that lacks them.
    page = api.get(f"{LISTINGS}?page=0&size=100", headers={}) or {}
    out["listings"] = {
        (listing.get("titleEn") or listing.get("title")): listing["id"]
        for listing in page.get("content", [])
    }
    for summary in page.get("content", []):
        lid = summary["id"]
        detail = api.get(f"{LISTINGS}/{lid}", headers={}) or {}
        title = detail.get("titleEn") or summary.get("title") or "Dubai Apartment"

        if not detail.get("seoTitle"):
            beds = detail.get("bedrooms")
            beds_label = "Studio" if beds in (0, None) else f"{beds}BR"
            api.put(f"{LISTINGS}/{lid}", headers={}, json={
                "seoTitle": f"{title} | {DEMO_BRAND} Properties Dubai",
                "seoDescription": (
                    f"{beds_label} for rent in Dubai — {title}. "
                    f"Annual rent AED {detail.get('annualRent') or ''}. "
                    f"Managed by {DEMO_BRAND} Properties; flexible cheques, "
                    "well-maintained building, quick move-in."
                )[:300],
                "seoKeywords": ", ".join(filter(None, [
                    "dubai apartment for rent", beds_label.lower(),
                    (detail.get("furnishing") or "").replace("_", " ").lower(),
                    f"{DEMO_BRAND.lower()} properties",
                ])),
            })
            log(f"SEO set: {title}")

        # Real coordinates so the listing Location tab has a pin.
        if not detail.get("lat") and title in _LISTING_COORDS:
            lat, lng = _LISTING_COORDS[title]
            api.put(f"{LISTINGS}/{lid}", headers={}, json={"lat": lat, "lng": lng})
            log(f"coordinates set: {title} ({lat}, {lng})")

        # Real curated photos (4-5 per listing). If the listing only has the
        # old generated placeholders (<4 photos), replace them wholesale.
        photos = _curated_photos(title)
        if len(photos) >= 4 and len(detail.get("media") or []) < 4:
            for m in detail.get("media") or []:
                api.call("DELETE", f"{LISTINGS}/{lid}/media/{m['id']}",
                         headers={})
            for i, (path, caption) in enumerate(photos):
                with open(path, "rb") as fh:
                    r = api.s.post(
                        f"{api.base}{LISTINGS}/{lid}/media",
                        files={"file": (os.path.basename(path), fh, "image/jpeg")},
                        data={"isCover": "true" if i == 0 else "false",
                              "caption": caption},
                        timeout=120,
                    )
                    if r.status_code not in (200, 201):
                        log(f"WARN media upload failed for {title}: "
                            f"{r.status_code} {r.text[:120]}")
            log(f"media uploaded: {title} ({len(photos)} photos)")
        elif len(photos) < 4 and len(detail.get("media") or []) < 4:
            # Network/photo failures: fall back to generated placeholders so
            # the listing is never left bare.
            for i, path in enumerate(_listing_images(title, lid)):
                with open(path, "rb") as fh:
                    api.s.post(
                        f"{api.base}{LISTINGS}/{lid}/media",
                        files={"file": (os.path.basename(path), fh, "image/jpeg")},
                        data={"isCover": "true" if i == 0 else "false",
                              "caption": "Exterior view" if i == 0 else "Living area"},
                        timeout=120,
                    )
            log(f"media uploaded (fallback placeholders): {title}")

    # ── 6b. Vendors + split expenses ─────────────────────────────────────────
    # Demonstrates the split-transaction capability: one vendor invoice
    # allocated across multiple properties / units.
    existing_vendors = {
        v.get("nameEn"): v for v in (api.get("/api/v1/vendors") or [])
        if isinstance(v, dict)
    }

    def make_vendor(name_en, name_ar, contact, phone):
        if name_en in existing_vendors:
            return existing_vendors[name_en]
        return api.post("/api/v1/vendors", json={
            "nameEn": name_en, "nameAr": name_ar,
            "contactPerson": contact, "phone": phone,
        })

    fm_vendor = make_vendor("Emirates Facility Management", "إدارة المرافق",
                            "Imran Shaikh", "+97143330001")
    cleaning_vendor = make_vendor("Gulf Cleaning Services", "خدمات الخليج للتنظيف",
                                  "Maria Santos", "+97143330002")

    def account_id(code):
        return api.get(f"/api/v1/finance/accounts/code/{code}")["id"]

    existing_txn_descriptions = {
        t.get("description")
        for t in (api.get("/api/v1/finance/transactions") or [])
        if isinstance(t, dict)
    }

    def split_expense(description, code, date, vendor, splits):
        if description in existing_txn_descriptions:
            return
        api.post("/api/v1/finance/transactions/split", json={
            "date": iso(date),
            "description": description,
            "accountId": account_id(code),
            "debit": float(sum(s["amount"] for s in splits)),
            "vendorId": vendor["id"],
            "splits": splits,
        })
        log(f"split expense: {description}")

    split_expense(
        "Fire safety AMC 2026 — both towers", "D-01-08",
        dt.date(TODAY.year, 2, 10), fm_vendor,
        [{"propertyId": tower["id"], "amount": 5000.0},
         {"propertyId": marina["id"], "amount": 4000.0}],
    )
    split_expense(
        "Q1 common-area deep cleaning", "D-01-04",
        dt.date(TODAY.year, 4, 5), cleaning_vendor,
        [{"propertyId": tower["id"], "amount": 3500.0},
         {"propertyId": marina["id"], "amount": 2500.0}],
    )
    split_expense(
        "AC compressor repairs — units A-101 / A-103", "D-01-03",
        dt.date(TODAY.year, 5, 18), fm_vendor,
        [{"propertyId": tower["id"], "unitId": a101["id"], "amount": 1200.0},
         {"propertyId": tower["id"], "unitId": a103["id"], "amount": 1600.0}],
    )

    # ── 7. Meetings ──────────────────────────────────────────────────────────
    def at_hour(days_ahead, hour):
        d = dt.datetime.combine(
            TODAY + dt.timedelta(days=days_ahead), dt.time(hour, 0)
        )
        # UAE is UTC+4; send as UTC instant.
        return (d - dt.timedelta(hours=4)).strftime("%Y-%m-%dT%H:%M:%SZ")

    meetings_raw = api.get("/api/v1/meetings?page=0&size=100") or []
    if isinstance(meetings_raw, dict):
        meetings_raw = meetings_raw.get("content", [])
    existing_meetings = {
        m.get("title") for m in meetings_raw if isinstance(m, dict)
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
    meetings_payload = api.get("/api/v1/meetings?page=0&size=100") or {}
    meetings_final = (
        meetings_payload.get("content", [])
        if isinstance(meetings_payload, dict)
        else meetings_payload
    )
    out["meetings"] = {
        meeting.get("title"): meeting["id"]
        for meeting in meetings_final
        if meeting.get("title") and meeting.get("id")
    }
    log("2 meetings ensured (cheque replacement approved, viewing requested)")

    # ── 8. Operational tutorial fixtures ───────────────────────────────────
    # These records keep the property, staff, ticket, booking, promotions, and
    # gate-pass tutorial screens useful without requiring a live customer.
    def page_items(payload):
        if isinstance(payload, list):
            return payload
        if isinstance(payload, dict) and isinstance(payload.get("content"), list):
            return payload["content"]
        return []

    buildings = api.get(f"/api/v1/buildings/property/{tower['id']}") or []
    building = next(
        (b for b in buildings if b.get("nameEn") == "Tutorial Operations Tower"),
        None,
    )
    if not building:
        building = api.post("/api/v1/buildings", json={
            "property": {"id": tower["id"]},
            "nameEn": "Tutorial Operations Tower",
            "nameAr": "برج العمليات التجريبي",
            "floors": 12,
        })

    contacts = api.get(f"/api/v1/properties/{tower['id']}/contacts") or []
    if not any(c.get("name") == "Tutorial Maintenance Desk" for c in contacts):
        api.post(f"/api/v1/properties/{tower['id']}/contacts", json={
            "category": "BUILDING_MAINTENANCE",
            "name": "Tutorial Maintenance Desk",
            "phone": "+971500000003",
            "email": "maintenance@tutorial.example.com",
            "notes": "Available around the clock",
            "sortOrder": 0,
        })

    amenities = page_items(api.get(f"/api/v1/amenities?propertyId={tower['id']}"))
    amenity = next(
        (a for a in amenities if a.get("nameEn") == "Residents Fitness Centre"),
        None,
    )
    if not amenity:
        amenity = api.post("/api/v1/amenities", json={
            "propertyId": tower["id"],
            "nameEn": "Residents Fitness Centre",
            "nameAr": "مركز لياقة السكان",
            "description": "Bookable resident gym for tutorial demonstrations.",
            "bookable": True,
            "buildingIds": [],
        })
    else:
        amenity = api.put(
            f"/api/v1/amenities/{amenity['id']}",
            json={"buildingIds": [], "active": True, "bookable": True},
        )

    parking_spots = page_items(
        api.get(f"/api/v1/parking-spots?propertyId={tower['id']}")
    )
    parking = next(
        (p for p in parking_spots if p.get("spotNumber") == "TUTORIAL-B2-18"),
        None,
    )
    if not parking:
        parking = api.post("/api/v1/parking-spots", json={
            "propertyId": tower["id"],
            "spotNumber": "TUTORIAL-B2-18",
            "level": "B2",
            "covered": True,
            "buildingIds": [],
        })
    else:
        parking = api.put(
            f"/api/v1/parking-spots/{parking['id']}",
            json={"buildingIds": [], "active": True},
        )

    staff = api.get(f"/api/v1/staff/by-property/{tower['id']}") or []
    if not any(s.get("employeeId") == "TUTORIAL-OPS-001" for s in staff):
        api.post("/api/v1/staff", json={
            "nameEn": "Omar Tutorial",
            "nameAr": "عمر التجريبي",
            "employeeId": "TUTORIAL-OPS-001",
            "designation": "Facilities Coordinator",
            "department": "Operations",
            "monthlySalary": 7500,
            "joinDate": iso(TODAY - dt.timedelta(days=120)),
            "phone": "+971500000004",
            "emiratesId": "",
            "passportNumber": "",
            "active": True,
            "property": {"id": tower["id"]},
        })
    log("property operations ready: building, contact, amenity, parking, staff")

    existing_users = api.get("/api/admin/users") or []

    def ensure_operator(email, name, role, phone_number):
        user = next((u for u in existing_users if u.get("email") == email), None)
        if not user:
            user = api.post("/api/admin/users", json={
                "email": email,
                "password": DEMO_PASSWORD,
                "name": name,
                "role": role,
                "tenantId": tenant_id,
                "phoneNumber": phone_number,
            })
            existing_users.append(user)
        try:
            api.post(f"/api/admin/users/{user['id']}/properties/{tower['id']}")
        except RuntimeError as exc:
            if "409" not in str(exc):
                raise
        return user

    manager = ensure_operator(
        f"manager@{DEMO_EMAIL_DOMAIN}",
        "Maya Tutorial Manager",
        "PROPERTY_MANAGER",
        os.environ.get("DEMO_MANAGER_PHONE", "+971500000006"),
    )
    guard = ensure_operator(
        f"guard@{DEMO_EMAIL_DOMAIN}",
        "Samir Tutorial Guard",
        "SECURITY_GUARD",
        os.environ.get("DEMO_GUARD_PHONE", "+971500000007"),
    )
    api.put(
        f"/api/v1/gatepass/guards/{guard['id']}/properties",
        json=[tower["id"]],
    )
    out["operatorLogins"] = [
        {"role": "PROPERTY_MANAGER", "email": manager["email"], "password": DEMO_PASSWORD},
        {"role": "SECURITY_GUARD", "email": guard["email"], "password": DEMO_PASSWORD},
    ]
    log("property manager and security guard accounts ready")

    resident_api = Api(base)
    resident_api.login(ahmed["email"], DEMO_PASSWORD)

    my_bookings = resident_api.get("/api/v1/bookings/my") or []
    amenity_booking = next(
        (b for b in my_bookings if b.get("amenityId") == amenity["id"]),
        None,
    )
    if not amenity_booking:
        amenity_booking = resident_api.post("/api/v1/bookings", json={
            "resourceType": "AMENITY",
            "resourceId": amenity["id"],
            "unitId": a101["id"],
            "preferredDate": iso(TODAY + dt.timedelta(days=7)),
            "note": "Tutorial family fitness session",
        })
        api.post(
            f"/api/v1/bookings/{amenity_booking['id']}/approve",
            json={"adminNote": "Approved tutorial booking"},
        )
    out["amenityBookingId"] = amenity_booking["id"]
    out["amenityId"] = amenity["id"]
    out["parkingSpotId"] = parking["id"]
    log("approved resident amenity booking ready")

    ticket_payload = resident_api.get("/api/v1/tickets") or []
    tickets = page_items(ticket_payload)
    ticket = next(
        (t for t in tickets if t.get("title") == "Tutorial: Leaking kitchen tap"),
        None,
    )
    if not ticket:
        ticket = resident_api.post("/api/v1/tickets", json={
            "propertyId": tower["id"],
            "unitId": a101["id"],
            "leaseId": lease_ahmed["id"],
            "title": "Tutorial: Leaking kitchen tap",
            "description": "The kitchen tap is leaking continuously. Synthetic tutorial request.",
            "category": "PLUMBING",
            "priority": "HIGH",
        })
        resident_api.post(
            f"/api/v1/tickets/{ticket['id']}/replies",
            json={"message": "Access is available after 10:00 AM."},
        )
    out["ticketId"] = ticket["id"]
    log("open maintenance ticket and renter reply ready")

    businesses = page_items(api.get("/api/v1/promotions/businesses?size=100"))
    business = next(
        (b for b in businesses if b.get("nameEn") == "Tutorial Community Cafe"),
        None,
    )
    if not business:
        business = api.post("/api/v1/promotions/businesses", json={
            "nameEn": "Tutorial Community Cafe",
            "nameAr": "مقهى المجتمع التجريبي",
            "category": "DINING",
            "phoneE164": "+971500000006",
            "whatsappE164": "+971500000006",
            "allowedDomains": ["example.com"],
            "active": True,
        })
    ads = page_items(
        api.get(f"/api/v1/promotions/ads?businessId={business['id']}&size=100")
    )
    promotion_ad = next(
        (a for a in ads if a.get("titleEn") == "20% off for Tutorial Residents"),
        None,
    )
    if not promotion_ad:
        promotion_ad = api.post("/api/v1/promotions/ads", json={
            "businessId": business["id"],
            "titleEn": "20% off for Tutorial Residents",
            "titleAr": "خصم 20٪ لسكان العرض التجريبي",
            "subtitleEn": "A synthetic resident offer for platform tutorials.",
            "accentColor": "#2563EB",
            "ctaType": "COUPON",
            "ctaLabelEn": "Copy code",
            "couponCode": "TUTORIAL20",
            "couponTermsEn": "Tutorial use only",
            "startsAt": (dt.datetime.now(dt.UTC) - dt.timedelta(hours=1)).isoformat(),
            "endsAt": (dt.datetime.now(dt.UTC) + dt.timedelta(days=30)).isoformat(),
            "priority": 8,
            "placement": "HOME_AND_OFFERS",
            "propertyIds": [tower["id"]],
            "active": True,
        })
    out["promotionBusinessId"] = business["id"]
    out["promotionAdId"] = promotion_ad["id"]
    log("tutorial business and active resident coupon ready")

    api.put(f"/api/v1/gatepass/policies?propertyId={tower['id']}", json={
        "requireUnregisteredApproval": True,
        "requireRegisteredApproval": False,
        "notifyRegisteredEntry": True,
        "requireFreshPhoto": True,
        "approvalTimeoutMinutes": 20,
    })
    visitor_phone = "+971500009991"
    try:
        api.post("/api/v1/gatepass/visitors/registration", json={
            "propertyId": tower["id"],
            "unitId": a101["id"],
            "name": "Tutorial Service Vendor",
            "phone": visitor_phone,
            "visitorType": "SERVICE_VENDOR",
            "validFrom": (dt.datetime.now(dt.UTC) - dt.timedelta(hours=1)).isoformat(),
            "validTo": (dt.datetime.now(dt.UTC) + dt.timedelta(days=30)).isoformat(),
            "active": True,
        })
    except RuntimeError as exc:
        if "409" not in str(exc):
            raise

    my_passes = resident_api.get("/api/v1/gatepass/mine") or []
    gate_pass = next(
        (p for p in my_passes if p.get("status") in ("ACTIVE", "PENDING_APPROVAL")),
        None,
    )
    if not gate_pass:
        gate_pass = resident_api.post("/api/v1/gatepass", json={
            "unitId": a101["id"],
            "guestName": "Tutorial Guest",
            "guestPhone": "+971500000005",
            "purpose": "Tutorial visitor access",
            "vehicleNumber": "TUTORIAL-01",
            "passType": "RECURRING",
            "validFrom": (dt.datetime.now(dt.UTC) - dt.timedelta(minutes=5)).isoformat(),
            "validTo": (dt.datetime.now(dt.UTC) + dt.timedelta(days=1)).isoformat(),
        })
    if gate_pass.get("status") == "PENDING_APPROVAL":
        gate_pass = api.post(
            f"/api/v1/gatepass/{gate_pass['id']}/approval",
            json={"approved": True},
        )
    out["gatePassId"] = gate_pass["id"]
    log("gate policy, registered visitor, and active resident pass ready")

    # ── Done ─────────────────────────────────────────────────────────────────
    OUT_FILE.write_text(json.dumps(out, indent=2))
    print(f"\nDone. Credentials and IDs written to {OUT_FILE}")
    if REDACT_CREDENTIALS:
        print("  Credentials redacted from console output.")
    else:
        print(f"  Tenant admin : {ADMIN_EMAIL} / {ADMIN_PASSWORD}")
        for r in out["renterLogins"]:
            print(f"  Renter       : {r['email']} / {r['password']}")


if __name__ == "__main__":
    main()
