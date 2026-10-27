#!/usr/bin/env python3
"""Add a dedicated app-store reviewer renter to an existing demo organisation.

Strictly additive. The organisation (default "Miftah Sandbox") is in use by a
client, so this script never updates, re-walks or deletes anything it did not
create itself, and refuses to run rather than guess:

  - the organisation and its Tower property must exist (nothing is created at
    that level, no feature flag is touched);
  - two NEW units in the tower (REVIEWER_UNITS, default A-301 and A-302), in
    the same building as the seeded A-101 if it has one; a unit number already
    in use by anyone else stops the run;
  - one NEW renter with a NEW portal (RENTER) login on REVIEWER_EMAIL; an email
    that already has a Miftah login anywhere stops the run before anything is
    written;
  - an ACTIVE, posted contract on the first unit for this calendar year
    (deposit + four quarterly rent cheques): cheques dated more than a few days
    ago cleared, the latest past-dated one at the bank, future ones registered;
  - a second contract on the other unit, starting next month, issued for
    signature (PENDING_SIGNATURE), so the reviewer can open and accept it;
  - from the reviewer's own login: an open maintenance ticket with a reply, a
    booking of the tower's existing gym (approved), an active visitor pass and
    a meeting request about renewing, hosted by the seed's own property manager
    login (approved).

It does not run month-end recognition, the renewal scheduler or anything else
that acts on the whole organisation. A re-run resumes from
scripts/seed_store_reviewer.out.json (gitignored, mode 600) and only ever acts
on the records whose ids that file holds, so whatever the client or the store
reviewer later does with them is left as it is.

Every name, phone and visitor is fictional (phones in the seed's 555 block).

Usage:
  # Prod: super admin from web/e2e-prod/.env.local; the reviewer password is
  # typed, never echoed, and must not be the shared demo password.
  read -rs REVIEWER_PASSWORD && export REVIEWER_PASSWORD && \
    REVIEWER_EMAIL=appreview@example.com python3 scripts/seed_store_reviewer.py

  # Local dry run (local test password, console not redacted):
  PROD_BASE_URL=http://localhost:8081 PROD_SUPERADMIN_EMAIL=... \
    PROD_SUPERADMIN_PASSWORD=... python3 scripts/seed_store_reviewer.py
"""
import datetime as dt
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import seed_demo_tenant as demo  # noqa: E402  (shared client and helpers only)
from seed_demo_tenant import Api, TODAY, iso, is_local, log, page_items, phone  # noqa: E402

OUT_FILE = Path(os.environ.get(
    "REVIEWER_OUTPUT_FILE",
    str(demo.REPO_ROOT / "scripts" / "seed_store_reviewer.out.json"),
))
REVIEWER_NAME_EN = os.environ.get("REVIEWER_NAME", "Omar Khalid")
REVIEWER_NAME_AR = os.environ.get("REVIEWER_NAME_AR", "عمر خالد")
REVIEWER_PHONE = os.environ.get("REVIEWER_PHONE", phone(140))
UNIT_ACTIVE, UNIT_PENDING = (os.environ.get("REVIEWER_UNITS", "A-301,A-302").split(",") + ["", ""])[:2]
TOWER_NAME = os.environ.get("REVIEWER_PROPERTY", f"{demo.DEMO_BRAND} Residence Tower")
AMENITY_NAME = "Residents Fitness Centre"
TICKET_TITLE = "Air conditioning not cooling"
GUEST_NAME = "Nadia Farouk"


def fail(msg):
    sys.exit(f"STOPPED, nothing further written: {msg}")


def months_after(d, n):
    y, m = divmod(d.month - 1 + n, 12)
    return d.replace(year=d.year + y, month=m + 1)


