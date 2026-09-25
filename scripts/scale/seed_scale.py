#!/usr/bin/env python3
"""Scale-test dataset for RentAxis (spec docs/superpowers/specs/2026-09-25-scale-track-design.md, "Proof").

One organisation, N units over P properties, Y years of tenancy contracts with renewals
chaining, 4-12 cheques each replayed to a realistic mix of cleared / deposited / bounced /
post-dated, month-by-month revenue recognition, tickets, vendors, purchase invoices and
payment runs. Everything goes through the public API (header auth, the way
web/walkthrough/ui-sweep.spec.ts provisions its organisation) -- no SQL, so every journal
is written by PostingService.

Run it against a throwaway database and backend (scripts/scale/run_backend.sh starts one
on :8082 against rentaxis_scale). Small first:

    python3 scripts/scale/seed_scale.py --units 300 --properties 6 --label small
    python3 scripts/scale/seed_scale.py --units 8000 --properties 60 --label full

Idempotent-ish: progress is kept in a state file (default ~/.cache/rentaxis-scale/<label>.json);
a re-run with the same --label resumes where the last one stopped. The plan is derived from
--seed, so a resumed run makes the same decisions.
"""
import argparse
import datetime as dt
import json
import os
import random
import sys
import threading
import time
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import Api, ApiError, items, log  # noqa: E402

D = dt.date
TODAY = D.today()
YEAR1_FROM = D(2023, 10, 1)          # year-1 contracts start between these two dates
YEAR1_TO = D(2024, 9, 30)
BOOKS_START = D(2023, 9, 1)

FIRST = ["Ahmed", "Fatima", "Rajesh", "Sara", "Omar", "Aisha", "Priya", "John", "Maria", "Ali", "Noura",
         "Vikram", "Layla", "Hassan", "Mei", "Carlos", "Anna", "Yusuf", "Deepa", "Khalid", "Olga", "Samir"]
LAST = ["Hassan", "Al Zaabi", "Kumar", "Mansour", "Khan", "Al Mansoori", "Sharma", "Smith", "Garcia",
        "Rahman", "Al Nuaimi", "Iyer", "Haddad", "Chen", "Silva", "Ivanova", "Qureshi", "Nair", "Al Suwaidi"]
BANKS = ["Emirates NBD", "FAB", "ADCB", "Dubai Islamic Bank", "Mashreq", "RAKBANK", "CBD", "HSBC"]
RES_TYPES = [("STUDIO", 450, 38000), ("BHK1", 800, 58000), ("BHK2", 1200, 85000), ("BHK3", 1650, 120000)]
COM_TYPES = [("OFFICE", 1500, 95000), ("RETAIL", 900, 140000)]
TICKET_CATS = ["PLUMBING", "ELECTRICAL", "HVAC", "STRUCTURAL", "PEST_CONTROL", "CLEANING", "APPLIANCE", "SECURITY", "OTHER"]


def iso(d):
    return d.isoformat()


def add_years(d, n):
    try:
        return d.replace(year=d.year + n)
    except ValueError:
        return d.replace(year=d.year + n, day=28)


class State:
    def __init__(self, path):
        self.path = path
        self.lock = threading.Lock()
        self.data = {}
        if os.path.exists(path):
            with open(path) as f:
                self.data = json.load(f)
        self._dirty = 0

    def __getitem__(self, k):
        return self.data[k]

    def get(self, k, default=None):
        return self.data.get(k, default)

    def setdefault(self, k, v):
        with self.lock:
            return self.data.setdefault(k, v)

    def put(self, k, v, flush=False):
        with self.lock:
            self.data[k] = v
            self._dirty += 1
        if flush or self._dirty > 200:
            self.save()

    def save(self):
        with self.lock:
            tmp = self.path + ".tmp"
            with open(tmp, "w") as f:
                json.dump(self.data, f)
            os.replace(tmp, self.path)
            self._dirty = 0


def pool_map(fn, work, threads, label):
    """Run fn over work with a thread pool; count failures instead of stopping."""
    done = fails = 0
    errors = defaultdict(int)
    t0 = time.time()
    with ThreadPoolExecutor(max_workers=threads) as ex:
        futs = [ex.submit(fn, w) for w in work]
        for f in as_completed(futs):
            try:
                f.result()
                done += 1
            except Exception as e:  # noqa: BLE001
                fails += 1
                errors[str(e)[:160]] += 1
            n = done + fails
            if n % 1000 == 0:
                log(f"  {label}: {n}/{len(work)} ({fails} failed, {n / max(time.time() - t0, 1e-3):.1f}/s)")
    log(f"{label}: {done} ok, {fails} failed in {time.time() - t0:.0f}s")
    for msg, c in sorted(errors.items(), key=lambda x: -x[1])[:5]:
        log(f"  x{c}: {msg}")
    return done, fails


