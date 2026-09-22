#!/usr/bin/env python3
"""Seed a named demo tenant with full demo data.

Repeatable, API-driven (no SQL). Exercises the same endpoints the web and
mobile apps use, so it doubles as a smoke test of the prod API.

What it creates:
  - Named demo tenant + TENANT_ADMIN login
  - Features enabled: LISTINGS, MEETINGS, LEASE_RENEWALS
  - Chart of accounts + charge-type catalogue + per-property account sets
    (generated from the tenant template) + the fiscal year opened on 1 January
    and everything before it locked
  - 2 properties with rent-collection settings, 8 units
  - 4 renters with portal (RENTER) logins
  - 4 tenancy contracts, each with its own cheque grid, covering the register:
      * quarterly, 3 cleared + 1 deposited + 1 still registered
      * quarterly, one cheque returned by the bank and replaced by two
      * monthly, 5 cleared and the next instalment overdue
      * one left in DRAFT with its grid, to post live in the demo
  - Month-end income recognition run to the end of last month (CIL journals)
  - 4 published marketplace listings + 1 draft (vacant units)
  - 2 vendors, one of them with a posted purchase invoice (PISR, 5% input VAT)
    and the payment voucher (BPV) that settles it
  - 2 meetings (cheque replacement + property viewing), one approved

Credentials & IDs are printed and written to scripts/seed_demo_tenant.out.json.

Usage:
  python3 scripts/seed_demo_tenant.py                  # uses web/e2e-prod/.env.local
  PROD_BASE_URL=http://localhost:8080 \
  PROD_SUPERADMIN_EMAIL=... PROD_SUPERADMIN_PASSWORD=... \
  python3 scripts/seed_demo_tenant.py

  # Split local stack (no Caddy in front): the API and the web app are two
  # different origins, and the marketplace-listing step needs the web one.
  PROD_BASE_URL=http://localhost:8081 DEMO_WEB_BASE_URL=http://localhost:3001 \
  PROD_SUPERADMIN_EMAIL=... PROD_SUPERADMIN_PASSWORD=... \
  python3 scripts/seed_demo_tenant.py
"""
import json
import os
import re
import sys
import datetime as dt
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

import requests

