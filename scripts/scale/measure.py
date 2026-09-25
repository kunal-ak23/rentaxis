#!/usr/bin/env python3
"""Baseline timings for the daily endpoints the scale-track spec names.

Hits each endpoint --runs times (after one warm-up call) as the seeded organisation's
TENANT_ADMIN and records p50 / p95 wall time and the response size. With --count-queries
and a backend started with SCALE_HIBERNATE_STATS=true (scripts/scale/run_backend.sh), it
also sums Hibernate's per-session "JDBC statements executed" from the backend log for one
extra call of each endpoint (plain JdbcTemplate queries are not counted).

    python3 scripts/scale/measure.py --label full --out /tmp/timings.json
"""
import argparse
import datetime as dt
import json
import os
import re
import statistics
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import Api, log  # noqa: E402

TODAY = dt.date.today()


def endpoints(api):
    """(name, path, params) for every daily endpoint in the spec."""
    accounts = api.get("/api/v1/finance/accounts") or []
    # "1 account, 12 months": the busiest leaf -- a property's advance-rent account.
    leaf = next((a for a in accounts if not a.get("group") and "Advance Rent" in (a.get("nameEn") or "")), None) \
        or next(a for a in accounts if not a.get("group"))
    last12 = {"from": (TODAY - dt.timedelta(days=365)).isoformat(), "to": TODAY.isoformat()}
    return [
        ("GET /leases/paged p1", "/api/v1/leases/paged", {"page": 0, "size": 25}),
        ("GET /leases (unbounded)", "/api/v1/leases", None),
        ("GET /renters", "/api/v1/renters", None),
        ("GET /units", "/api/v1/units", None),
        ("GET /properties", "/api/v1/properties", None),
        ("GET /tickets (maintenance)", "/api/v1/tickets", None),
        ("GET /vendors", "/api/v1/vendors", None),
        ("GET /finance/payment-runs", "/api/v1/finance/payment-runs", None),
        ("GET /finance/issued-cheques", "/api/v1/finance/issued-cheques", None),
        ("GET /cheques?page=0&size=50", "/api/v1/cheques", {"page": 0, "size": 50}),
        ("GET /cheques/to-deposit", "/api/v1/cheques/to-deposit", {"page": 0, "size": 50}),
        ("GET /cheques/post-dated", "/api/v1/cheques/post-dated", None),
        ("GET /cheques/aging", "/api/v1/cheques/aging", None),
        ("GET /finance/reports/payables-aging", "/api/v1/finance/reports/payables-aging", None),
        ("GET /dashboard/summary", "/api/v1/dashboard/summary", None),
        ("GET /finance/trial-balance", "/api/v1/finance/trial-balance", None),
        (f"GET /finance/ledger 1 account 12 months ({leaf.get('code')})", "/api/v1/finance/ledger",
         {"accountIds": leaf["id"], **last12}),
        ("GET /finance/recognition/pending", "/api/v1/finance/recognition/pending", None),
        ("GET /finance/journals?page=0", "/api/v1/finance/journals", {"page": 0, "size": 25}),
    ]


def timed(api, path, params):
    t0 = time.perf_counter()
    r = api._session().get(api.base + path, headers=api.headers, params=params, timeout=600)
    ms = (time.perf_counter() - t0) * 1000
    return ms, r.status_code, len(r.content)


def jdbc_count(logfile, start_offset):
    with open(logfile, "rb") as f:
        f.seek(start_offset)
        text = f.read().decode("utf-8", "replace")
    return sum(int(n) for _, n in re.findall(r"(\d+) ns executing (\d+) JDBC statements", text))


def pct(xs, p):
    xs = sorted(xs)
    k = (len(xs) - 1) * p
    lo, hi = int(k), min(int(k) + 1, len(xs) - 1)
    return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=os.environ.get("SCALE_BACKEND", "http://localhost:8082"))
    ap.add_argument("--label", default="full")
    ap.add_argument("--state", default=None)
    ap.add_argument("--runs", type=int, default=5)
    ap.add_argument("--count-queries", action="store_true")
    ap.add_argument("--backend-log", default=None)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    st = json.load(open(a.state or os.path.expanduser(f"~/.cache/rentaxis-scale/{a.label}.json")))
    api = Api(a.base, st["users"]["TENANT_ADMIN"], "TENANT_ADMIN", st["tenantId"])
    results = []
    for name, path, params in endpoints(api):
        row = {"name": name, "path": path, "params": params}
        if a.count_queries:
            off = os.path.getsize(a.backend_log)
            ms, code, size = timed(api, path, params)
            time.sleep(1.0)   # let the session-metrics lines flush
            row.update(queries=jdbc_count(a.backend_log, off), status=code, bytes=size)
            log(f"{name}: {row['queries']} JDBC statements ({code})")
        else:
            timed(api, path, params)  # warm-up
            samples = []
            for _ in range(a.runs):
                ms, code, size = timed(api, path, params)
                samples.append(ms)
            row.update(samples_ms=[round(x) for x in samples], p50_ms=round(pct(samples, 0.5)),
                       p95_ms=round(pct(samples, 0.95)), status=code, bytes=size)
            log(f"{name}: p50 {row['p50_ms']} ms, p95 {row['p95_ms']} ms, {size / 1024:.0f} KiB ({code})")
        results.append(row)
    with open(a.out, "w") as f:
        json.dump(results, f, indent=1)


if __name__ == "__main__":
    main()
