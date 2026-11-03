#!/usr/bin/env python3
"""Tutorial 52 fixture: a promoter on Oasis Crest Properties, for the promoter developer API video.

LOCAL ONLY (the tutorial recording stack, backend :8084). Run it after `tutorials/recording-stack.sh seed`,
which writes the Oasis Crest manifest this script reads and extends:

    python3 tutorials/seed/seed_promoter_52.py
    tutorials/recording-stack.sh snapshot pre52     # every proof and capture of 52 starts from here

What it makes, through the app's own APIs, each step only when missing (idempotent, never deletes):
  - promo business "Glide Car Wash" (SERVICES) whose links may only go to offers.glidewash.example
  - a PROMOTER login, Rami Khalil <partners@glidewash.example>, given that business and two properties
    (Oasis Crest Residence Tower and Oasis Crest Marina Heights)
  - the promoter's coupon ad "Free exterior wash for residents" (code GLIDE-RES), redirect
    https://offers.glidewash.example/redeem with one parameter, code <- coupon code; reset to exactly that
    on every run, then approved by the Company Admin

The promoter's password is the manifest's local test password (the Company Admin's); it is never printed.
Manifest keys added: `promoterLogin` {email, password} and `promoter` {userId, name, email, businessId,
businessName, adId, adTitle, propertyIds}.
"""
from __future__ import annotations

import datetime as dt
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
# DataInitializer's local super admin, as recording-stack.sh seed uses it (localhost-only test login).
SA_EMAIL = os.environ.get("TUTORIAL_SUPERADMIN_EMAIL", "admin@rentaxis.com")
SA_PASSWORD = os.environ.get("TUTORIAL_SUPERADMIN_PASSWORD", "admin123")

BUSINESS_EN = "Glide Car Wash"
BUSINESS_AR = "غلايد لغسيل السيارات"
DOMAIN = "offers.glidewash.example"
PROMOTER_NAME = "Rami Khalil"
PROMOTER_EMAIL = "partners@glidewash.example"
AD_TITLE = "Free exterior wash for residents"
COUPON = "GLIDE-RES"
REDIRECT = f"https://{DOMAIN}/redeem"


def log(msg):
    print(f"  • {msg}", flush=True)


def require_local(url):
    u = urlparse(url)
    if u.hostname not in ("localhost", "127.0.0.1") or u.port in (8081, 8082, 8083):
        sys.exit(f"Refusing {url}: the tutorial 52 fixture runs against the local recording stack only.")


class Api:
    def __init__(self):
        self.s = requests.Session()
        self.h = {}

    def login(self, email, password):
        r = self.s.post(f"{API}/api/auth/login", json={"email": email, "password": password}, timeout=30)
        if r.status_code != 200:
            sys.exit(f"login failed for {email}: {r.status_code}")
        u = r.json()
        self.h = {"X-User-Id": str(u["id"]), "X-User-Role": u["role"]}
        if u.get("tenantId"):
            self.h["X-Tenant-Id"] = self.h["X-User-Tenant-Id"] = str(u["tenantId"])
        if u.get("token"):
            self.h["Authorization"] = f"Bearer {u['token']}"
        return u

    def call(self, method, path, headers=None, **kw):
        r = self.s.request(method, f"{API}{path}", headers={**self.h, **(headers or {})}, timeout=60, **kw)
        if r.status_code >= 300:
            raise RuntimeError(f"{method} {path} -> {r.status_code}: {r.text[:400]}")
        return r.json() if r.text else None


def items(payload):
    return payload if isinstance(payload, list) else (payload or {}).get("content", [])