class Seeder:
    def __init__(self, args):
        self.a = args
        self.rng = random.Random(args.seed)
        os.makedirs(os.path.dirname(args.state), exist_ok=True)
        self.st = State(args.state)
        self.base = args.base

    # ------------------------------------------------------------------ org
    def org(self):
        st = self.st
        if not st.get("tenantId"):
            su = Api(self.base).post("/api/auth/login", {"email": os.environ.get("SCALE_SUPERADMIN_EMAIL", "admin@rentaxis.com"), "password": os.environ.get("SCALE_SUPERADMIN_PASSWORD", "admin123")})  # the local DataInitializer default
            sa = Api(self.base, su["id"], su["role"])
            t = sa.post("/api/admin/tenants", {"name": f"SCALE {self.a.label}"})
            scoped = Api(self.base, su["id"], su["role"], t["id"])
            users = {}
            for role, slug in (("TENANT_ADMIN", "admin"), ("PROPERTY_MANAGER", "pm"), ("ACCOUNTANT", "acct")):
                u = scoped.post("/api/admin/users", {"name": f"Scale {slug}", "email": f"scale-{slug}-{self.a.label}@example.invalid",
                                                     "password": "Scale!12345", "role": role, "tenantId": t["id"]})
                users[role] = u["id"]
            scoped.post("/api/v1/finance/accounts/seed")
            # Commercial lettings charge VAT; posting them needs the organisation's TRN.
            sa.put(f"/api/admin/tenants/{t['id']}", {"name": f"SCALE {self.a.label}", "trn": "100123456700003"})
            st.put("tenantId", t["id"])
            st.put("users", users, flush=True)
            log(f"org {t['id']} provisioned")
        self.api = Api(self.base, st["users"]["TENANT_ADMIN"], "TENANT_ADMIN", st["tenantId"])
        fiscal = self.api.get("/api/v1/finance/fiscal-settings") or {}
        if fiscal.get("booksStartDate") != iso(BOOKS_START):
            self.api.put("/api/v1/finance/fiscal-settings", {"fiscalYearStartMonth": 1, "booksStartDate": iso(BOOKS_START)})

    # ------------------------------------------------------------ properties
    def properties(self):
        props = self.st.setdefault("properties", [])
        P = self.a.properties
        rng = random.Random(self.a.seed + 1)
        for i in range(len(props), P):
            kind = "COMMERCIAL" if i % 6 == 5 else ("MIXED" if i % 12 == 4 else "RESIDENTIAL")
            p = self.api.post("/api/v1/properties", {
                "nameEn": f"Scale {kind.title()} {i + 1:02d}", "nameAr": f"مبنى {i + 1}",
                "type": kind, "emirate": rng.choice(["DUBAI", "DUBAI", "SHARJAH", "ABU_DHABI"]),
                "address": f"Plot {100 + i}, District {i % 9}"})
            self.api.post(f"/api/v1/properties/{p['id']}/accounts/generate")
            props.append({"id": p["id"], "kind": kind})
            self.st.put("properties", props, flush=True)
        log(f"{len(props)} properties")

    # ----------------------------------------------------------------- units
    def units(self):
        units = self.st.setdefault("units", {})
        props = self.st["properties"]
        N, P = self.a.units, len(props)
        rng = random.Random(self.a.seed + 2)
        per = [N // P + (1 if i < N % P else 0) for i in range(P)]
        for pi, p in enumerate(props):
            if p["id"] in units:
                continue
            n = per[pi]
            per_floor = rng.choice([4, 6, 8, 10])
            rows = []
            for j in range(n):
                floor, pos = j // per_floor + 1, j % per_floor + 1
                if p["kind"] == "COMMERCIAL" or (p["kind"] == "MIXED" and floor <= 2):
                    t, sq, rent = rng.choice(COM_TYPES)
                else:
                    t, sq, rent = rng.choice(RES_TYPES)
                rent = int(rent * rng.uniform(0.85, 1.25) / 500) * 500
                rows.append(f"{floor:02d}-{pos:02d},{t},{int(sq * rng.uniform(0.9, 1.1))},{rent},VACANT")
            csv = "UnitNumber,Type,SizeSqft,ExpectedRent,Status\n" + "\n".join(rows) + "\n"
            self.api.call("POST", "/api/v1/units/bulk", params={"propertyId": p["id"]},
                          files={"file": ("units.csv", csv.encode(), "text/csv")})
            got = self.api.get(f"/api/v1/units/property/{p['id']}")
            units[p["id"]] = [{"id": u["id"], "no": u["unitNumber"], "type": u.get("type"),
                               "rent": float(u.get("expectedRent") or 60000)} for u in got]
            self.st.put("units", units, flush=True)
        log(f"{sum(len(v) for v in units.values())} units")

    # ------------------------------------------------------------------ plan
    def plan(self):
        """Per unit and year: NEW (new renter), RENEW, or None (vacant). Deterministic."""
        rng = random.Random(self.a.seed + 3)
        plan = []
        for p in self.st["properties"]:
            for u in self.st["units"][p["id"]]:
                commercial = u["type"] in ("OFFICE", "RETAIL")
                start = YEAR1_FROM + dt.timedelta(days=rng.randrange((YEAR1_TO - YEAR1_FROM).days + 1))
                years, prev, prev_end = [], None, None
                for k in range(self.a.years):
                    if k == 0:
                        action = "NEW" if rng.random() < 0.96 else None
                    elif prev is None:
                        action = "NEW" if rng.random() < 0.7 else None
                    else:
                        r = rng.random()
                        action = "RENEW" if r < 0.72 else ("NEW" if r < 0.97 else None)
                    if action == "RENEW":
                        s = prev_end + dt.timedelta(days=1)
                    elif action == "NEW" and prev_end is not None:
                        s = max(prev_end, add_years(start, k) - dt.timedelta(days=1)) \
                            + dt.timedelta(days=rng.randrange(3, 25))   # re-let gap after a move-out
                    else:
                        s = add_years(start, k)
                    e = add_years(s, 1) - dt.timedelta(days=1)
                    if action and s > TODAY + dt.timedelta(days=5):
                        action = None
                    years.append(None if not action else {
                        "action": action, "start": iso(s), "end": iso(e),
                        "contract": iso(s - dt.timedelta(days=rng.randrange(5, 30))),
                        "inst": rng.choices([4, 6, 12, 2], weights=[45, 25, 25, 5])[0],
                        "pct": rng.choice([0, 0, 0, 3, 5, 5, 7]),
                        "bounce": [rng.random() < 0.025 for _ in range(14)],
                    })
                    prev = action
                    if action:
                        prev_end = e
                plan.append({"prop": p["id"], "unit": u["id"], "no": u["no"], "rent": u["rent"],
                             "commercial": commercial, "years": years})
        return plan

    # --------------------------------------------------------------- renters
    def renters(self, plan):
        need = sum(1 for u in plan for y in u["years"] if y and y["action"] == "NEW")
        extra = int(need * self.a.extra_renters)
        total = need + extra
        renters = self.st.setdefault("renters", {})
        rng = random.Random(self.a.seed + 4)
        names = [(f"{rng.choice(FIRST)} {rng.choice(LAST)}", f"+9715{rng.randrange(10**7, 10**8)}") for _ in range(total)]
        todo = [i for i in range(total) if str(i) not in renters]

        def one(i):
            nm, ph = names[i]
            r = self.api.post("/api/v1/renters", {"nameEn": f"{nm} {i}", "email": f"r{i}.{self.a.label}@example.invalid",
                                                  "phone": ph, "primaryLanguage": "EN", "createPortalAccount": False})
            with self.st.lock:
                renters[str(i)] = r["id"]
        pool_map(one, todo, self.a.threads, "renters")
        self.st.save()
        return [renters[str(i)] for i in range(total) if str(i) in renters]

    # ------------------------------------------------------------- contracts
    def contracts(self, plan, renter_ids):
        leases = self.st.setdefault("leases", {})     # "unitId:k" -> {"id", "status"}
        # Renter assignment: deterministic walk over the NEW slots.
        slot = 0
        assign = {}
        for u in plan:
            for k, y in enumerate(u["years"]):
                if y and y["action"] == "NEW":
                    assign[(u["unit"], k)] = renter_ids[slot % len(renter_ids)]
                    slot += 1
        seq = {"n": 0}
        seq_lock = threading.Lock()

        def cheque_no():
            with seq_lock:
                seq["n"] += 1
                return str(100000 + seq["n"] * 16)

        for k in range(self.a.years):
            if self.st.get(f"year{k}_done"):
                continue
            log(f"== contract year {k + 1}")
            # 1. move-outs: terminate the previous year's lease where the unit turns over or goes vacant
            def move_out(u):
                prev = leases.get(f"{u['unit']}:{k - 1}")
                y = u["years"][k]
                if not prev or prev.get("status") != "POSTED" or (y and y["action"] == "RENEW"):
                    return
                end = D.fromisoformat(u["years"][k - 1]["end"])
                if end > TODAY:
                    return
                self.api.post(f"/api/v1/leases/{prev['id']}/terminate",
                              {"terminationDate": iso(end), "notes": "Scale seed: move-out at term end"})
                prev["status"] = "TERMINATED"
            if k > 0:
                pool_map(move_out, plan, self.a.threads, f"year {k + 1} move-outs")
                self.st.save()

            # 2. this year's contracts
            def one(u):
                y = u["years"][k]
                key = f"{u['unit']}:{k}"
                if not y:
                    return
                rec = leases.get(key)
                if rec and rec.get("status") == "POSTED":
                    return
                if rec is None:
                    if y["action"] == "RENEW":
                        prev = leases.get(f"{u['unit']}:{k - 1}")
                        if not prev or prev.get("status") != "POSTED":
                            return
                        body = {"contractDate": y["contract"], "startDate": y["start"], "endDate": y["end"],
                                "carryDepositForward": True}
                        if y["pct"]:
                            body["rentChange"] = {"mode": "PERCENT", "percent": y["pct"]}
                        lease = self.api.post(f"/api/v1/leases/{prev['id']}/renew", body)
                    else:
                        rent = round(u["rent"] * (1 + 0.03 * k) / 100) * 100
                        vat = u["commercial"]
                        lines = [{"chargeTypeCode": "SECURITY_DEPOSIT", "grossAmount": float(round(rent * 0.05)),
                                  "discountAmount": 0.0, "vatApplicable": False, "narration": "Security deposit"},
                                 {"chargeTypeCode": "RENT", "grossAmount": float(rent), "discountAmount": 0.0,
                                  "vatApplicable": vat, "narration": "Annual rent"}]
                        lease = self.api.post("/api/v1/leases", {
                            "unitId": u["unit"], "renterId": assign[(u["unit"], k)], "startDate": y["start"],
                            "endDate": y["end"], "contractDate": y["contract"], "agreementDate": y["contract"],
                            "gracePeriodDays": 5, "paymentTerms": y["inst"], "paymentMethod": "CHEQUE",
                            "depositPaymentMethod": "CHEQUE", "rentVatApplicable": vat, "lines": lines})
                    rec = {"id": lease["id"], "status": "DRAFT"}
                    with self.st.lock:
                        leases[key] = rec
                lid = rec["id"]
                self.api.post(f"/api/v1/leases/{lid}/cheques/generate",
                              {"installments": y["inst"], "foldDepositsAndFeesIntoFirst": False,
                               "payeeBank": BANKS[hash(u["unit"]) % len(BANKS)]})
                self.api.post(f"/api/v1/leases/{lid}/cheques/numbers", {"startingNumber": cheque_no()})
                self.api.post(f"/api/v1/leases/{lid}/post")
                rec["status"] = "POSTED"
            pool_map(one, plan, self.a.threads, f"year {k + 1} contracts")
            self.st.save()

            # 3. the cheques of this year's contracts: deposit / clear / bounce up to today
            self.cheques_for_year(plan, k)
            self.st.put(f"year{k}_done", True, flush=True)

    def cheques_for_year(self, plan, k):
        leases = self.st["leases"]
        work = [(u, leases[f"{u['unit']}:{k}"]) for u in plan
                if u["years"][k] and leases.get(f"{u['unit']}:{k}", {}).get("status") == "POSTED"]
        rows = []           # (cheque, bounce?)
        lock = threading.Lock()

        def fetch(w):
            u, rec = w
            got = self.api.get(f"/api/v1/leases/{rec['id']}/cheques")
            flags = u["years"][k]["bounce"]
            with lock:
                for i, c in enumerate(got):
                    rows.append((c, flags[i % len(flags)]))
        pool_map(fetch, work, self.a.threads, f"year {k + 1} cheque fetch")
        cutoff_clear = TODAY - dt.timedelta(days=3)
        dep = [c for c, _ in rows if c["status"] == "REGISTERED" and c.get("mode") == "PDC"
               and c.get("chequeDate") and D.fromisoformat(c["chequeDate"]) <= TODAY]
        log(f"year {k + 1}: {len(rows)} cheques, {len(dep)} due for deposit")
        chunks = [dep[i:i + 400] for i in range(0, len(dep), 400)]
        pool_map(lambda ch: self.api.post("/api/v1/cheques/deposit-batch",
                                          {"chequeIds": [c["id"] for c in ch], "useChequeDates": True}),
                 chunks, max(2, self.a.threads // 2), f"year {k + 1} deposit batches")
        bounce = {c["id"] for c, b in rows if b}
        by_date = defaultdict(list)
        to_bounce = []
        # A resumed run also finishes cheques an interrupted run deposited but never cleared.
        banked = dep + [c for c, _ in rows if c["status"] == "DEPOSITED"]
        for c in banked:
            cd = D.fromisoformat(c["chequeDate"])
            if cd > cutoff_clear:
                continue                           # still at the bank
            if c["id"] in bounce:
                to_bounce.append((c["id"], min(cd + dt.timedelta(days=3), TODAY)))
            else:
                by_date[min(cd + dt.timedelta(days=2), TODAY)].append(c["id"])
        clear_chunks = [(d_, ids[i:i + 400]) for d_, ids in by_date.items() for i in range(0, len(ids), 400)]
        pool_map(lambda x: self.api.post("/api/v1/cheques/clear-batch", {"chequeIds": x[1], "clearingDate": iso(x[0])}),
                 clear_chunks, max(2, self.a.threads // 2), f"year {k + 1} clear batches")
        pool_map(lambda x: self.api.put(f"/api/v1/cheques/{x[0]}/bounce",
                                        {"date": iso(x[1]), "failureReason": "BOUNCE", "notes": "Scale seed"}),
                 to_bounce, self.a.threads, f"year {k + 1} bounces")

    # ----------------------------------------------------------- recognition
    def allowed_through(self):
        """Recognition may run through the day before the first contract not yet posted can start."""
        done = sum(1 for k in range(self.a.years) if self.st.get(f"year{k}_done"))
        if done >= self.a.years:
            return TODAY - dt.timedelta(days=1)
        return add_years(YEAR1_FROM, done) - dt.timedelta(days=1)

    def recognition(self):
        last = self.st.get("recognisedThrough")
        m = D(2023, 10, 1)
        yesterday = TODAY - dt.timedelta(days=1)
        while True:
            nxt = D(m.year + (m.month // 12), m.month % 12 + 1, 1)
            to = min(nxt - dt.timedelta(days=1), yesterday)
            while to > self.allowed_through():
                time.sleep(5)
            if last and to <= D.fromisoformat(last):
                if to >= yesterday:
                    break
                m = nxt
                continue
            t0 = time.time()
            r = self.api.post("/api/v1/finance/recognition/run", params={"to": iso(to)}, timeout=3600)
            log(f"recognition to {to}: posted {r.get('posted')} ({time.time() - t0:.1f}s)")
            self.st.put("recognisedThrough", iso(to), flush=True)
            if to >= yesterday:
                break
            m = nxt

    # --------------------------------------------------------------- tickets
    def tickets(self, plan):
        n = self.a.tickets
        done = self.st.setdefault("tickets", 0)
        rng = random.Random(self.a.seed + 5)
        leases = self.st["leases"]
        work = []
        for i in range(n):
            u = rng.choice(plan)
            day = D(2023, 10, 1) + dt.timedelta(days=rng.randrange((TODAY - D(2023, 10, 1)).days))
            k = min(max((day - D(2023, 10, 1)).days // 365, 0), self.a.years - 1)
            lease = leases.get(f"{u['unit']}:{k}")
            status = rng.choices(["OPEN", "IN_PROGRESS", "RESOLVED"], weights=[15, 10, 75])[0]
            work.append((i, u, lease["id"] if lease and lease.get("status") != "DRAFT" else None, day,
                         rng.choice(TICKET_CATS), rng.choice(["LOW", "MEDIUM", "MEDIUM", "HIGH"]), status))
        work = work[done:]
        counter = {"n": done}
        lk = threading.Lock()

        def one(w):
            i, u, lease_id, day, cat, pri, status = w
            body = {"propertyId": u["prop"], "unitId": u["unit"], "title": f"{cat.title()} issue #{i}",
                    "description": "Scale seed ticket", "category": cat, "priority": pri, "reportedDate": iso(day)}
            if lease_id:
                body["leaseId"] = lease_id
            t = self.api.post("/api/v1/tickets", body)
            if status != "OPEN":
                self.api.put(f"/api/v1/tickets/{t['id']}/assign", {"assignTo": self.st["users"]["TENANT_ADMIN"]})
                self.api.put(f"/api/v1/tickets/{t['id']}/status", {"status": "IN_PROGRESS"})
            if status == "RESOLVED":
                self.api.put(f"/api/v1/tickets/{t['id']}/status", {"status": "RESOLVED"})
            with lk:
                counter["n"] += 1
        pool_map(one, work, self.a.threads, "tickets")
        self.st.put("tickets", counter["n"], flush=True)

    # ------------------------------------------------------ payables side
    def payables(self):
        rng = random.Random(self.a.seed + 6)
        vendors = self.st.setdefault("vendors", [])
        for i in range(len(vendors), self.a.vendors):
            v = self.api.post("/api/v1/vendors", {"nameEn": f"Scale Vendor {i + 1:03d}", "nameAr": f"مورد {i + 1}",
                                                  "contactPerson": f"Contact {i}", "phone": f"+9714{3000000 + i}",
                                                  "trn": f"1003{i:011d}", "paymentTermsDays": rng.choice([15, 30, 45, 60]),
                                                  "active": True})
            vendors.append(v["id"])
            if len(vendors) % 50 == 0:
                self.st.put("vendors", vendors, flush=True)
        self.st.put("vendors", vendors, flush=True)
        log(f"{len(vendors)} vendors")

        chart = {a["code"]: a for a in (self.api.get("/api/v1/finance/accounts") or []) if a.get("code")}
        if "D-01-001" not in chart:
            parent = chart["D-01"]
            chart["D-01-001"] = self.api.post("/api/v1/finance/accounts", {
                "code": "D-01-001", "nameEn": "Building Maintenance & AMC", "nameAr": "صيانة",
                "accountType": parent["accountType"], "accountSubType": parent["accountSubType"],
                "parentId": parent["id"], "group": False})
        expense = chart["D-01-001"]["id"]
        props = self.st["properties"]
        bank = self.st.get("bankAccounts") or {}
        for p in props[:5]:
            if p["id"] not in bank:
                accs = self.api.get(f"/api/v1/properties/{p['id']}/accounts") or []
                for a in accs:
                    if a.get("role") == "BANK":
                        bank[p["id"]] = (a.get("account") or {}).get("id") or a.get("accountId")
        self.st.put("bankAccounts", bank, flush=True)

        invoices = self.st.setdefault("invoices", [])       # [id, vendorId, docDate]
        start = D(2023, 10, 1)
        span = (TODAY - start).days
        todo = list(range(len(invoices), self.a.invoices))
        lk = threading.Lock()

        def inv(i):
            r = random.Random(self.a.seed * 1000 + i)
            vid = vendors[r.randrange(len(vendors))]
            p = props[r.randrange(len(props))]["id"]
            day = start + dt.timedelta(days=int(span * i / max(self.a.invoices, 1)))
            v = self.api.post("/api/v1/finance/vouchers", {
                "docType": "PISR", "docDate": iso(day), "vendorId": vid, "invoiceNumber": f"SC-{i:06d}",
                "supplierInvoiceDate": iso(day), "propertyId": p, "narration": f"Scale invoice {i}",
                "lines": [{"accountId": expense, "description": "Maintenance works", "amount": float(r.randrange(500, 40000)),
                           "vatRate": 5.0, "propertyId": p}]})
            try:
                self.api.post(f"/api/v1/finance/vouchers/{v['id']}/post")
            except ApiError:
                # Concurrent voucher posts can 500 (Hibernate follow-on-lock NPE in
                # VoucherService.lockForWrite); the same post succeeds when retried alone.
                with lk:
                    retry.append(v["id"])
            with lk:
                invoices.append([v["id"], vid, iso(day)])
        retry = []
        pool_map(inv, todo, self.a.threads, "purchase invoices")
        for vid in retry:
            self.api.post(f"/api/v1/finance/vouchers/{vid}/post")
        log(f"purchase invoices: {len(retry)} posts failed concurrently (500) and succeeded on a sequential retry")
        self.st.put("invoices", invoices, flush=True)

        runs = self.st.setdefault("paymentRuns", 0)
        cands = [c["item"] for c in (self.api.get("/api/v1/finance/payment-runs/candidates") or {}).get("items", [])]
        cands.sort(key=lambda c: c.get("docDate") or c.get("date") or "")
        per = max(1, int(len(cands) * 0.9) // max(self.a.payment_runs, 1))
        bank_id = next(iter(bank.values()))
        for r_i in range(runs, self.a.payment_runs):
            chunk = cands[r_i * per:(r_i + 1) * per]
            if not chunk:
                break
            last_day = max(D.fromisoformat((c.get("docDate") or c.get("date"))[:10]) for c in chunk)
            pay_day = min(last_day + dt.timedelta(days=20), TODAY)
            try:
                run = self.api.post("/api/v1/finance/payment-runs", {
                    "paymentDate": iso(pay_day), "paymentAccountId": bank_id, "method": "TRANSFER",
                    "narration": f"Scale payment run {r_i + 1}",
                    "items": [{"invoiceId": c["id"], "amount": c["open"]} for c in chunk]})
                pv = self.api.get(f"/api/v1/finance/payment-runs/{run['id']}/preview")
                self.api.post(f"/api/v1/finance/payment-runs/{run['id']}/post", {"vendors": [
                    {"vendorId": v["vendorId"], "netPayment": v["netPayment"], "advanceApplied": v.get("advanceApplied"),
                     "chequeNumber": v.get("chequeNumber"),
                     "items": [{"itemId": it["itemId"], "paid": it["paid"]} for it in v["items"]]} for v in pv["vendors"]]})
            except ApiError as e:
                log(f"payment run {r_i + 1} failed: {e}")
                if r_i == runs:
                    log(f"first candidate: {json.dumps(chunk[0])[:400]}")
                break
            self.st.put("paymentRuns", r_i + 1, flush=True)
        log(f"{self.st.get('paymentRuns')} payment runs")

    # ------------------------------------------------------------------ main
    def run(self):
        t0 = time.time()
        phases = self.a.phases.split(",")
        self.org()
        self.properties()
        self.units()
        plan = self.plan()
        n_contracts = sum(1 for u in plan for y in u["years"] if y)
        log(f"plan: {len(plan)} units, {n_contracts} contracts over {self.a.years} years")
        rids = self.renters(plan) if "contracts" in phases or "renters" in phases else []
        # Recognition runs alongside: month M as soon as every contract that can start by M is posted.
        rec = threading.Thread(target=self.recognition, daemon=True) if "recognition" in phases else None
        if rec:
            rec.start()
        if "contracts" in phases:
            self.contracts(plan, rids)
        if "tickets" in phases:
            self.tickets(plan)
        if "payables" in phases:
            self.payables()
        if rec:
            rec.join()
        self.st.save()
        log(f"done in {(time.time() - t0) / 60:.1f} min; tenant {self.st['tenantId']}, admin {self.st['users']['TENANT_ADMIN']}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", default=os.environ.get("SCALE_BACKEND", "http://localhost:8082"))
    ap.add_argument("--label", default="full")
    ap.add_argument("--units", type=int, default=8000)
    ap.add_argument("--properties", type=int, default=60)
    ap.add_argument("--years", type=int, default=3)
    ap.add_argument("--extra-renters", type=float, default=0.08, help="renters with no contract, as a share of contract renters")
    ap.add_argument("--tickets", type=int, default=5000)
    ap.add_argument("--vendors", type=int, default=200)
    ap.add_argument("--invoices", type=int, default=2000)
    ap.add_argument("--payment-runs", type=int, default=100)
    ap.add_argument("--threads", type=int, default=8)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--phases", default="contracts,recognition,tickets,payables")
    ap.add_argument("--state", default=None)
    a = ap.parse_args()
    a.state = a.state or os.path.expanduser(f"~/.cache/rentaxis-scale/{a.label}.json")
    Seeder(a).run()


if __name__ == "__main__":
    main()
