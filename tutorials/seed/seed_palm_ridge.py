#!/usr/bin/env python3
"""Seed "Palm Ridge Properties", the fictional organisation the accounting tutorials record on.

LOCAL ONLY. Written for the tutorial recording stack (tutorials/recording-stack.sh, backend :8084).
It never reads the production credentials file and has no default URL: both the API URL and the
super-admin credentials must be given explicitly, and the URL must be on this machine and not on a
port another local stack owns (see palm_ridge_plan.require_local_url).

    PALM_RIDGE_API_URL=http://localhost:8084 \
    PALM_RIDGE_SUPERADMIN_EMAIL=... PALM_RIDGE_SUPERADMIN_PASSWORD=... \
    PALM_RIDGE_USER_PASSWORD=... \
    python3 tutorials/seed/seed_palm_ridge.py [--out tutorials/work/seed/palm-ridge.out.json] [--plan]

`--plan` prints what would be created and exits without any network call.

What it builds (the plan is palm_ridge_plan.py; tutorial mapping in .superpowers/accounting-tutorials.md):
  - the organisation (fictional address, phone, TRN, logo and stamp from tutorials/brand/palm-ridge/),
    a Company Admin and an Accountant
  - books starting 1 Sep 2025 with opening balances (two bank accounts, one supplier invoice open at
    cut-over, capital), two properties and eleven units, eleven tenants, eleven tenancy contracts
  - a year of history replayed in date order so journal numbers run chronologically: cheques cleared,
    banked, returned and replaced, returned and settled in cash, written off and part recovered; a
    termination settled against the deposit and one with a deposit refund; a renewal with a 5 %
    increase; a rent-free fit-out month; VAT per instalment; penalties and a maintenance recharge;
    supplier bills, payment runs, post-dated supplier cheques presented and outstanding
  - month-end recognition and VAT tax points to 31 Aug 2026, VAT returns filed to Q2 2026,
    FY 2025 closed, the period lock at 30 Jun 2026
  - the August 2026 bank statement for the residences' bank account, written as a CSV for the
    bank-reconciliation tutorial to import (not imported here)

Idempotent: every step reads the server's state first and only does what is missing, so a re-run
after a failure resumes. It never deletes anything.
"""
from __future__ import annotations

import datetime as dt
import importlib.util
import json
import os
import pathlib
import sys
from decimal import Decimal
from zoneinfo import ZoneInfo

import requests

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent.parent
_spec = importlib.util.spec_from_file_location("palm_ridge_plan", HERE / "palm_ridge_plan.py")
P = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(P)

D = dt.date
TODAY = dt.datetime.now(ZoneInfo("Asia/Dubai")).date()
BRAND = REPO / "tutorials" / "brand" / "palm-ridge"


def iso(d):
    return d.isoformat() if d else None


def num(x):
    return float(Decimal(str(x)))


def rel(path):
    """A path relative to the repo when it is inside it (the manifest is committed nowhere, but stays readable)."""
    try:
        return str(pathlib.Path(path).resolve().relative_to(REPO))
    except ValueError:
        return str(path)


def log(msg):
    print(f"  • {msg}", flush=True)


def items(payload):
    if isinstance(payload, list):
        return payload
    if isinstance(payload, dict):
        for k in ("content", "items", "rows"):
            if isinstance(payload.get(k), list):
                return payload[k]
    return []


class Api:
    """Bearer token when the stack issues one, X-User-* headers otherwise (the stack's own model)."""

    def __init__(self, base):
        self.base = base
        self.s = requests.Session()
        self.headers = {}

    def login(self, email, password, tenant_id=None):
        body = {"email": email, "password": password}
        if tenant_id:
            body["tenantId"] = tenant_id
        r = self.s.post(f"{self.base}/api/auth/login", json=body, timeout=30)
        if r.status_code != 200:
            raise SystemExit(f"login failed for {email}: {r.status_code}")
        u = r.json()
        self.headers = {"X-User-Id": str(u["id"]), "X-User-Role": u["role"]}
        if u.get("tenantId"):
            self.headers["X-Tenant-Id"] = self.headers["X-User-Tenant-Id"] = str(u["tenantId"])
        if u.get("token"):
            self.headers["Authorization"] = f"Bearer {u['token']}"
        return u

    def call(self, method, path, body=None, params=None, expect=(200, 201, 204), **kw):
        r = self.s.request(method, f"{self.base}{path}", json=body, params=params, headers=self.headers,
                           timeout=300, **kw)
        if r.status_code not in expect:
            raise RuntimeError(f"{method} {path} -> {r.status_code}: {r.text[:600]}")
        if r.status_code == 204 or not r.text:
            return None
        try:
            return r.json()
        except ValueError:
            return r.text

    def get(self, path, params=None):
        return self.call("GET", path, params=params)

    def post(self, path, body=None, params=None):
        return self.call("POST", path, body, params)

    def put(self, path, body=None, params=None):
        return self.call("PUT", path, body, params)