REPO_ROOT = Path(__file__).resolve().parent.parent
TENANT_NAME = os.environ.get("DEMO_TENANT_NAME", "Miftah Demo Account")
DEMO_BRAND = os.environ.get("DEMO_BRAND", "Miftah Demo")
DEMO_EMAIL_DOMAIN = os.environ.get("DEMO_EMAIL_DOMAIN", "miftahdemo.example")
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

    def __init__(self, base_url, web_base_url=None):
        self.base = base_url.rstrip("/")
        # On a deployed stack Caddy fronts the API and the web app on one
        # origin, so these are the same. A local split stack (backend on 8081,
        # `next dev` on 3001) has no such front door: /api/v1 and /api/admin
        # only exist on the backend, and /api/auth/* and /api/proxy/* only on
        # the web app. DEMO_WEB_BASE_URL is what tells the two apart.
        self.web_base = (web_base_url or base_url).rstrip("/")
        self.s = requests.Session()
        self.headers = {}

    def origin_for(self, path):
        return self.web_base if path.startswith("/api/proxy") else self.base

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
        csrf = self.s.get(f"{self.web_base}/api/auth/csrf", timeout=30).json()[
            "csrfToken"
        ]
        r = self.s.post(
            f"{self.web_base}/api/auth/callback/credentials",
            data={
                "csrfToken": csrf,
                "email": email,
                "password": password,
                "callbackUrl": f"{self.web_base}/",
                "json": "true",
            },
            timeout=30,
            allow_redirects=False,
        )
        if r.status_code >= 400:
            raise RuntimeError(f"NextAuth login failed ({r.status_code})")
        sess = self.s.get(f"{self.web_base}/api/auth/session", timeout=30).json()
        if not (sess or {}).get("user"):
            raise RuntimeError("NextAuth session has no user after login")

    def call(self, method, path, expect=(200, 201, 204), **kw):
        headers = kw.pop("headers", self.headers)
        r = self.s.request(
            method, f"{self.origin_for(path)}{path}", headers=headers, timeout=60, **kw
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


def page_items(payload):
    """The rows of a response that may be a bare list or a Spring Page.
    Module level because both the finance steps (section 5b) and the
    operational fixtures (section 8) page through collections."""
    if isinstance(payload, list):
        return payload
    if isinstance(payload, dict) and isinstance(payload.get("content"), list):
        return payload["content"]
    return []


def main():
    load_env()
    base = os.environ.get(
        "PROD_BASE_URL", "https://rentaxis.uaenorth.cloudapp.azure.com"
    )
    # Same origin unless a split local stack says otherwise (see Api.__init__).
    web_base = os.environ.get("DEMO_WEB_BASE_URL", base)
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
    sa = Api(base, web_base)
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
    # Mobile finance/lease/cheque screens stay hidden until the apps are rewritten
    # for accounting v2. Set explicitly rather than relying on the default, because
    # this tenant is also the App Store reviewer's tenant and the flag is the one
    # thing that decides what the reviewer sees.
    sa.put(
        f"/api/admin/tenants/{tenant_id}/features/MOBILE_FINANCE",
        json={"enabled": False},
    )
    log("feature MOBILE_FINANCE explicitly off (mobile finance screens hidden)")

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
    api = Api(base, web_base)
    admin_user = api.login(ADMIN_EMAIL, ADMIN_PASSWORD)
    admin_user_id = admin_user["id"]
    out["adminUserId"] = admin_user_id

    # Chart of accounts + the per-property account template + the tenant-level
    # role defaults + the charge-type catalogue: one call since accounting v2
    # (AccountController.seedDefaultAccounts). Each of its three parts is
    # idempotent server-side, so this is a no-op on an established tenant.
    api.post("/api/v1/finance/accounts/seed")
    log("chart of accounts, property template, role defaults and charge types seeded")

    # Open the books on 1 January of the demo year and close everything before
    # it, so back-dated demo documents post and nothing can be written into last
    # year. The period lock is its own endpoint and only ever moves forward
    # (TenantFiscalSettingsService.lockThrough), so both steps are read first.
    books_start = dt.date(TODAY.year, 1, 1)
    locked_through = books_start - dt.timedelta(days=1)
    fiscal = api.get("/api/v1/finance/fiscal-settings") or {}
    if (fiscal.get("booksStartDate") != iso(books_start)
            or fiscal.get("fiscalYearStartMonth") != 1):
        # Setting the books start date also sets the lock to the day before it
        # when no lock exists yet — the POST below is then a no-op re-assertion.
        fiscal = api.put(
            "/api/v1/finance/fiscal-settings",
            json={"fiscalYearStartMonth": 1, "booksStartDate": iso(books_start)},
        )
    if (fiscal.get("booksLockedThrough") or "") < iso(locked_through):
        fiscal = api.post(
            "/api/v1/finance/fiscal-settings/lock",
            json={"through": iso(locked_through)},
        )
    out["fiscal"] = {
        "fiscalYearStartMonth": fiscal.get("fiscalYearStartMonth"),
        "booksStartDate": fiscal.get("booksStartDate"),
        "booksLockedThrough": fiscal.get("booksLockedThrough"),
    }
    log(f"books open {fiscal.get('booksStartDate')}, "
        f"locked through {fiscal.get('booksLockedThrough')}")

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

    # Creating a property already generates its account set from the tenant
    # template (PropertyService.createProperty → generateMissing); *generate*
    # here fills any gap left by a property that existed before the template
    # did, and is idempotent — it skips every role already mapped.
    REQUIRED_ROLES = ("RENT_RECEIVABLE", "ADVANCE_RENT", "RENTAL_INCOME",
                      "PDC_RECEIVABLE", "BANK", "SECURITY_DEPOSIT", "ADMIN_FEE")

    def property_accounts(prop):
        """Role -> account id for one property. A row with no accountId is a
        property-scoped role the template does not cover; the lease-critical
        seven are fatal, the rest are reported and left alone."""
        api.post(f"/api/v1/properties/{prop['id']}/accounts/generate")
        rows = api.get(f"/api/v1/properties/{prop['id']}/accounts") or []
        mapped = {r["role"]: r["accountId"] for r in rows if r.get("accountId")}
        missing = [r["role"] for r in rows if not r.get("accountId")]
        for role in REQUIRED_ROLES:
            if role not in mapped:
                raise RuntimeError(
                    f"{prop['nameEn']}: role {role} is unmapped, a lease on it "
                    f"cannot post. Unmapped roles: {missing}"
                )
        if missing:
            log(f"  note: {prop['nameEn']} has no account for {', '.join(missing)}")
        return mapped

    out["accounts"] = {
        "tower": property_accounts(tower),
        "marina": property_accounts(marina),
    }
    log(f"property account sets ready "
        f"({len(out['accounts']['tower'])} roles on the tower, "
        f"{len(out['accounts']['marina'])} on the marina)")

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

    out["renterIds"] = {
        "ahmed": ahmed["id"], "fatima": fatima["id"],
        "rajesh": rajesh["id"], "sara": sara["id"],
    }

    # ── 4. Leases as posting documents ───────────────────────────────────────
    # A v2 lease is a document, not a settings form: `lines` say what is charged,
    # an explicit cheque grid says how it is collected, and `post` writes the TCO
    # and one PDR per row. `POST /api/v1/leases` ignores a `cheques` key in
    # silence (CreateLeaseDTO has no such field), so the grid is its own call.
    year_start = dt.date(TODAY.year, 1, 1)
    year_end = dt.date(TODAY.year, 12, 31)
    # Signed ten days before the tenancy starts, but *dated* the first of the
    # year: the TCO carries the contract date and every cheque's posting date
    # defaults to it, and the fiscal step above locks everything before 1 Jan.
    agreement_date = year_start - dt.timedelta(days=10)
    contract_date = year_start

    existing_leases = {
        l.get("unitId"): l for l in (api.get("/api/v1/leases") or [])
    }

    # A deposit is never taxed, whatever its charge type's flag says (LeaseVat),
    # so it is excluded from the check below rather than trusted to be flagged.
    DEPOSIT_CODES = {"SECURITY_DEPOSIT", "PARKING_DEPOSIT"}
    VAT_RATE = 0.05

    def line(code, gross, narration="", discount=0.0, vat=False):
        """One charged particular. `vat` is spelled out on every line rather than
        left to the charge type's default: all seven seeded types default to
        false today, and a flip of that default must not silently unbalance a
        grid the demo depends on."""
        return {"chargeTypeCode": code, "grossAmount": float(gross),
                "discountAmount": float(discount), "narration": narration,
                "vatApplicable": vat}

    def contract_value(lines):
        """Σ (net + VAT) — the figure `post` compares the cheque grid against
        (LeasePostingService.validate: "Cheque grid totals X but contract value
        incl. VAT is Y"), computed the way the server computes it: VAT rounded
        per line, deposits untaxed."""
        total = 0.0
        for l in lines:
            net = l["grossAmount"] - l["discountAmount"]
            taxed = l["vatApplicable"] and l["chargeTypeCode"] not in DEPOSIT_CODES
            # HALF_UP like the server's VoucherMath/LeaseVat — Python's round()
            # is half-to-even and would drift on a .xx5 line.
            vat = float(Decimal(str(net * VAT_RATE)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)) if taxed else 0.0
            total += net + vat
        return total

    def cheque(seq, number, date, amount, narration, bank="Emirates NBD",
               mode="PDC", posting_date=None):
        """One row of the grid. `seqNo` is accepted and ignored by the server —
        position is the order of the list — but it is sent anyway because it is
        what the row is called in the log and in the manifest."""
        return {"seqNo": seq, "postingDate": iso(posting_date or contract_date),
                "chequeNumber": number, "chequeDate": iso(date),
                "payeeBank": bank, "amount": float(amount),
                "narration": narration, "mode": mode}

    def make_lease(unit, renter, contract_ref, lines, cheques, terms, post=True):
        """Create-or-fetch a DRAFT lease, give it its grid, then post it.

        Every half is idempotent: a lease already on the unit is returned as it
        stands, a grid is only written when the lease has none, and a lease that
        is already ACTIVE is never posted twice.

        `contract_ref` is not sent anywhere — `contractNumber` is the server's
        own per-tenant sequence and `externalContractRef` is written only by the
        cut-over import. It names the contract in the arithmetic check below and
        in the cheque narrations, which is where a human reads it.
        """
        lease = existing_leases.get(unit["id"])
        if not lease:
            # Checked here as well as by the server: a grid that does not add up
            # is refused at *post* time, which on a fresh tenant means a lease
            # and a grid are already written and the run dies three calls later.
            value, collected = contract_value(lines), sum(c["amount"] for c in cheques)
            if abs(value - collected) > 0.005:
                raise RuntimeError(
                    f"{contract_ref}: cheque grid {collected:,.2f} does not equal "
                    f"contract value {value:,.2f} — the post would be rejected"
                )
            lease = api.post("/api/v1/leases", json={
                "unitId": unit["id"],
                "renterId": renter["id"],
                "startDate": iso(year_start),
                "endDate": iso(year_end),
                "agreementDate": iso(agreement_date),
                "contractDate": iso(contract_date),
                "gracePeriodDays": 5,
                "paymentTerms": terms,
                "paymentMethod": "CHEQUE",
                "depositPaymentMethod": "CHEQUE",
                "rentVatApplicable": False,
                "lines": lines,
            })
        if lease.get("status") == "DRAFT" and not api.get(
                f"/api/v1/leases/{lease['id']}/cheques"):
            api.put(f"/api/v1/leases/{lease['id']}/cheques", json=cheques)
        if post and lease.get("status") in ("DRAFT", "PENDING_SIGNATURE"):
            # PostLeaseResponse — {lease, tcoJournalId, tcoEntryNumber, cheques}.
            posted = api.post(f"/api/v1/leases/{lease['id']}/post")
            lease = posted["lease"]
            log(f"{contract_ref} posted: TCO {posted.get('tcoEntryNumber')}, "
                f"{len(posted.get('cheques') or [])} instruments registered")
        return lease

    # Ahmed — quarterly rent, the admin fee folded into the first rent cheque,
    # the deposit on its own row. 108,500 = 22,000 + 22,750 + 3 × 21,250.
    lease_ahmed = make_lease(
        a101, ahmed, "ART/1001",
        [line("SECURITY_DEPOSIT", 22000), line("RENT", 85000),
         line("ADMIN_FEE", 1500, "Contract administration")],
        [cheque(1, "200100", year_start, 22000, "Security Deposit"),
         cheque(2, "200101", year_start, 22750, "Rent - 1st Installment"),
         cheque(3, "200102", dt.date(TODAY.year, 4, 1), 21250, "Rent - 2nd Installment"),
         cheque(4, "200103", dt.date(TODAY.year, 7, 1), 21250, "Rent - 3rd Installment"),
         cheque(5, "200104", dt.date(TODAY.year, 10, 1), 21250, "Rent - 4th Installment")],
        terms=4,
    )

    # Fatima — quarterly; her second rent cheque comes back and is replaced by
    # two smaller ones further down. 78,000 = 16,000 + 4 × 15,500.
    lease_fatima = make_lease(
        a102, fatima, "ART/1002",
        [line("SECURITY_DEPOSIT", 16000), line("RENT", 62000)],
        [cheque(1, "300200", year_start, 16000, "Security Deposit", bank="FAB"),
         cheque(2, "300201", year_start, 15500, "Rent - 1st Installment", bank="FAB"),
         cheque(3, "300202", dt.date(TODAY.year, 4, 1), 15500, "Rent - 2nd Installment", bank="FAB"),
         cheque(4, "300203", dt.date(TODAY.year, 7, 1), 15500, "Rent - 3rd Installment", bank="FAB"),
         cheque(5, "300204", dt.date(TODAY.year, 10, 1), 15500, "Rent - 4th Installment", bank="FAB")],
        terms=4,
    )

    # Rajesh — monthly, with the year's parking folded into January.
    # 135,000 = 12,000 + 13,000 + 11 × 10,000.
    ORDINALS = {1: "1st", 2: "2nd", 3: "3rd"}
    rajesh_cheques = [cheque(1, "400300", year_start, 12000, "Security Deposit",
                             bank="Dubai Islamic Bank")]
    for m in range(1, 13):
        rajesh_cheques.append(cheque(
            m + 1, f"4003{m:02d}", dt.date(TODAY.year, m, 1),
            13000 if m == 1 else 10000,
            f"Rent - {ORDINALS.get(m, f'{m}th')} Installment",
            bank="Dubai Islamic Bank"))
    lease_rajesh = make_lease(
        a103, rajesh, "ART/1003",
        [line("SECURITY_DEPOSIT", 12000), line("RENT", 120000),
         line("PARKING_FEE", 3000, "Annual parking — bay B2-18")],
        rajesh_cheques, terms=12,
    )

    # Sara — left in DRAFT on purpose, grid and all. Posting it is the one
    # moment in the demo where the audience watches TCO and PDR journals appear.
    # 140,000 = 30,000 + 4 × 27,500.
    lease_sara = make_lease(
        m1501, sara, "MH/2001",
        [line("SECURITY_DEPOSIT", 30000), line("RENT", 110000)],
        [cheque(1, "500400", year_start, 30000, "Security Deposit"),
         cheque(2, "500401", year_start, 27500, "Rent - 1st Installment"),
         cheque(3, "500402", dt.date(TODAY.year, 4, 1), 27500, "Rent - 2nd Installment"),
         cheque(4, "500403", dt.date(TODAY.year, 7, 1), 27500, "Rent - 3rd Installment"),
         cheque(5, "500404", dt.date(TODAY.year, 10, 1), 27500, "Rent - 4th Installment")],
        terms=4, post=False,
    )
    log("3 posted contracts (quarterly, quarterly, monthly) + 1 draft to post live")

    out["leases"] = {
        "ahmed": lease_ahmed["id"],
        "fatima": lease_fatima["id"],
        "rajesh": lease_rajesh["id"],
        "sara": lease_sara["id"],
    }
    out["leaseStatus"] = {
        key: api.get(f"/api/v1/leases/{lease_id}")["status"]
        for key, lease_id in out["leases"].items()
    }

    # ── 5. Cheque lifecycle on the PDC register ──────────────────────────────
    # Posting registered every row; the register walks them on from there
    # (PUT /api/v1/cheques/{id}/deposit|clear|bounce, each with a {date} body).
    RANK = {"DRAFT": 0, "REGISTERED": 1, "DEPOSITED": 2, "CLEARED": 3, "BOUNCED": 3,
            "REPLACED": 4}
    # Statuses this script must never try to move: a row that is out of the
    # normal walk entirely. Left out of RANK on purpose — `RANK.get(s, 0)` would
    # read them as "not started yet" and try to deposit a cancelled cheque.
    OFF_THE_WALK = {"CANCELLED", "RETURNED", "ONLINE_PENDING"}

    def rows_for(lease_id):
        rows = api.get(f"/api/v1/leases/{lease_id}/cheques") or []
        return sorted(rows, key=lambda r: r["seqNo"])

    def value_date(cheque_date, days_after):
        """Banked the day after, settled a few days later — but never in the
        future: a demo ledger dated next month is not a demo of anything."""
        d = dt.date.fromisoformat(cheque_date) + dt.timedelta(days=days_after)
        return iso(min(d, TODAY))

    def advance(row, target):
        """Walk one register row REGISTERED → DEPOSITED → CLEARED/BOUNCED,
        skipping what has already happened. Safe to re-run."""
        status = row.get("status") or "REGISTERED"
        if status in OFF_THE_WALK or RANK.get(status, 0) >= RANK[target]:
            return row
        cid = row["id"]
        if status == "REGISTERED" and RANK[target] >= RANK["DEPOSITED"]:
            row = api.put(f"/api/v1/cheques/{cid}/deposit",
                          json={"date": value_date(row["chequeDate"], 1)})
            status = row["status"]
        if status == "DEPOSITED" and target == "CLEARED":
            row = api.put(f"/api/v1/cheques/{cid}/clear",
                          json={"date": value_date(row["chequeDate"], 4)})
        if status == "DEPOSITED" and target == "BOUNCED":
            row = api.put(f"/api/v1/cheques/{cid}/bounce",
                          json={"date": value_date(row["chequeDate"], 5),
                                "failureReason": "BOUNCE",
                                "notes": "Insufficient funds"})
        return row

    def tally(rows):
        counts = {}
        for r in rows:
            counts[r["status"]] = counts.get(r["status"], 0) + 1
        return ", ".join(f"{n} {s.lower()}" for s, n in sorted(counts.items()))

    # Ahmed: deposit and the first two rent cheques cleared, Q3 banked and
    # waiting on the bank, Q4 still sitting in the register.
    rows = rows_for(lease_ahmed["id"])
    for r in rows[:3]:
        advance(r, "CLEARED")
    advance(rows[3], "DEPOSITED")
    log(f"Ahmed: {tally(rows_for(lease_ahmed['id']))}")

    # Fatima: deposit and Q1 cleared; Q2 returned by the bank and replaced by
    # two smaller cheques, each registering its own PDR. One bounce does not
    # cross the org's auto-propose threshold (2 — FineSettingsInitializer), so
    # the penalty is proposed by hand from the Penalties tab in the live demo.
    rows = rows_for(lease_fatima["id"])
    for r in rows[:2]:
        advance(r, "CLEARED")
    bounced = advance(rows[2], "BOUNCED")
    bounced_payment_id = bounced["id"]
    if bounced["status"] == "BOUNCED" and not bounced.get("replacedById"):
        replacement_date = dt.date(TODAY.year, 4, 20)
        api.post(f"/api/v1/cheques/{bounced['id']}/replace", json={
            "date": iso(replacement_date),
            "notes": "Returned unpaid; renter re-papered it in two instruments",
            "replacements": [
                cheque(90, "300290", dt.date(TODAY.year, 5, 1), 10000,
                       "Replacement 1 of 2 — returned cheque 300202", bank="FAB",
                       posting_date=replacement_date),
                cheque(91, "300291", dt.date(TODAY.year, 6, 1), 5500,
                       "Replacement 2 of 2 — returned cheque 300202", bank="FAB",
                       posting_date=replacement_date),
            ],
        })
        log("Fatima: Q2 cheque returned and replaced by two rows")
    log(f"Fatima: {tally(rows_for(lease_fatima['id']))}")

    # Rajesh: deposit and five months cleared; the sixth is past its date and
    # unpaid, so the register shows it as overdue.
    rows = rows_for(lease_rajesh["id"])
    for r in rows[:6]:
        advance(r, "CLEARED")
    log(f"Rajesh: {tally(rows_for(lease_rajesh['id']))}, the next one overdue")

    out["cheques"] = {
        key: [{"id": r["id"], "seqNo": r["seqNo"], "status": r["status"]}
              for r in rows_for(lease_id)]
        for key, lease_id in out["leases"].items()
    }

    # ── 5b. Vendors, a purchase invoice and the payment that settles it ──────
    # Ahead of the listings section on purpose: everything below this point
    # needs the Next.js app (NextAuth session for /api/proxy/*), and the books
    # should not depend on the web app being up.
    #
    # Creating a vendor also creates its payable leaf under B-01-04 Vendors
    # (accounting v2 plan 1), so the demo tenant gets a usable payables side of
    # the chart with no extra step.
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
    for v in (fm_vendor, cleaning_vendor):
        log(f"vendor ready: {v.get('nameEn')}")

    def vendor_payable_id(vendor):
        """The vendor's ledger leaf, created silently under B-01-04 when the
        vendor is saved — but skipped in silence if the chart of accounts was
        not seeded first, which is why this asks rather than assumes."""
        vendor = api.get(f"/api/v1/vendors/{vendor['id']}")
        acc = (vendor.get("payableAccount") or {}).get("id")
        if not acc:
            raise RuntimeError(
                f"vendor {vendor.get('nameEn')} has no payable account; "
                f"VendorService creates one under B-01-04 on save, but skips it "
                f"when the chart of accounts is missing — re-run the seed step"
            )
        return acc

    # The v1 split-expense demo posted to `/finance/transactions`, removed with
    # `financial_transactions` in plan 1. Its replacement is a Purchase/Service
    # Invoice and a Bank/Cash Payment Voucher (spec §10.1/§10.2), built in plan 4.
    chart = {a["code"]: a for a in (api.get("/api/v1/finance/accounts") or [])
             if isinstance(a, dict) and a.get("code")}

    def expense_leaf(code, name_en, name_ar, parent_code):
        """Get-or-create a leaf under a seeded expense group. D-01 Direct
        Expense ships as an empty group ("one leaf per property per category"),
        so the demo tenant has to put its own categories in it."""
        if code in chart:
            return chart[code]
        parent = chart.get(parent_code)
        if not parent:
            raise RuntimeError(f"expense group {parent_code} is missing from the chart")
        leaf = api.post("/api/v1/finance/accounts", json={
            "code": code,
            "nameEn": name_en,
            "nameAr": name_ar,
            "accountType": parent["accountType"],
            "accountSubType": parent["accountSubType"],
            "parentId": parent["id"],
            "group": False,
        })
        chart[code] = leaf
        log(f"expense account created: {code} {name_en}")
        return leaf

    maintenance_expense = expense_leaf(
        "D-01-001", "Building Maintenance & AMC", "صيانة المباني والعقود السنوية",
        "D-01",
    )

    existing_vouchers = {
        v.get("narration"): v
        for v in page_items(api.get("/api/v1/finance/vouchers", params={"size": 200}))
        if isinstance(v, dict) and v.get("narration")
    }

    def make_voucher(narration, body):
        """Create-and-post a voucher, keyed on its narration so a re-run is a
        no-op. A draft left behind by a half-finished run is posted, not
        duplicated."""
        voucher = existing_vouchers.get(narration)
        if voucher is None:
            voucher = api.post("/api/v1/finance/vouchers",
                               json={**body, "narration": narration})
        elif voucher.get("status") != "DRAFT":
            return voucher
        posted = api.post(f"/api/v1/finance/vouchers/{voucher['id']}/post")
        existing_vouchers[narration] = posted
        log(f"voucher posted: {posted.get('voucherNumber')} — {narration}")
        return posted

    # Purchase / Service Invoice — Dr the expense + Dr input VAT, Cr the vendor's
    # payable leaf with the gross (spec §10.1). 5% VAT, added on top of the line.
    invoice = make_voucher(
        f"Fire safety AMC — {tower['nameEn']}",
        {
            "docType": "PISR",
            "docDate": iso(dt.date(TODAY.year, 2, 10)),
            "vendorId": fm_vendor["id"],
            "invoiceNumber": f"EFM-{TODAY.year}-0114",
            "propertyId": tower["id"],
            # The per-line VAT amount is derived on the server from vatRate.
            "lines": [{
                "accountId": maintenance_expense["id"],
                "description": "Annual fire safety maintenance contract",
                "amount": 9000.0,
                "vatRate": 5.0,
                "propertyId": tower["id"],
            }],
        },
    )

    # Bank / Cash Payment Voucher — Dr the vendor's payable, Cr the tower's bank
    # leaf (spec §10.2). The header names the vendor because the line is that
    # vendor's payable account, which VoucherService insists on matching.
    payment = make_voucher(
        "Payment — fire safety AMC",
        {
            "docType": "BPV",
            "docDate": iso(dt.date(TODAY.year, 3, 5)),
            "vendorId": fm_vendor["id"],
            "propertyId": tower["id"],
            "paymentAccountId": out["accounts"]["tower"]["BANK"],
            "chequeNumber": "700001",
            "chequeDate": iso(dt.date(TODAY.year, 3, 5)),
            "lines": [{
                "accountId": vendor_payable_id(fm_vendor),
                "description": "Fire safety AMC — invoice settled in full",
                "amount": 9450.0,
            }],
        },
    )
    out["vouchers"] = {
        "purchaseInvoice": invoice["id"],
        "purchaseInvoiceNumber": invoice.get("voucherNumber"),
        "paymentVoucher": payment["id"],
        "paymentVoucherNumber": payment.get("voucherNumber"),
        "vendorId": fm_vendor["id"],
    }

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
                        f"{api.web_base}{LISTINGS}/{lid}/media",
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
                        f"{api.web_base}{LISTINGS}/{lid}/media",
                        files={"file": (os.path.basename(path), fh, "image/jpeg")},
                        data={"isCover": "true" if i == 0 else "false",
                              "caption": "Exterior view" if i == 0 else "Living area"},
                        timeout=120,
                    )
            log(f"media uploaded (fallback placeholders): {title}")

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

    # ── Month-end recognition ────────────────────────────────────────────────
    # Accounting v2 plan 3 recognises rent PER DAY, one CIL per calendar month,
    # and only for periods that have already ENDED. A freshly seeded tenant is
    # therefore all schedule and no income: every recognition entry is PLANNED,
    # Rental Income is zero and the income reports the demo exists to show are
    # empty. Run the close to the end of last month — the same date the
    # month-end screen defaults to — so the seeded books look like a landlord's
    # in the middle of a year rather than one on their first day.
    last_month_end = TODAY.replace(day=1) - dt.timedelta(days=1)
    recognition = api.post(
        f"/api/v1/finance/recognition/run?to={iso(last_month_end)}&preview=false"
    ) or {}
    # The date the books are closed to. Its own key as well as the block below,
    # because that is the one fact the walkthroughs and the tutorials read.
    out["recognitionRunTo"] = iso(last_month_end)
    out["recognition"] = {
        "to": iso(last_month_end),
        "posted": recognition.get("posted", 0),
        "amount": recognition.get("amount", 0),
        "skippedLocked": recognition.get("skippedLocked", 0),
        "failed": recognition.get("failed", 0),
    }
    log(
        f"recognition run to {iso(last_month_end)}: "
        f"{recognition.get('posted', 0)} entries posted, "
        f"{recognition.get('amount', 0)} recognised"
    )
    if recognition.get("failed"):
        # Not fatal — the rest of the demo data is still usable — but a silent
        # partial close is how a demo ends up with books that do not add up.
        log(f"  WARNING: {recognition['failed']} entries the ledger refused: {recognition.get('errors')}")

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