def main():
    require_local(API)
    seed = json.loads(MANIFEST.read_text())
    tenant_id = seed["tenant"]["id"]
    tower, marina = seed["properties"]["tower"], seed["properties"]["marina"]
    password = seed["adminLogin"]["password"]

    admin = Api()
    admin.login(seed["adminLogin"]["email"], password)

    business = next((b for b in items(admin.call("GET", "/api/v1/promotions/businesses?size=100"))
                     if b.get("nameEn") == BUSINESS_EN), None)
    if not business:
        business = admin.call("POST", "/api/v1/promotions/businesses", json={
            "nameEn": BUSINESS_EN, "nameAr": BUSINESS_AR, "category": "SERVICES",
            "allowedDomains": [DOMAIN], "active": True})
    log(f"business {BUSINESS_EN} ready")

    promoter = next((p for p in admin.call("GET", "/api/v1/promotions/promoters") or []
                     if p.get("email") == PROMOTER_EMAIL), None)
    access = {"canCreateBusinesses": False, "businessIds": [business["id"]], "propertyIds": [tower, marina]}
    if not promoter:
        promoter = admin.call("POST", "/api/v1/promotions/promoters",
                              json={"name": PROMOTER_NAME, "email": PROMOTER_EMAIL, **access})
    else:
        promoter = admin.call("PUT", f"/api/v1/promotions/promoters/{promoter['userId']}", json=access)
    user_id = promoter.get("userId") or promoter.get("id")
    log("promoter login ready, two properties shared")

    # The invite leaves the login without a password (set-password email); give it the local test one.
    sa = Api()
    sa.login(SA_EMAIL, SA_PASSWORD)
    sa.call("PUT", f"/api/admin/users/{user_id}", json={
        "email": PROMOTER_EMAIL, "password": password, "name": PROMOTER_NAME,
        "role": "PROMOTER", "tenantId": tenant_id})

    me = Api()
    me.login(PROMOTER_EMAIL, password)
    ad_body = {
        "businessId": business["id"],
        "titleEn": AD_TITLE,
        "subtitleEn": "Show the code at any Glide bay. Valid on weekdays.",
        "accentColor": "#0E7490",
        "ctaType": "COUPON",
        "ctaLabelEn": "Copy code",
        "couponCode": COUPON,
        "couponTermsEn": "One wash per unit per month.",
        "startsAt": (dt.datetime.now(dt.UTC) - dt.timedelta(hours=1)).isoformat(),
        "endsAt": (dt.datetime.now(dt.UTC) + dt.timedelta(days=60)).isoformat(),
        "placement": "HOME_AND_OFFERS",
        "propertyIds": [tower, marina],
        "active": True,
        "redirectUrl": REDIRECT,
        "redirectParams": [{"name": "code", "source": "COUPON_CODE"}],
    }
    ad = next((a for a in items(me.call("GET", "/api/v1/promoter/ads?size=100")) if a.get("titleEn") == AD_TITLE), None)
    ad = me.call("PUT", f"/api/v1/promoter/ads/{ad['id']}", json=ad_body) if ad \
        else me.call("POST", "/api/v1/promoter/ads", json=ad_body)
    if ad.get("approvalStatus") != "APPROVED":
        admin.call("POST", f"/api/v1/promotions/ads/{ad['id']}/approve",
                   json={"expectedSubmittedAt": ad.get("submittedAt")})
    log("coupon ad ready (redirect: code only), approved")

    keys = me.call("GET", "/api/v1/promoter/api-keys") or []
    if keys:
        log(f"WARNING: the promoter already has {len(keys)} API key(s); restore an older snapshot "
            "before `snapshot pre52` so the Developer tab starts empty")

    seed["promoterLogin"] = {"email": PROMOTER_EMAIL, "password": password}
    seed["promoter"] = {
        "userId": user_id, "name": PROMOTER_NAME, "email": PROMOTER_EMAIL,
        "businessId": business["id"], "businessName": BUSINESS_EN,
        "adId": ad["id"], "adTitle": AD_TITLE, "couponCode": COUPON, "redirectUrl": REDIRECT,
        "propertyIds": [tower, marina],
    }
    MANIFEST.write_text(json.dumps(seed, indent=2))
    log(f"manifest updated: {MANIFEST.relative_to(REPO)} (promoterLogin, promoter)")


if __name__ == "__main__":
    main()