class Seed:
    def __init__(self, api_url, out_file):
        self.url = api_url
        self.out_file = out_file
        self.out = {"org": P.ORG_NAME, "apiUrl": api_url, "seededAt": dt.datetime.now().isoformat()}
        self.sa = Api(api_url)
        self.api = Api(api_url)            # Company Admin: every finance action below is allowed to that role
        self.props, self.units, self.renters, self.leases = {}, {}, {}, {}
        self.roles = {}                    # property key -> {role: accountId}
        self.bank_accounts = {}
        self._pending = []
        self.vendors, self.expense = {}, {}
        self.defaults = {}                 # tenant-level role -> accountId

    # ── organisation, users, branding ───────────────────────────────────────────
    def org(self):
        email, password = os.environ.get("PALM_RIDGE_SUPERADMIN_EMAIL"), os.environ.get("PALM_RIDGE_SUPERADMIN_PASSWORD")
        self.user_password = os.environ.get("PALM_RIDGE_USER_PASSWORD")
        if not (email and password and self.user_password):
            raise SystemExit("Set PALM_RIDGE_SUPERADMIN_EMAIL, PALM_RIDGE_SUPERADMIN_PASSWORD and PALM_RIDGE_USER_PASSWORD.")
        self.sa.login(email, password)
        found = [t for t in (self.sa.get("/api/admin/tenants") or []) if t["name"] == P.ORG_NAME]
        tenant = found[0] if found else self.sa.post("/api/admin/tenants", {"name": P.ORG_NAME})
        self.tenant_id = tenant["id"]
        self.out["tenantId"] = self.tenant_id
        # The shape the tutorial recorder reads (tutorials/capture/lib/context.mjs, record-tutorial.mjs).
        # The file holds local test passwords: it lives in tutorials/work/ (gitignored) and is never printed.
        self.out["tenant"] = {"id": self.tenant_id, "name": P.ORG_NAME, "slug": tenant.get("slug")}
        self.sa.put(f"/api/admin/tenants/{self.tenant_id}", {"address": P.ORG_ADDRESS, "phone": P.ORG_PHONE, "trn": P.ORG_TRN})
        for feature, on in (("LEASE_RENEWALS", True), ("EMAIL_NOTIFICATIONS", False), ("MOBILE_FINANCE", False)):
            self.sa.put(f"/api/admin/tenants/{self.tenant_id}/features/{feature}", {"enabled": on})
        users = {(u.get("email") or "").lower(): u for u in (self.sa.get("/api/admin/users") or [])
                 if str(u.get("tenantId")) == str(self.tenant_id)}
        for person in (P.ADMIN, P.ACCOUNTANT):
            if person["email"] not in users:
                self.sa.post("/api/admin/users", {"email": person["email"], "password": self.user_password,
                                                  "name": person["name"], "role": person["role"],
                                                  "tenantId": self.tenant_id, "phoneNumber": person["phone"]})
                log(f"{person['role']} created: {person['name']}")
        self.out["adminLogin"] = {"email": P.ADMIN["email"], "password": self.user_password, "name": P.ADMIN["name"]}
        self.out["accountantLogin"] = {"email": P.ACCOUNTANT["email"], "password": self.user_password,
                                       "name": P.ACCOUNTANT["name"]}
        self.api.login(P.ADMIN["email"], self.user_password)
        log(f"organisation ready: {P.ORG_NAME} ({self.tenant_id})")

    def branding(self):
        current = next((t for t in (self.sa.get("/api/admin/tenants") or []) if t["id"] == self.tenant_id), {})
        patch = {}
        for key, name in (("logoUrl", "logo-mark.png"), ("stampImageUrl", "stamp.png")):
            if current.get(key):
                continue
            with open(BRAND / name, "rb") as fh:
                up = self.api.call("POST", "/api/v1/assets/upload", files={"file": (name, fh, "image/png")},
                                   data={"folder": "assets"})
            patch[key] = up["url"]
        if patch:
            self.sa.put(f"/api/admin/tenants/{self.tenant_id}", patch)
            log(f"branding set: {', '.join(patch)}")

    # ── books, portfolio, banks, vendors ────────────────────────────────────────
    def books(self):
        self.api.post("/api/v1/finance/accounts/seed")
        fs = self.api.get("/api/v1/finance/fiscal-settings") or {}
        if fs.get("booksStartDate") != iso(P.BOOKS_START):
            if fs.get("booksStartDate"):
                raise SystemExit(f"books already start on {fs['booksStartDate']}; this org is not the one the plan describes")
            self.api.put("/api/v1/finance/fiscal-settings", {"fiscalYearStartMonth": 1, "booksStartDate": iso(P.BOOKS_START)})
        self.chart = {a["code"]: a for a in (self.api.get("/api/v1/finance/accounts") or []) if a.get("code")}
        self.defaults = {r["role"]: r["accountId"] for r in (self.api.get("/api/v1/finance/default-accounts") or [])
                         if r.get("accountId")}
        for key, (code, en, ar) in P.EXPENSE_LEAVES.items():
            if code not in self.chart:
                parent = self.chart["D-01"]
                self.chart[code] = self.api.post("/api/v1/finance/accounts", {
                    "code": code, "nameEn": en, "nameAr": ar, "accountType": parent["accountType"],
                    "accountSubType": parent.get("accountSubType"), "parentId": parent["id"], "group": False})
            self.expense[key] = self.chart[code]["id"]
        log(f"books start {iso(P.BOOKS_START)}; chart, template and charge types seeded")

    def portfolio(self):
        existing = {s["property"]["nameEn"]: s["property"] for s in (self.api.get("/api/v1/properties") or [])
                    if isinstance(s, dict) and s.get("property")}
        for key, p in P.PROPERTIES.items():
            prop = existing.get(p["nameEn"]) or self.api.post("/api/v1/properties", {
                "nameEn": p["nameEn"], "nameAr": p["nameAr"], "type": p["type"], "emirate": "DUBAI", "address": p["address"]})
            self.api.post(f"/api/v1/rent-settings/{prop['id']}", {
                "dueDayOfMonth": 1, "gracePeriodDays": 5, "fineBounceAmount": 500.0, "fineSignatureMismatchAmount": 250.0,
                "fineAccountClosedAmount": 500.0, "fineGraceDays": 3, "finePerDayRate": 25.0})
            self.api.post(f"/api/v1/properties/{prop['id']}/accounts/generate")
            self.roles[key] = {r["role"]: r["accountId"] for r in (self.api.get(f"/api/v1/properties/{prop['id']}/accounts") or [])
                               if r.get("accountId")}
            self.props[key] = prop
        for key, (pk, number, utype, sqft, rent) in P.UNITS.items():
            have = {u.get("unitNumber"): u for u in (self.api.get(f"/api/v1/units/property/{self.props[pk]['id']}") or [])}
            self.units[key] = have.get(number) or self.api.post("/api/v1/units", {
                "property": {"id": self.props[pk]["id"]}, "unitNumber": number, "type": utype,
                "sizeSqft": sqft, "expectedRent": rent, "status": "VACANT"})
        renters = {(r.get("email") or "").lower(): r for r in items(self.api.get("/api/v1/renters"))}
        for key, (en, ar, phone) in P.TENANTS.items():
            email = f"{key}@{P.EMAIL_DOMAIN}"
            self.renters[key] = renters.get(email) or self.api.post("/api/v1/renters", {
                "nameEn": en, "nameAr": ar, "email": email, "phone": phone, "primaryLanguage": "EN",
                "createPortalAccount": False})
        log(f"{len(self.props)} properties, {len(self.units)} units, {len(self.renters)} tenants")

    def banks(self):
        have = {(b.get("accountNumber") or ""): b for b in (self.api.get("/api/v1/bank-accounts") or [])}
        for key, b in P.BANK_ACCOUNTS.items():
            leaf = self.roles[key]["BANK"]
            acct = have.get(b["accountNumber"]) or self.api.post("/api/v1/bank-accounts", {
                **b, "branchName": "Al Quoz", "currency": "AED", "property": {"id": self.props[key]["id"]},
                "coaAccount": {"id": leaf}, "isDefault": True, "active": True})
            self.api.put(f"/api/v1/finance/bank-reconciliation/bank-accounts/{acct['id']}/ledgers", {"accountIds": [leaf]})
            self.bank_accounts[key] = acct
        vendors = {v.get("nameEn"): v for v in items(self.api.get("/api/v1/vendors"))}
        for key, v in P.VENDORS.items():
            got = vendors.get(v["nameEn"]) or self.api.post("/api/v1/vendors", {**v, "paymentTermsDays": 30, "active": True})
            got = self.api.get(f"/api/v1/vendors/{got['id']}")
            if not (got.get("payableAccount") or {}).get("id"):
                raise SystemExit(f"vendor {v['nameEn']} has no payable account")
            self.vendors[key] = got
        log("bank accounts and vendors ready")

    # ── opening position (31 Aug 2025) ──────────────────────────────────────────
    def opening(self):
        grid = self.api.get("/api/v1/finance/opening-balances")
        if not grid.get("posted"):
            entered = {}
            for key, amount in P.OPENING_BANK.items():
                entered[self.roles[key]["BANK"]] = (amount, Decimal(0))
            for it in P.AP_OPENING_ITEMS:
                leaf = self.vendors[it["vendor"]]["payableAccount"]["id"]
                d, c = entered.get(leaf, (Decimal(0), Decimal(0)))
                entered[leaf] = (d, c + it["amount"])
            capital = self.chart["F-01"]["id"]
            dr = sum(v[0] for v in entered.values())
            cr = sum(v[1] for v in entered.values())
            entered[capital] = (Decimal(0), dr - cr)
            for acc, (d, c) in entered.items():
                self.api.put(f"/api/v1/finance/opening-balances/{acc}", {"debit": num(d), "credit": num(c)})
            posted = self.api.post("/api/v1/finance/opening-balances/post")
            log(f"opening balances posted: {posted.get('entryNumber') if isinstance(posted, dict) else posted}")
        have = {(x.get("invoiceNumber") or "") for x in items(self.api.get("/api/v1/finance/ap-opening-items"))}
        for it in P.AP_OPENING_ITEMS:
            if it["invoiceNumber"] not in have:
                self.api.post("/api/v1/finance/ap-opening-items", {
                    "vendorId": self.vendors[it["vendor"]]["id"], "invoiceNumber": it["invoiceNumber"],
                    "invoiceDate": iso(it["invoiceDate"]), "dueDate": iso(it["dueDate"]), "amount": num(it["amount"]),
                    "propertyId": self.props[it["property"]]["id"]})
                log(f"supplier invoice open at cut-over: {it['invoiceNumber']}")

    # ── the dated story ─────────────────────────────────────────────────────────
    def build_events(self):
        ev = []                                   # (date, order, label, fn)

        def at(date, order, label, fn):
            ev.append((date, order, label, fn))

        for key, c in P.CONTRACTS.items():
            at(c["contract"], 10, f"contract {key}", lambda k=key: self.contract(k))
            if c.get("post", True):
                self.cheque_events(key, c, at)
            for p in c.get("penalties") or []:
                at(p["date"], 45, f"penalty {key}", lambda k=key, p=p: self.penalty(k, p))
                if p.get("approve"):
                    at(p["approve"], 46, f"penalty approve {key}", lambda k=key, p=p: self.penalty_approve(k, p))
                if p.get("collect"):
                    at(p["collect"], 47, f"penalty collect {key}", lambda k=key, p=p: self.penalty_collect(k, p))
            for no, rep in (c.get("replace") or {}).items():
                at(rep["date"], 40, f"replace {no}", lambda k=key, no=no, rep=rep: self.replace(k, no, rep))
            if c.get("jv_bank_cash"):
                at(c["jv_bank_cash"]["date"], 60, "bank the cash", lambda k=key: self.bank_cash(k))
            if c.get("terminate"):
                at(c["terminate"]["date"], 70, f"terminate {key}", lambda k=key: self.terminate(k))
            if c.get("settle"):
                at(c["settle"]["date"], 71, f"settle {key}", lambda k=key: self.settle(k))
            if c.get("refund"):
                at(c["refund"]["date"], 72, f"refund {key}", lambda k=key: self.refund(k))
            if c.get("write_off"):
                at(c["write_off"]["date"], 73, f"write off {key}", lambda k=key: self.write_off(k))
            if c.get("recover"):
                at(c["recover"]["date"], 74, f"recover {key}", lambda k=key: self.recover(k))
            if c.get("renew"):
                at(c["renew"]["contract"], 75, f"renew {key}", lambda k=key: self.renew(k))
            if c.get("ticket"):
                t = c["ticket"]
                at(t["reported"], 80, f"ticket {key}", lambda k=key: self.ticket(k))
                at(t["bill"]["date"], 81, f"ticket bill {key}", lambda k=key: self.ticket_bill(k))
        for inv in P.purchase_invoices():
            at(inv["date"], 50, f"bill {inv['key']}", lambda inv=inv: self.bill(inv))
        for run in P.PAYMENT_RUNS:
            at(run[1], 55, f"payment run {run[0]}", lambda run=run: self.payment_run(run))
        at(P.ISSUED_CHEQUE_PRESENTED_ON, 56, "supplier cheques presented", self.present_issued)
        m = P.BOOKS_START
        while P.month_end(m) <= P.RECOGNISED_THROUGH:
            me = P.month_end(m)
            at(me, 90, f"month-end {me:%b %Y}", lambda me=me: self.month_end(me))
            m = P.add_months(m, 1)
        for qs in P.VAT_QUARTERS_FILED:
            filed_on = P.add_months(qs, 3) + dt.timedelta(days=19)
            at(filed_on, 95, f"VAT return {qs:%b %Y}", lambda qs=qs: self.vat_return(qs))
        at(D(P.FISCAL_YEAR_CLOSED + 1, 2, 15), 96, f"close FY {P.FISCAL_YEAR_CLOSED}", self.close_year)
        at(P.LOCKED_THROUGH + dt.timedelta(days=25), 97, "period lock", self.lock)
        return sorted(ev, key=lambda e: (e[0], e[1]))

    def cheque_events(self, key, c, at):
        """Deposit / clear / bounce events for every row the plan can see before posting. Rows of a
        server-generated grid are resolved by date when their event fires."""
        fates = c.get("fates") or {}
        if "cheques" in c:
            planned = [(r["chequeNumber"], r["chequeDate"]) for r in c["cheques"] if r["mode"] == "PDC"]
        else:
            planned = []
        for no, d in planned:
            self._fate_events(key, ("no", no), d, fates.get(no), at)
        for rep in (c.get("replace") or {}).values():
            for r in rep["rows"]:
                if r["mode"] == "PDC":
                    self._fate_events(key, ("no", r["chequeNumber"]), r["chequeDate"], None, at)
        if "generate" in c:
            at(c["contract"], 11, f"schedule generated rows {key}",
               lambda k=key, f=fates: self._generated_fates(k, f))
        if c.get("renew"):
            at(c["renew"]["contract"], 76, f"schedule renewed rows {key}",
               lambda k=key: self._generated_fates(k, {}, renewal=True))

    def _fate_events(self, key, ref, cheque_date, fate, at):
        kind, when = (fate or (("CLEAR" if cheque_date <= P.CLEAR_UNTIL else "HOLD"), None))
        if kind == "HOLD":
            return
        dep = when if kind == "DEPOSIT" else cheque_date + dt.timedelta(days=1)
        if kind == "CLEAR_LATE":
            dep = when - dt.timedelta(days=2)
        at(dep, 20, f"deposit {ref[1]}", lambda: self.cheque_step(key, ref, "deposit", dep))
        if kind in ("CLEAR", "CLEAR_LATE"):
            clr = when if kind == "CLEAR_LATE" else cheque_date + dt.timedelta(days=3)
            at(clr, 30, f"clear {ref[1]}", lambda: self.cheque_step(key, ref, "clear", clr))
        elif kind == "BOUNCE":
            at(when, 30, f"bounce {ref[1]}", lambda: self.cheque_step(key, ref, "bounce", when))

    def later(self, date, order, label, fn):
        """Queue an event while the run loop is iterating; run() merges and re-sorts."""
        self._pending.append((date, order, label, fn))

    def _generated_fates(self, key, fates, renewal=False):
        """Called once the grid exists: queue the deposit / clear events for its rows."""
        lease = self.leases[key + ("-renewal" if renewal else "")]
        for r in self.rows(lease["id"]):
            if r.get("mode") != "PDC" or not r.get("chequeDate"):
                continue
            d = D.fromisoformat(r["chequeDate"])
            self._fate_events(key + ("-renewal" if renewal else ""), ("id", r["id"]), d, fates.get(f"on:{iso(d)}"),
                              self.later)

    def run(self):
        queue = self.build_events()
        done = 0
        while queue:
            date, order, label, fn = queue.pop(0)
            before = len(self._pending)
            fn()
            done += 1
            if len(self._pending) != before:           # an event scheduled more events: merge and re-sort
                queue = sorted(queue + self._pending[before:], key=lambda e: (e[0], e[1]))
                del self._pending[before:]
        log(f"{done} dated events replayed")

    # ── contracts and cheques ───────────────────────────────────────────────────
    def rows(self, lease_id):
        return sorted(self.api.get(f"/api/v1/leases/{lease_id}/cheques") or [], key=lambda r: r["seqNo"])

    def find_row(self, key, ref):
        lease = self.leases[key]
        if ref[0] == "id":
            return self.api.get(f"/api/v1/cheques/{ref[1]}")
        for r in self.rows(lease["id"]):
            if r.get("chequeNumber") == ref[1]:
                return r
        raise RuntimeError(f"{key}: no cheque {ref[1]}")

    def contract(self, key):
        c = P.CONTRACTS[key]
        unit = self.units[c["unit"]]
        mine = [l for l in items(self.api.get("/api/v1/leases")) if l.get("unitId") == unit["id"]
                and l.get("renterId") == self.renters[c["tenant"]]["id"]]
        lease = next((l for l in mine if l.get("startDate") == iso(c["start"])), None)
        if lease is None:
            lease = self.api.post("/api/v1/leases", {
                "unitId": unit["id"], "renterId": self.renters[c["tenant"]]["id"],
                "startDate": iso(c["start"]), "endDate": iso(c["end"]), "agreementDate": iso(c["contract"]),
                "contractDate": iso(c["contract"]), "gracePeriodDays": 5, "paymentTerms": 4,
                "paymentMethod": "CHEQUE", "depositPaymentMethod": "CHEQUE", "rentVatApplicable": bool(c.get("vat")),
                "lines": [{"chargeTypeCode": code, "grossAmount": num(g), "discountAmount": 0.0, "vatApplicable": v,
                           "narration": ""} for code, g, v in c["lines"]]})
        self.leases[key] = lease
        if lease.get("status") == "DRAFT":
            if c.get("rent_free") and not lease.get("rentFreePeriods"):
                self.api.put(f"/api/v1/leases/{lease['id']}/rent-free-periods",
                             [{"fromDate": iso(a), "toDate": iso(b), "note": n} for a, b, n in c["rent_free"]])
            if not self.rows(lease["id"]):
                if "cheques" in c:
                    self.api.put(f"/api/v1/leases/{lease['id']}/cheques", [self.row_body(r, c["contract"]) for r in c["cheques"]])
                else:
                    g = c["generate"]
                    self.api.post(f"/api/v1/leases/{lease['id']}/cheques/generate", {
                        "installments": g["installments"], "firstDueDate": iso(g["firstDueDate"]), "payeeBank": g["payeeBank"],
                        "foldDepositsAndFeesIntoFirst": False, "mode": "PDC"})
                    self.api.post(f"/api/v1/leases/{lease['id']}/cheques/numbers", {"startingNumber": g["startingNumber"]})
            if c.get("post", True):
                posted = self.api.post(f"/api/v1/leases/{lease['id']}/post")
                lease = posted["lease"]
                log(f"{key}: posted {posted.get('tcoEntryNumber')}")
        self.leases[key] = lease

    @staticmethod
    def row_body(r, posting_date):
        return {"postingDate": iso(posting_date), "chequeNumber": r["chequeNumber"], "chequeDate": iso(r["chequeDate"]),
                "payeeBank": r["payeeBank"] if r["mode"] == "PDC" else None, "amount": num(r["amount"]),
                "narration": r["narration"], "mode": r["mode"], "vatAmount": num(r["vatAmount"]), "rowKind": r["rowKind"]}

    def cheque_step(self, key, ref, step, date):
        row = self.find_row(key, ref)
        s = row.get("status")
        if step == "deposit" and s == "REGISTERED":
            self.api.put(f"/api/v1/cheques/{row['id']}/deposit", {"date": iso(date)})
        elif step == "clear" and s == "DEPOSITED":
            self.api.put(f"/api/v1/cheques/{row['id']}/clear", {"date": iso(date)})
        elif step == "bounce" and s == "DEPOSITED":
            self.api.put(f"/api/v1/cheques/{row['id']}/bounce", {"date": iso(date), "failureReason": "BOUNCE",
                                                                 "notes": "Returned unpaid: insufficient funds"})
            log(f"{key}: cheque {row.get('chequeNumber')} returned")

    def replace(self, key, no, rep):
        bounced = self.find_row(key, ("no", no))
        if bounced.get("status") == "BOUNCED" and not bounced.get("replacedById"):
            self.api.post(f"/api/v1/cheques/{bounced['id']}/replace", {
                "date": iso(rep["date"]), "notes": rep["rows"][0]["narration"],
                "replacements": [{k: v for k, v in self.row_body(r, rep["date"]).items() if k != "rowKind"}
                                 for r in rep["rows"]]})
            log(f"{key}: returned cheque {no} replaced")
        if rep.get("receive"):
            for r in self.rows(self.leases[key]["id"]):
                if r.get("mode") == "CASH" and r.get("status") == "REGISTERED" and r.get("replacesId") == bounced["id"]:
                    self.api.put(f"/api/v1/cheques/{r['id']}/receive", {"date": iso(rep["receive"]),
                                                                       "notes": "Cash received at the office"})
                    log(f"{key}: cash received for returned cheque {no}")

    def bank_cash(self, key):
        c = P.CONTRACTS[key]["jv_bank_cash"]
        prop = self.props[P.UNITS[P.CONTRACTS[key]["unit"]][0]]
        known = [j for j in items(self.api.get("/api/v1/finance/journals", {"docType": "JV", "size": 200}))
                 if j.get("narration") == c["narration"]]
        if known:
            return
        cash, bank = self.defaults.get("CASH"), self.roles[P.UNITS[P.CONTRACTS[key]["unit"]][0]]["BANK"]
        self.api.post("/api/v1/finance/journals", {"entryDate": iso(c["date"]), "narration": c["narration"], "lines": [
            {"accountId": bank, "debit": num(c["amount"]), "narration": "Cash deposited", "propertyId": prop["id"]},
            {"accountId": cash, "credit": num(c["amount"]), "narration": "Cash deposited", "propertyId": prop["id"]}]})
        log(f"JV: {c['narration']}")

    # ── penalties and recharges ─────────────────────────────────────────────────
    def _penalties(self, key):
        return items(self.api.get("/api/v1/penalties", {"leaseId": self.leases[key]["id"], "size": 100}))

    def penalty(self, key, p):
        reason_rows = [x for x in self._penalties(key) if x.get("reason") == p["reason"]]
        if reason_rows:
            return
        cheque_id = None
        if p.get("cheque"):
            cheque_id = self.find_row(key, ("no", p["cheque"]))["id"]
        elif p.get("cheque_on"):
            cheque_id = next(r["id"] for r in self.rows(self.leases[key]["id"]) if r.get("chequeDate") == iso(p["cheque_on"]))
        self.api.post("/api/v1/penalties", {"leaseId": self.leases[key]["id"], "chequeId": cheque_id, "reason": p["reason"],
                                            "amount": num(p["amount"]), "description": p["description"],
                                            "incidentDate": iso(p["date"])})
        log(f"{key}: {p['reason'].lower()} penalty proposed")

    def penalty_approve(self, key, p):
        for x in self._penalties(key):
            if x.get("reason") == p["reason"] and x.get("status") == "PROPOSED":
                self.api.post(f"/api/v1/penalties/{x['id']}/approve", {"date": iso(p["approve"]), "note": "Per the fine schedule"})
                log(f"{key}: penalty approved")

    def penalty_collect(self, key, p):
        ids = {x["id"] for x in self._penalties(key) if x.get("reason") == p["reason"]}
        for r in self.rows(self.leases[key]["id"]):
            if r.get("penaltyAssessmentId") in ids and r.get("status") == "REGISTERED" and r.get("mode") in ("CASH", "TRANSFER"):
                self.api.put(f"/api/v1/cheques/{r['id']}/receive", {"date": iso(p["collect"]), "notes": "Fine paid at the office"})
                log(f"{key}: penalty collected")

    def ticket(self, key):
        t, c = P.CONTRACTS[key]["ticket"], P.CONTRACTS[key]
        mine = [x for x in items(self.api.get("/api/v1/tickets")) if x.get("title") == t["title"]]
        if mine:
            self.ticket_id = mine[0]["id"]
            return
        unit = self.units[c["unit"]]
        self.ticket_id = self.api.post("/api/v1/tickets", {
            "propertyId": self.props[P.UNITS[c["unit"]][0]]["id"], "unitId": unit["id"], "leaseId": self.leases[key]["id"],
            "title": t["title"], "description": "Hairline crack across the balcony glass panel; tenant reports it was hit by furniture.",
            "category": t["category"], "priority": "MEDIUM", "onBehalfOfRenterId": self.renters[c["tenant"]]["id"],
            "reportedDate": iso(t["reported"])})["id"]
        log(f"{key}: ticket raised")

    def ticket_bill(self, key):
        t = P.CONTRACTS[key]["ticket"]
        b = t["bill"]
        inv = self.bill({"key": b["invoice"], "vendor": b["vendor"], "invoiceNumber": b["invoice"], "date": b["date"],
                         "lines": [("repairs", P.UNITS[P.CONTRACTS[key]["unit"]][0], Decimal(str(b["amount"])))],
                         "narration": b["description"]})
        charges = self.api.get(f"/api/v1/tickets/{self.ticket_id}/charges") or {}
        if not any((x.get("id") or x.get("voucherId")) == inv["id"] for x in (charges.get("bills") or [])):
            charges = self.api.post(f"/api/v1/tickets/{self.ticket_id}/bills/{inv['id']}")
        if not (charges.get("recharges") or []):
            self.api.post(f"/api/v1/tickets/{self.ticket_id}/recharge", {
                "amount": num(t["recharge"]["amount"]), "vatable": False, "description": t["recharge"]["description"]})
            log(f"{key}: recharge proposed from the ticket (left for finance to approve)")

    # ── termination, settlement, refund, bad debt ───────────────────────────────
    def terminate(self, key):
        lease = self.api.get(f"/api/v1/leases/{self.leases[key]['id']}")
        if lease["status"] != "ACTIVE":
            return
        t = P.CONTRACTS[key]["terminate"]
        pv = self.api.get(f"/api/v1/leases/{lease['id']}/terminate/preview", {"date": iso(t["date"])})
        self.api.post(f"/api/v1/leases/{lease['id']}/terminate", {
            "terminationDate": iso(t["date"]), "notes": t["notes"],
            "returnChequeIds": [x["id"] for x in pv.get("chequesToReturn") or []],
            "keepChequeIds": [x["id"] for x in pv.get("chequesToKeep") or []]})
        log(f"{key}: terminated on {iso(t['date'])}")

    def settle(self, key):
        lid = self.leases[key]["id"]
        s = self.api.call("GET", f"/api/v1/leases/{lid}/settlement", expect=(200, 204, 404)) or {}
        if (s.get("status") or "").upper() in ("FINALIZED", "FINALISED", "COMPLETED"):
            return
        st = P.CONTRACTS[key]["settle"]
        self.api.post(f"/api/v1/leases/{lid}/settlement/draft", {"notes": "Move-out inspection", "deductions": [
            {"category": cat, "description": desc, "amount": num(amt)} for cat, desc, amt in st["deductions"]]})
        res = self.api.post(f"/api/v1/leases/{lid}/settlement/finalize",
                            {"settlementDate": iso(st["date"]), "acknowledgeOutstanding": True})
        self.out.setdefault("settlements", {})[key] = {k: res.get(k) for k in ("refundAmount", "balanceDue", "journalNumber")}
        log(f"{key}: settlement finalised (refund {res.get('refundAmount')}, due {res.get('balanceDue')})")

    def refund(self, key):
        r = P.CONTRACTS[key]["refund"]
        lid = self.leases[key]["id"]
        s = self.api.get(f"/api/v1/leases/{lid}/settlement")
        out = Decimal(str(s.get("refundOutstanding") if s.get("refundOutstanding") is not None else 0))
        if out <= 0:
            return
        prop = P.UNITS[P.CONTRACTS[key]["unit"]][0]
        vo = self.api.post("/api/v1/finance/vouchers", {
            "docType": "BPV", "docDate": iso(r["date"]), "settlementId": s["id"], "paymentAccountId": self.roles[prop]["BANK"],
            "paymentMethod": "TRANSFER", "paymentReference": r["reference"], "narration": "Security deposit refund",
            "propertyId": self.props[prop]["id"],
            "lines": [{"accountId": self.defaults["RENTER_REFUND_PAYABLE"], "description": "Deposit refund",
                       "amount": num(out)}]})
        self.api.post(f"/api/v1/finance/vouchers/{vo['id']}/post", {})
        log(f"{key}: deposit refund {out} paid by transfer")

    def write_off(self, key):
        lid = self.leases[key]["id"]
        if any(w.get("status") in ("PROPOSED", "WRITTEN_OFF") for w in self.api.get("/api/v1/finance/bad-debts", {"leaseId": lid}) or []):
            return self._approve_write_off(key)
        w = P.CONTRACTS[key]["write_off"]
        cands = self.api.get("/api/v1/finance/bad-debts/candidates", {"leaseId": lid, "on": iso(w["date"])}) or []
        ids = [x["chequeId"] for x in cands if x.get("chequeId")]
        if not ids:
            log(f"WARNING {key}: nothing open to write off on {iso(w['date'])}")
            return
        self.api.post("/api/v1/finance/bad-debts", {"leaseId": lid, "chequeIds": ids, "date": iso(w["date"]), "reason": w["reason"]})
        self._approve_write_off(key)

    def _approve_write_off(self, key):
        lid = self.leases[key]["id"]
        for w in self.api.get("/api/v1/finance/bad-debts", {"leaseId": lid}) or []:
            if w.get("status") == "PROPOSED":
                done = self.api.post(f"/api/v1/finance/bad-debts/{w['id']}/approve",
                                     {"note": "Approved by the Company Admin", "date": iso(P.CONTRACTS[key]["write_off"]["date"])})
                self.out.setdefault("writeOffs", {})[key] = {"amount": done.get("amount"), "journal": done.get("journalNumber")}
                log(f"{key}: bad debt written off ({done.get('amount')})")

    def recover(self, key):
        r = P.CONTRACTS[key]["recover"]
        lid = self.leases[key]["id"]
        for w in self.api.get("/api/v1/finance/bad-debts", {"leaseId": lid}) or []:
            if w.get("status") == "WRITTEN_OFF" and not (w.get("recoveries") or []):
                amount = min(Decimal(str(r["amount"])), Decimal(str(w["amount"])))
                self.api.post(f"/api/v1/finance/bad-debts/{w['id']}/recoveries", {
                    "amount": num(amount), "date": iso(r["date"]), "note": r["note"],
                    "accountId": self.roles[P.UNITS[P.CONTRACTS[key]["unit"]][0]]["BANK"]})
                log(f"{key}: {amount} recovered on the written-off debt")

    def renew(self, key):
        c = P.CONTRACTS[key]
        r = c["renew"]
        old = self.api.get(f"/api/v1/leases/{self.leases[key]['id']}")
        succ = [l for l in items(self.api.get("/api/v1/leases")) if l.get("renewedFromLeaseId") == old["id"]]
        new = succ[0] if succ else self.api.post(f"/api/v1/leases/{old['id']}/renew", {
            "contractDate": iso(r["contract"]), "startDate": iso(r["start"]), "endDate": iso(r["end"]),
            "carryDepositForward": True, "rentChange": {"mode": "PERCENT", "percent": r["percent"]},
            "additionalLines": [{"chargeTypeCode": "RENEWAL_FEE", "grossAmount": num(r["fee"]), "discountAmount": 0.0,
                                 "vatApplicable": False, "narration": "Renewal fee"}]})
        if new.get("status") == "DRAFT":
            if not self.rows(new["id"]):
                self.api.post(f"/api/v1/leases/{new['id']}/cheques/generate", {
                    "installments": r["installments"], "firstDueDate": iso(r["start"]), "payeeBank": r["payeeBank"],
                    "foldDepositsAndFeesIntoFirst": True, "mode": "PDC"})
                self.api.post(f"/api/v1/leases/{new['id']}/cheques/numbers", {"startingNumber": r["startingNumber"]})
            new = self.api.post(f"/api/v1/leases/{new['id']}/post")["lease"]
            log(f"{key}: renewed from {iso(r['start'])} (+{r['percent']} %)")
        self.leases[key + "-renewal"] = new

    # ── payables ────────────────────────────────────────────────────────────────
    def bill(self, inv):
        vouchers = getattr(self, "_vouchers", None)
        if vouchers is None:
            vouchers = self._vouchers = {(v.get("invoiceNumber") or ""): v for v in
                                         items(self.api.get("/api/v1/finance/vouchers", {"docType": "PISR", "size": 500}))}
        v = vouchers.get(inv["invoiceNumber"])
        if v and v.get("status") == "POSTED":
            return v
        props = {pk for _, pk, _ in inv["lines"]}
        body = {"docType": "PISR", "docDate": iso(inv["date"]), "vendorId": self.vendors[inv["vendor"]]["id"],
                "invoiceNumber": inv["invoiceNumber"], "supplierInvoiceDate": iso(inv["date"]), "narration": inv["narration"],
                "lines": [{"accountId": self.expense[exp], "description": inv["narration"], "amount": num(net), "vatRate": 5.0,
                           "propertyId": self.props[pk]["id"]} for exp, pk, net in inv["lines"]]}
        if len(props) == 1:
            body["propertyId"] = self.props[next(iter(props))]["id"]
        v = v or self.api.post("/api/v1/finance/vouchers", body)
        v = self.api.post(f"/api/v1/finance/vouchers/{v['id']}/post", {})
        vouchers[inv["invoiceNumber"]] = v
        return v

    def payment_run(self, run):
        key, pay_date, method, cheque_date, first_no, due_by = run
        runs = items(self.api.get("/api/v1/finance/payment-runs"))
        mine = next((r for r in runs if r.get("narration") == f"Supplier payments {key}"), None)
        if mine and mine.get("status") == "POSTED":
            return
        if mine is None:
            cand = [c["item"] for c in (self.api.get("/api/v1/finance/payment-runs/candidates") or {}).get("items", [])
                    if not c.get("draftRuns")]
            pick = [it for it in cand if Decimal(str(it.get("open") or 0)) > 0
                    and D.fromisoformat(it.get("dueDate") or it["docDate"]) <= due_by
                    and D.fromisoformat(it.get("invoiceDate") or it["docDate"]) <= pay_date]
            if not pick:
                log(f"payment run {key}: nothing due")
                return
            body = {"paymentDate": iso(pay_date), "paymentAccountId": self.roles["residences"]["BANK"], "method": method,
                    "narration": f"Supplier payments {key}",
                    "items": [{"invoiceId": it["id"] if it["kind"] != "OPENING" else None,
                               "openingItemId": it["id"] if it["kind"] == "OPENING" else None,
                               "amount": float(it["open"]), "applyAdvance": True} for it in pick]}
            if method == "CHEQUE":
                body["chequeDate"], body["firstChequeNumber"] = iso(cheque_date), first_no
            mine = self.api.post("/api/v1/finance/payment-runs", body)
        pv = self.api.get(f"/api/v1/finance/payment-runs/{mine['id']}/preview")
        if not pv.get("postable"):
            raise RuntimeError(f"payment run {key} is not postable: {[p.get('message') for p in pv.get('problems') or []]}")
        self.api.post(f"/api/v1/finance/payment-runs/{mine['id']}/post", {"vendors": [
            {"vendorId": v["vendorId"], "netPayment": v["netPayment"], "advanceApplied": v.get("advanceApplied"),
             "chequeNumber": v.get("chequeNumber"), "items": [{"itemId": i["itemId"], "paid": i["paid"]} for i in v["items"]]}
            for v in pv["vendors"]]})
        log(f"payment run {key} posted ({method.lower()}, {len(pv['vendors'])} suppliers)")

    def present_issued(self):
        for c in items(self.api.get("/api/v1/finance/issued-cheques", {"status": "ISSUED"})):
            if D.fromisoformat(c["chequeDate"]) <= P.ISSUED_CHEQUE_PRESENTED_ON:
                self.api.post(f"/api/v1/finance/issued-cheques/{c['id']}/present", {"date": iso(P.ISSUED_CHEQUE_PRESENTED_ON)})
                log(f"supplier cheque {c.get('chequeNumber')} presented")

    # ── period close ────────────────────────────────────────────────────────────
    def month_end(self, me):
        if me <= P.RECOGNISED_THROUGH:
            self.api.post("/api/v1/finance/recognition/run", params={"to": iso(me)})
        if me <= P.TAX_POINTS_THROUGH:
            self.api.post("/api/v1/finance/vat/tax-points/run", params={"to": iso(me)})

    def vat_return(self, qs):
        ret = self.api.get("/api/v1/finance/vat-returns", {"periodStart": iso(qs)})
        if (ret.get("status") or "") == "FILED":
            return
        body = {"periodStart": iso(qs), "filingReference": f"FTA-DEMO-{qs:%Y}Q{(qs.month - 1) // 3 + 1}"}
        chk = ret.get("outputCheck") or {}
        if chk and not chk.get("ok", True):
            raise RuntimeError(f"VAT return {qs} output check fails: {chk}; the seed files clean returns only")
        self.api.post("/api/v1/finance/vat-returns/file", body)
        log(f"VAT return filed for the quarter from {qs:%b %Y}")

    def close_year(self):
        fy = P.FISCAL_YEAR_CLOSED
        years = items(self.api.get("/api/v1/finance/fiscal-years"))
        if any(y.get("fiscalYear") == fy and y.get("status") == "CLOSED" for y in years):
            return
        pv = self.api.get(f"/api/v1/finance/fiscal-years/{fy}/close-preview")
        r = self.api.post(f"/api/v1/finance/fiscal-years/{fy}/close", {"overrideWarnings": True})
        log(f"FY {fy} closed: {r.get('journalNumber') if isinstance(r, dict) else r} (preview problems: {len(pv.get('problems') or [])})")

    def lock(self):
        fs = self.api.get("/api/v1/finance/fiscal-settings") or {}
        if (fs.get("booksLockedThrough") or "") < iso(P.LOCKED_THROUGH):
            self.api.post("/api/v1/finance/fiscal-settings/lock", {"through": iso(P.LOCKED_THROUGH)})
            log(f"books locked through {iso(P.LOCKED_THROUGH)}")

    # ── the statement the bank-reconciliation tutorial imports ─────────────────
    def statement(self):
        m = P.BANK_STATEMENT_MONTH
        me = P.month_end(m)
        acct = self.bank_accounts["residences"]
        leaf = self.roles["residences"]["BANK"]
        tb = self.api.get("/api/v1/finance/trial-balance", {"asOf": iso(m - dt.timedelta(days=1))}) or []
        opening = sum((Decimal(str(r["balance"])) for r in items(tb) if r.get("accountId") == leaf), Decimal(0))
        ws = self.api.get(f"/api/v1/finance/bank-reconciliation/bank-accounts/{acct['id']}/workspace",
                          {"from": iso(m), "to": iso(me), "state": "ALL"}) or {}
        lines = []
        for bi in ws.get("bookItems") or []:
            ed = D.fromisoformat(bi["entryDate"])
            if bi.get("docType") == "OB" or bi.get("reversalOfId") or bi.get("reversedById"):
                continue
            if ed >= me - dt.timedelta(days=1):
                continue                                  # the bank books it next month: deposit in transit
            amt = Decimal(str(bi["amount"]))
            chq = bi.get("chequeNo") or ""
            desc = (f"CHQ CLEARING {chq}" if chq else "INWARD TT") if amt > 0 else (f"CHQ PAID {chq}" if chq else "OUTWARD TT")
            lines.append({"date": ed, "desc": desc, "ref": "" if chq else (bi.get("entryNumber") or ""), "chq": chq,
                          "amount": amt})
        lines += P.STATEMENT_EXTRAS
        path = self.out_file.parent / f"palm-ridge-statement-{m:%Y-%m}.csv"
        path.write_text(P.statement_csv(opening, lines))
        self.out["bankStatement"] = {"file": rel(path), "bankAccountId": acct["id"],
                                     "opening": str(opening), "profile": P.STATEMENT_PROFILE}
        log(f"bank statement written: {rel(path)} ({len(lines)} lines)")

    def manifest(self):
        self.out.update({
            "properties": {k: v["id"] for k, v in self.props.items()},
            "units": {k: v["id"] for k, v in self.units.items()},
            "tenants": {k: v["id"] for k, v in self.renters.items()},
            "leases": {k: v["id"] for k, v in self.leases.items()},
            "vendors": {k: v["id"] for k, v in self.vendors.items()},
            "bankAccounts": {k: v["id"] for k, v in self.bank_accounts.items()},
            "users": {"admin": P.ADMIN["email"], "accountant": P.ACCOUNTANT["email"]},
        })
        self.out_file.write_text(json.dumps(self.out, indent=2, default=str))
        log(f"manifest: {rel(self.out_file)}")