def main():
    demo.load_env()
    base = os.environ.get("PROD_BASE_URL", "https://rentaxis.uaenorth.cloudapp.azure.com")
    sa_email = os.environ.get("PROD_SUPERADMIN_EMAIL")
    sa_password = os.environ.get("PROD_SUPERADMIN_PASSWORD")
    if not sa_email or not sa_password:
        fail("PROD_SUPERADMIN_EMAIL / PROD_SUPERADMIN_PASSWORD not set")
    local = is_local(base)
    email = os.environ.get("REVIEWER_EMAIL") or (
        f"reviewer@{demo.DEMO_EMAIL_DOMAIN}" if local else None)
    password = os.environ.get("REVIEWER_PASSWORD") or (demo.LOCAL_TEST_PASSWORD if local else None)
    if not email or not password:
        fail(f"REVIEWER_EMAIL and REVIEWER_PASSWORD must be set to seed {base}")
    if not local and password in (demo.LOCAL_TEST_PASSWORD, os.environ.get("DEMO_PASSWORD")):
        fail("REVIEWER_PASSWORD must be its own password, not the local or shared demo one")
    if not UNIT_ACTIVE or not UNIT_PENDING or UNIT_ACTIVE == UNIT_PENDING:
        fail("REVIEWER_UNITS must name two different units, e.g. A-301,A-302")

    # A manifest is a resume of one reviewer in one organisation. Anything else
    # (another stack, another email, a new password) is a different job.
    out = json.loads(OUT_FILE.read_text()) if OUT_FILE.exists() else {}
    if out:
        login = out.get("login") or {}
        if out.get("baseUrl") != base:
            fail(f"{OUT_FILE} belongs to {out.get('baseUrl')}; set REVIEWER_OUTPUT_FILE for {base}")
        if (login.get("email") or "").lower() != email.lower():
            fail(f"{OUT_FILE} is for {login.get('email')}, not {email}")
        if login.get("password") != password:
            fail("REVIEWER_PASSWORD differs from the one this reviewer was created with; "
                 "change a password from the web app, not by re-running this script")
    out.update({"baseUrl": base, "seededAt": dt.datetime.now().isoformat()})

    def save():
        OUT_FILE.write_text(json.dumps(out, indent=2))
        os.chmod(OUT_FILE, 0o600)

    print(f"Adding the store reviewer to '{demo.TENANT_NAME}' on {base}")

    # ── The organisation, as it already is ──────────────────────────────────
    sa = Api(base)
    sa.login(sa_email, sa_password)
    orgs = [t for t in (sa.get("/api/admin/tenants") or []) if t["name"] == demo.TENANT_NAME]
    if len(orgs) != 1:
        fail(f"expected one organisation named {demo.TENANT_NAME!r}, found {len(orgs)}")
    tenant_id = orgs[0]["id"]
    if out.get("tenantId") not in (None, tenant_id):
        fail(f"{OUT_FILE} is for organisation {out['tenantId']}, not {tenant_id}")
    out["tenantId"] = tenant_id

    # The super admin's own session pointed at the organisation, as the demo
    # seed does without a Company Admin login.
    api = Api(base)
    api.login(sa_email, sa_password)
    api.headers["X-Tenant-Id"] = str(tenant_id)
    api.headers.pop("X-User-Tenant-Id", None)

    tower = next((s["property"] for s in (api.get("/api/v1/properties") or [])
                  if isinstance(s, dict) and (s.get("property") or {}).get("nameEn") == TOWER_NAME), None)
    if not tower:
        fail(f"property {TOWER_NAME!r} not found")
    tower_units = api.get(f"/api/v1/units/property/{tower['id']}") or []
    # The new units sit where the seeded A-units do: in their building when the
    # seed made one, in none when an older seed did not. Never a client's own.
    building = next((u["building"] for u in tower_units
                     if u.get("unitNumber") == "A-101" and u.get("building")), None)

    # Back-dated cheques are posted, banked and cleared this year: the books
    # must be open from 1 January. A lock the client moved forward is theirs.
    year_start, year_end = dt.date(TODAY.year, 1, 1), dt.date(TODAY.year, 12, 31)
    fiscal = api.get("/api/v1/finance/fiscal-settings") or {}
    if (fiscal.get("booksStartDate") or "9999") > iso(year_start) or \
            (fiscal.get("booksLockedThrough") or "") >= iso(year_start):
        fail(f"books are not open from {iso(year_start)} (start {fiscal.get('booksStartDate')}, "
             f"locked through {fiscal.get('booksLockedThrough')})")

    # Every login on the platform with this email, in any organisation. One
    # that is not the login this script created means the email is someone's.
    same_email = [u for u in (sa.get("/api/admin/users") or [])
                  if (u.get("email") or "").lower() == email.lower()]
    if any(u.get("id") != out.get("userId") for u in same_email):
        fail(f"{email} already has a Miftah login; choose another REVIEWER_EMAIL")

    # ── Units: new, or the ones an earlier run of this script made ──────────
    ours = out.setdefault("units", {})

    def make_unit(number, utype, sqft, rent):
        found = next((u for u in tower_units if u.get("unitNumber") == number), None)
        if found:
            if ours.get(number) != found["id"]:
                fail(f"unit {number} already exists in {TOWER_NAME} and was not made by this "
                     f"script; pick free numbers with REVIEWER_UNITS")
            return found
        body = {"property": {"id": tower["id"]}, "unitNumber": number, "type": utype,
                "sizeSqft": sqft, "expectedRent": rent, "status": "VACANT"}
        if building:
            body["building"] = {"id": building["id"]}
        unit = api.post("/api/v1/units", json=body)
        ours[number] = unit["id"]
        save()
        log(f"unit {number} created")
        return unit

    unit_a = make_unit(UNIT_ACTIVE, "BHK2", 1210, 90000)
    unit_b = make_unit(UNIT_PENDING, "BHK3", 1640, 124000)

    # ── The reviewer: a new renter with a new portal login ──────────────────
    if out.get("renterId"):
        renter = api.get(f"/api/v1/renters/{out['renterId']}")
    else:
        renter = api.post("/api/v1/renters", json={
            "nameEn": REVIEWER_NAME_EN, "nameAr": REVIEWER_NAME_AR, "email": email,
            "phone": REVIEWER_PHONE, "primaryLanguage": "EN", "createPortalAccount": True,
        })
        out["renterId"] = renter["id"]
        out["portalAccount"] = renter.get("portalAccount")
        out["userId"] = renter.get("userId")
        save()
        log(f"reviewer renter created: {email if local else '(email in manifest)'}")
    if not out.get("passwordSet"):
        # Only a login this run's renter create invited is ours to set. The
        # email check above makes anything else unlikely; this is the backstop.
        if out.get("portalAccount") != "INVITED" or not out.get("userId") \
                or renter.get("userId") != out["userId"]:
            fail(f"the reviewer's login was not newly created ({out.get('portalAccount')}); "
                 f"its password was not set")
        # Invited with no password; give it the reviewer's. updateUser
        # overwrites every field, so the full identity is resent.
        api.put(f"/api/admin/users/{out['userId']}", json={
            "email": email, "password": password, "name": REVIEWER_NAME_EN,
            "role": "RENTER", "tenantId": tenant_id, "phoneNumber": REVIEWER_PHONE,
        })
        out["passwordSet"] = True
        log("reviewer password set")
    out["login"] = {"email": email, "password": password}
    save()

    # ── Contracts ───────────────────────────────────────────────────────────
    all_leases = api.get("/api/v1/leases") or []

    def cheque(number, date, amount, narration, posting_date):
        return {"postingDate": iso(posting_date), "chequeNumber": number, "chequeDate": iso(date),
                "payeeBank": "Mashreq", "amount": float(amount), "narration": narration, "mode": "PDC"}

    def line(code, gross):
        return {"chargeTypeCode": code, "grossAmount": float(gross), "discountAmount": 0.0,
                "narration": "", "vatApplicable": False}

    def draft_lease(key, unit, start, end, signed, dated, rent, deposit, grid):
        """The lease the manifest names, or a new one on a unit holding none."""
        if out.get(key):
            return api.get(f"/api/v1/leases/{out[key]}")
        if any(l.get("unitId") == unit["id"] for l in all_leases):
            fail(f"unit {unit.get('unitNumber')} has a lease this script did not record")
        lease = api.post("/api/v1/leases", json={
            "unitId": unit["id"], "renterId": renter["id"],
            "startDate": iso(start), "endDate": iso(end),
            "agreementDate": iso(signed), "contractDate": iso(dated),
            "gracePeriodDays": 5, "paymentTerms": 4,
            "paymentMethod": "CHEQUE", "depositPaymentMethod": "CHEQUE",
            "rentVatApplicable": False,
            "lines": [line("SECURITY_DEPOSIT", deposit), line("RENT", rent)],
        })
        out[key] = lease["id"]
        save()
        api.put(f"/api/v1/leases/{lease['id']}/cheques", json=grid)
        out[f"{key}Grid"] = True
        save()
        return lease

    def ensure_grid(key, lease, grid):
        # A run that died between creating the lease and writing its grid.
        if not out.get(f"{key}Grid") and lease.get("status") == "DRAFT":
            api.put(f"/api/v1/leases/{lease['id']}/cheques", json=grid)
            out[f"{key}Grid"] = True
            save()

    # Active: this calendar year, quarterly. 108,000 = 18,000 + 4 × 22,500.
    grid_a = [cheque("610100", year_start, 18000, "Security Deposit", year_start)]
    grid_a += [cheque(f"61010{q + 1}", months_after(year_start, 3 * q), 22500,
                      f"Rent - Quarter {q + 1}", year_start) for q in range(4)]
    lease_a = draft_lease("activeLeaseId", unit_a, year_start, year_end,
                          year_start - dt.timedelta(days=10), year_start, 90000, 18000, grid_a)
    ensure_grid("activeLeaseId", lease_a, grid_a)
    if not out.get("activePosted"):
        if lease_a["status"] in ("DRAFT", "PENDING_SIGNATURE"):
            lease_a = api.post(f"/api/v1/leases/{lease_a['id']}/post")["lease"]
            log(f"{UNIT_ACTIVE}: contract posted")
        out["activePosted"] = True
        save()

    # Walk the register by date, once. Only this lease's own rows.
    if not out.get("chequesWalked"):
        rows = sorted(api.get(f"/api/v1/leases/{lease_a['id']}/cheques") or [],
                      key=lambda r: r["chequeDate"])
        past = [r for r in rows if dt.date.fromisoformat(r["chequeDate"]) <= TODAY]
        for r in past:
            latest = r is past[-1] and r is not past[0]
            status = r.get("status") or "REGISTERED"
            cdate = dt.date.fromisoformat(r["chequeDate"])
            if status == "REGISTERED":
                r = api.put(f"/api/v1/cheques/{r['id']}/deposit",
                            json={"date": iso(min(cdate + dt.timedelta(days=1), TODAY))})
                status = r["status"]
            if status == "DEPOSITED" and not latest:
                api.put(f"/api/v1/cheques/{r['id']}/clear",
                        json={"date": iso(min(cdate + dt.timedelta(days=4), TODAY))})
        out["chequesWalked"] = True
        save()
    statuses = [r["status"] for r in api.get(f"/api/v1/leases/{lease_a['id']}/cheques") or []]
    log(f"{UNIT_ACTIVE}: cheques " + ", ".join(f"{statuses.count(s)} {s.lower()}"
                                               for s in sorted(set(statuses))))

    # Pending signature: a second flat from next month, issued for acceptance.
    # 154,000 = 30,000 + 4 × 31,000.
    start_b = months_after(TODAY.replace(day=1), 1)
    end_b = months_after(start_b, 12) - dt.timedelta(days=1)
    grid_b = [cheque("620200", start_b, 30000, "Security Deposit", TODAY)]
    grid_b += [cheque(f"62020{q + 1}", months_after(start_b, 3 * q), 31000,
                      f"Rent - Quarter {q + 1}", TODAY) for q in range(4)]
    lease_b = draft_lease("pendingLeaseId", unit_b, start_b, end_b, TODAY, TODAY, 124000, 30000, grid_b)
    ensure_grid("pendingLeaseId", lease_b, grid_b)
    if not out.get("pendingIssued"):
        if lease_b["status"] == "DRAFT":
            api.post(f"/api/v1/leases/{lease_b['id']}/generate-contract")
            log(f"{UNIT_PENDING}: contract issued for signature")
        out["pendingIssued"] = True
        save()

    # ── The reviewer's own activity, from their login ───────────────────────
    # Each record is created once and then only ever looked up by the id the
    # manifest holds: what the store reviewer does with it is theirs.
    me = Api(base)
    me.login(email, password)

    if not out.get("ticketId"):
        ticket = me.post("/api/v1/tickets", json={
            "propertyId": tower["id"], "unitId": unit_a["id"], "leaseId": lease_a["id"],
            "title": TICKET_TITLE,
            "description": "The living-room AC runs but the air is warm.",
            "category": "HVAC", "priority": "MEDIUM",
        })
        out["ticketId"] = ticket["id"]
        save()
    if not out.get("ticketReplied"):
        me.post(f"/api/v1/tickets/{out['ticketId']}/replies",
                json={"message": "Any weekday after 4 PM works for a visit."})
        out["ticketReplied"] = True
        save()
    log("maintenance ticket with a reply ready")

    if not out.get("bookingId"):
        amenity = next((a for a in page_items(api.get(
                            f"/api/v1/amenities?propertyId={tower['id']}&size=100"))
                        if a.get("nameEn") == AMENITY_NAME and a.get("active", True)), None)
        if not amenity:
            log(f"note: no active {AMENITY_NAME!r} in {TOWER_NAME}; booking skipped")
        else:
            try:
                booking = me.post("/api/v1/bookings", json={
                    "resourceType": "AMENITY", "resourceId": amenity["id"], "unitId": unit_a["id"],
                    "preferredDate": iso(TODAY + dt.timedelta(days=5)), "note": "Evening workout",
                })
                out["bookingId"] = booking["id"]
                save()
            except RuntimeError as e:
                log(f"note: gym booking not created ({str(e)[:160]})")
    if out.get("bookingId") and not out.get("bookingApproved"):
        booking = next((b for b in (me.get("/api/v1/bookings/my") or [])
                        if b.get("id") == out["bookingId"]), {})
        if booking.get("status") == "PENDING":
            api.post(f"/api/v1/bookings/{out['bookingId']}/approve", json={"adminNote": "Approved"})
        out["bookingApproved"] = True
        save()
        log("gym booking approved")

    if not out.get("gatePassId"):
        now = dt.datetime.now(dt.UTC)
        gate_pass = me.post("/api/v1/gatepass", json={
            "unitId": unit_a["id"], "guestName": GUEST_NAME, "guestPhone": phone(141),
            "purpose": "Family visit", "passType": "RECURRING",
            "validFrom": (now - dt.timedelta(minutes=5)).isoformat(),
            "validTo": (now + dt.timedelta(days=60)).isoformat(),
        })
        out["gatePassId"] = gate_pass["id"]
        save()
    if not out.get("gatePassApproved"):
        gate_pass = next((p for p in (me.get("/api/v1/gatepass/mine") or [])
                          if p.get("id") == out["gatePassId"]), {})
        if gate_pass.get("status") == "PENDING_APPROVAL":
            api.post(f"/api/v1/gatepass/{out['gatePassId']}/approval", json={"approved": True})
        out["gatePassApproved"] = True
        save()
    log("visitor pass ready")

    # The host is the seed's own (fictional) property manager, never one of the
    # client's: a host is notified of the request.
    title = f"Contract renewal — {REVIEWER_NAME_EN} ({UNIT_ACTIVE})"
    host_email = f"manager@{demo.DEMO_EMAIL_DOMAIN}".lower()
    host = next((u for u in (sa.get("/api/admin/users") or [])
                 if str(u.get("tenantId")) == str(tenant_id)
                 and (u.get("email") or "").lower() == host_email), None)
    if not out.get("meetingId"):
        if not host:
            log(f"note: no {host_email} login in the organisation; meeting skipped")
        else:
            # Dubai wall-clock hours, sent as UTC. The host may already be booked
            # at a slot; only a taken slot moves on to the next one.
            error = None
            for days, hour in ((4, 11), (4, 14), (5, 11), (5, 14), (6, 11), (6, 14)):
                slot = dt.datetime.combine(TODAY + dt.timedelta(days=days), dt.time(hour, 0)) \
                    - dt.timedelta(hours=4)
                try:
                    meeting = me.post("/api/v1/meetings", json={
                        "type": "OFFICE_VISIT", "purpose": "LEASE_RENEWAL", "title": title,
                        "notes": "I would like to discuss renewing for another year.",
                        "slotStart": slot.strftime("%Y-%m-%dT%H:%M:%SZ"), "hostUserId": host["id"],
                        "leaseId": lease_a["id"], "propertyId": tower["id"], "unitId": unit_a["id"],
                    })
                    out["meetingId"] = meeting["id"]
                    save()
                    break
                except RuntimeError as e:
                    error = e
                    if "slotTaken" not in str(e):
                        break
            if not out.get("meetingId"):
                log(f"note: meeting not created ({str(error)[:160]})")
    if out.get("meetingId") and not out.get("meetingApproved"):
        mine = me.get("/api/v1/meetings/my?perspective=requester&page=0&size=100") or {}
        mine = mine.get("content", []) if isinstance(mine, dict) else mine
        meeting = next((m for m in mine if m.get("id") == out["meetingId"]), {})
        if meeting.get("status") == "REQUESTED":
            api.put(f"/api/v1/meetings/{out['meetingId']}/approve")
        out["meetingApproved"] = True
        save()
        log("renewal meeting approved")

    save()
    print(f"\nDone. Reviewer login and IDs written to {OUT_FILE}")
    if local:
        print(f"  Reviewer : {email} / {password}")
    else:
        print("  Credentials redacted from console output.")


if __name__ == "__main__":
    main()
