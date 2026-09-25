#!/usr/bin/env python3
"""Call every parameter-free-or-guessable GET endpoint and report 5xx (scale PR A, OSIV off).

Parses the controllers for @GetMapping paths, fills path variables with ids sampled from
the seeded database by variable name, and calls each one as the given roles. A 5xx is
printed with the endpoint; the backend log says why (grep LazyInitializationException).

    python3 scripts/scale/crawl_gets.py --roles TENANT_ADMIN,PROPERTY_MANAGER,ACCOUNTANT,ANONYMOUS
    python3 scripts/scale/crawl_gets.py --roles RENTER,SECURITY_GUARD --user RENTER=<user id> --user SECURITY_GUARD=<user id>

ANONYMOUS calls with no identity headers (the public /public/** and marketplace routes).
The in-repo safety net is OsivOffRouteSweepIT, which seeds every screen's rows and sweeps
every GET route for seven roles in CI; this script is the same idea against a seeded
scale database.
"""
import argparse
import glob
import json
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import Api  # noqa: E402

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
CTRL = os.path.join(ROOT, "backend/src/main/java/com/datagami/rentaxis/api")

# path-variable name (or controller-specific {id}) -> table to sample
TABLES = {
    "leaseId": "leases", "renterId": "renters", "unitId": "units", "propertyId": "properties",
    "chequeId": "cheques", "vendorId": "vendors", "accountId": "accounts", "buildingId": "buildings",
    "ticketId": "maintenance_tickets", "userId": "users", "journalId": "journal_entries",
    "entryId": "journal_entries", "voucherId": "vouchers", "runId": "payment_runs", "invoiceId": "vouchers",
}
ID_BY_CONTROLLER = {
    "Lease": "leases", "Renter": "renters", "Unit": "units", "Property": "properties", "Cheque": "cheques",
    "Vendor": "vendors", "Account": "accounts", "Building": "buildings", "MaintenanceTicket": "maintenance_tickets",
    "Ticket": "maintenance_tickets", "Journal": "journal_entries", "Voucher": "vouchers", "PaymentRun": "payment_runs",
    "BankAccount": "bank_accounts", "Staff": "staff", "User": "users",
}


def sample(db, table, tenant):
    try:
        out = subprocess.run(["psql", "-h", "127.0.0.1", "-U", "postgres", "-d", db, "-Atc",
                              f"select id from {table} where tenant_id = '{tenant}' limit 1"],
                             capture_output=True, text=True, timeout=20).stdout.strip()
        return out or None
    except Exception:
        return None


def endpoints():
    out = []
    for f in sorted(glob.glob(os.path.join(CTRL, "*.java"))):
        src = open(f).read()
        m = re.search(r'@RequestMapping\(\s*(?:value\s*=\s*|path\s*=\s*)?"([^"]*)"', src)
        base = m.group(1) if m else ""
        name = os.path.basename(f)[:-len("Controller.java")]
        for g in re.finditer(r'@GetMapping(?:\(\s*(?:value\s*=\s*|path\s*=\s*)?(?:\{\s*)?"([^"]*)"[^)]*\))?', src):
            out.append((name, base + (g.group(1) or "")))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8082")
    ap.add_argument("--db", default="rentaxis_scale")
    ap.add_argument("--roles", default="TENANT_ADMIN")
    ap.add_argument("--state", default=os.path.expanduser("~/.cache/rentaxis-scale/full.json"))
    ap.add_argument("--skip", default="/leases$,/tickets$,/maintenance-tickets$")
    ap.add_argument("--user", action="append", default=[], help="ROLE=userId for a role the state file has no user for")
    a = ap.parse_args()
    st = json.load(open(a.state))
    tenant = st["tenantId"]
    skip = [re.compile(x) for x in a.skip.split(",") if x]
    ids = {}
    bad = 0
    counts = {}
    users = dict(st["users"])
    users.update(dict(u.split("=", 1) for u in a.user))
    for role in a.roles.split(","):
        api = Api(a.base) if role == "ANONYMOUS" else Api(a.base, users[role], role, tenant)
        for ctrl, path in endpoints():
            if any(s.search(path) for s in skip):
                continue
            url = path
            ok = True
            for var in re.findall(r"\{([^}:]+)(?::[^}]*)?\}", path):
                table = TABLES.get(var) or (ID_BY_CONTROLLER.get(ctrl) if var == "id" else None)
                if not table:
                    ok = False
                    break
                if table not in ids:
                    ids[table] = sample(a.db, table, tenant)
                if not ids[table]:
                    ok = False
                    break
                url = re.sub(r"\{" + var + r"(?::[^}]*)?\}", ids[table], url)
            if not ok:
                continue
            try:
                r = api._session().get(api.base + url, headers=api.headers, timeout=300)
                code = r.status_code
            except Exception as e:  # noqa: BLE001
                code = f"ERR {e.__class__.__name__}"
            counts[code] = counts.get(code, 0) + 1
            if not isinstance(code, int) or code >= 500:
                bad += 1
                print(f"{role} {code} GET {url}", flush=True)
    print(f"done: {bad} failures; status counts {counts}", flush=True)


if __name__ == "__main__":
    main()