def main():
    if "--plan" in sys.argv:
        for key, c in P.CONTRACTS.items():
            print(f"{key:9} {P.UNITS[c['unit']][1]:7} {P.TENANTS[c['tenant']][0]:28} {iso(c['start'])} → {iso(c['end'])} "
                  f"value {P.contract_value(c['lines']):>12,.2f}")
        return
    api_url = P.require_local_url(os.environ.get("PALM_RIDGE_API_URL"), "PALM_RIDGE_API_URL")
    if TODAY < P.EARLIEST_RUN:
        raise SystemExit(f"The plan records events up to {iso(P.EARLIEST_RUN - dt.timedelta(days=1))}; today is {iso(TODAY)}.")
    out = REPO / "tutorials" / "work" / "seed" / "palm-ridge.out.json"
    if "--out" in sys.argv:
        out = pathlib.Path(sys.argv[sys.argv.index("--out") + 1]).resolve()
    out.parent.mkdir(parents=True, exist_ok=True)
    print(f"Seeding '{P.ORG_NAME}' on {api_url}")
    s = Seed(api_url, out)
    s.org()
    s.branding()
    s.books()
    s.portfolio()
    s.banks()
    s.opening()
    s.run()
    s.statement()
    s.manifest()


if __name__ == "__main__":
    main()
