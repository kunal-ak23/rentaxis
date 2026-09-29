"""Palm Ridge Properties: the fictional accounting org the accounting tutorial track records on.

Pure data and pure helpers, no network. `seed_palm_ridge.py` drives the API from this plan, and
`test_palm_ridge_plan.py` pins the arithmetic the narrations quote (day rates, monthly
recognition, VAT, termination figures), so a narration and the seeded ledger cannot drift apart
without a failing test.

Everything here is invented: the organisation, its people, its suppliers, its TRNs and its bank
details. Emails use the reserved `palmridge.example` domain.

The calendar is pinned (not relative to "today"), so the amounts and month names the narrations
read out stay the same whenever the seed runs. The seed refuses to run before `EARLIEST_RUN`,
because it records events up to that date as having happened.
"""
from __future__ import annotations

import csv
import datetime as dt
import io
import ipaddress
from decimal import Decimal, ROUND_HALF_UP
from urllib.parse import urlparse

D = dt.date

# ── Organisation ────────────────────────────────────────────────────────────────
ORG_NAME = "Palm Ridge Properties"
ORG_NAME_AR = "بالم ريدج العقارية"
EMAIL_DOMAIN = "palmridge.example"
ORG_ADDRESS = "Office 902, Ridge Point, Al Khail Road, Al Quoz, Dubai, United Arab Emirates, P.O. Box 00000"
ORG_PHONE = "+971 4 000 0900"
ORG_TRN = "100987654300003"
ADMIN = {"name": "Karim Saleh", "email": f"admin@{EMAIL_DOMAIN}", "role": "TENANT_ADMIN", "phone": "+971500009601"}
ACCOUNTANT = {"name": "Rania Khoury", "email": f"accounts@{EMAIL_DOMAIN}", "role": "ACCOUNTANT", "phone": "+971500009602"}

# ── Calendar (pinned) ───────────────────────────────────────────────────────────
BOOKS_START = D(2025, 9, 1)              # cut-over: the first day Miftah keeps the books
OPENING_DATE = BOOKS_START - dt.timedelta(days=1)   # opening balances are "as at" this day
RECOGNISED_THROUGH = D(2026, 8, 31)      # the seed runs month-end recognition to here; September is left for tutorial 36
TAX_POINTS_THROUGH = D(2026, 8, 31)      # VAT tax points posted to here; the Q3 2026 return stays open for tutorial 41
VAT_QUARTERS_FILED = [D(2025, 7, 1), D(2025, 10, 1), D(2026, 1, 1), D(2026, 4, 1)]   # quarter starts filed
FISCAL_YEAR_CLOSED = 2025                # FY 2025 (Sep–Dec: the books started in September) is closed by the seed
LOCKED_THROUGH = D(2026, 6, 30)          # the period lock after the Q2 2026 VAT return
BANK_STATEMENT_MONTH = D(2026, 8, 1)     # the statement the bank reconciliation tutorial imports (written, not imported)
EARLIEST_RUN = D(2026, 9, 22)            # the last dated event in the plan is 2026-09-21

VAT_RATE = Decimal("0.05")
CENT = Decimal("0.01")


def money(x) -> Decimal:
    """HALF_UP to the fils, the way the server rounds (VoucherMath / LeaseVat / ProrationEngine)."""
    return Decimal(str(x)).quantize(CENT, rounding=ROUND_HALF_UP)


def vat_of(net) -> Decimal:
    return money(Decimal(str(net)) * VAT_RATE)


def days_inclusive(start: dt.date, end: dt.date) -> int:
    if end < start:
        raise ValueError(f"{end} is before {start}")
    return (end - start).days + 1


def day_rate(amount, start: dt.date, end: dt.date) -> Decimal:
    """ProrationEngine.dayRate: amount ÷ inclusive days, 6 dp HALF_UP."""
    return (Decimal(str(amount)) / Decimal(days_inclusive(start, end))).quantize(Decimal("0.000001"), rounding=ROUND_HALF_UP)


def month_end(d: dt.date) -> dt.date:
    nxt = D(d.year + (d.month == 12), d.month % 12 + 1, 1)
    return nxt - dt.timedelta(days=1)


def add_months(d: dt.date, n: int) -> dt.date:
    y, m = divmod(d.month - 1 + n, 12)
    y += d.year
    m += 1
    return D(y, m, min(d.day, month_end(D(y, m, 1)).day))


def recognition_slices(amount, start: dt.date, end: dt.date, free=()):
    """The per-day recognition schedule (spec §8.2, rent-free §4b): [(period_start, period_end, days, amount)].

    Without free windows this is ProrationEngine.slice: every calendar-month slice is
    round(day rate × days, 2) and the last absorbs the remainder. With free windows the rate is
    net ÷ charged days, a wholly free month produces no row, and only the final charged slice
    absorbs rounding (RecognitionService.build + ProrationEngine.slice(amount, from, to, rate)).
    """
    amount = Decimal(str(amount))
    free = sorted(free)
    charged = []                       # charged sub-windows
    cursor = start
    for f_from, f_to in free:
        if f_from > cursor:
            charged.append((cursor, f_from - dt.timedelta(days=1)))
        cursor = max(cursor, f_to + dt.timedelta(days=1))
    if cursor <= end:
        charged.append((cursor, end))
    charged_days = sum(days_inclusive(a, b) for a, b in charged)
    rate = (amount / Decimal(charged_days)).quantize(Decimal("0.000001"), rounding=ROUND_HALF_UP)
    out = []
    running = Decimal(0)
    for i, (w_from, w_to) in enumerate(charged):
        c = w_from
        while c <= w_to:
            p_end = min(month_end(c), w_to)
            days = days_inclusive(c, p_end)
            last = (i == len(charged) - 1) and p_end == w_to
            amt = (amount - running).quantize(CENT) if last else money(rate * days)
            out.append((c, p_end, days, amt))
            running += amt
            c = p_end + dt.timedelta(days=1)
    return out


def earned_through(amount, start: dt.date, end: dt.date, as_of: dt.date) -> Decimal:
    """ProrationEngine.earnedThrough: round(day rate × days from start to as_of, 2)."""
    if as_of < start:
        return Decimal("0.00")
    if as_of >= end:
        return money(amount)
    return money(day_rate(amount, start, end) * days_inclusive(start, as_of))


def rent_free_concession(gross, start: dt.date, end: dt.date, f_from: dt.date, f_to: dt.date) -> Decimal:
    """Spec §4b: round(G × free days ÷ term days, 2)."""
    return money(Decimal(str(gross)) * days_inclusive(f_from, f_to) / days_inclusive(start, end))


# ── Local-only guard ────────────────────────────────────────────────────────────
RESERVED_PORTS = {3000, 3001, 3002, 3003, 8081, 8082, 8083}   # the user's stacks, break-test, sim (recording-stack.sh)


def require_local_url(url: str, what: str) -> str:
    """Refuse anything but an explicit http(s) URL on this machine, on a port no other stack owns."""
    if not url:
        raise SystemExit(f"{what} is not set. This seed never falls back to a default or to production.")
    u = urlparse(url)
    if u.scheme not in ("http", "https") or not u.hostname:
        raise SystemExit(f"{what}={url!r} is not an http(s) URL.")
    host = u.hostname
    local = host == "localhost" or host.endswith(".localhost")
    if not local:
        try:
            local = ipaddress.ip_address(host).is_loopback
        except ValueError:
            local = False
    if not local:
        raise SystemExit(f"Refusing {what}={url!r}: this seed runs against a local stack only.")
    port = u.port or (443 if u.scheme == "https" else 80)
    if port in RESERVED_PORTS:
        raise SystemExit(f"Refusing {what}={url!r}: port {port} belongs to another local stack.")
    return url.rstrip("/")


# ── Portfolio ───────────────────────────────────────────────────────────────────
PROPERTIES = {
    "residences": {"nameEn": "Palm Ridge Residences", "nameAr": "مساكن بالم ريدج", "type": "RESIDENTIAL",
                   "address": "Plot 0000, Al Furjan, Dubai"},
    "business": {"nameEn": "Palm Ridge Business Centre", "nameAr": "مركز بالم ريدج للأعمال", "type": "COMMERCIAL",
                 "address": "Plot 0000, Business Bay, Dubai"},
}

# unit key -> (property key, unit number, type, sqft, expected rent)
UNITS = {
    "r101": ("residences", "R-101", "BHK2", 1150, 72000),
    "r102": ("residences", "R-102", "BHK1", 800, 60000),
    "r103": ("residences", "R-103", "BHK2", 1250, 84000),
    "r104": ("residences", "R-104", "BHK3", 1600, 90000),
    "r201": ("residences", "R-201", "BHK1", 780, 66000),
    "r202": ("residences", "R-202", "BHK2", 1180, 78000),
    "r203": ("residences", "R-203", "BHK1", 820, 70000),
    "r204": ("residences", "R-204", "BHK1", 760, 64000),
    "bc301": ("business", "BC-301", "OFFICE", 2100, 120000),
    "bc302": ("business", "BC-302", "OFFICE", 1650, 96000),
    "bcg01": ("business", "BC-G01", "RETAIL", 1400, 150000),
}

# tenant key -> (English name, Arabic name, phone). Emails are <key>@palmridge.example.
TENANTS = {
    "omar": ("Omar Haddad", "عمر حداد", "+971500009611"),
    "layla": ("Layla Nasser", "ليلى ناصر", "+971500009612"),
    "arjun": ("Arjun Mehta", "أرجون ميهتا", "+971500009613"),
    "daniel": ("Daniel Brooks", "دانيال بروكس", "+971500009614"),
    "meridian": ("Meridian Logistics FZ-LLC", "ميريديان للخدمات اللوجستية م.م.ح", "+971500009615"),
    "sahara": ("Sahara Design Studio LLC", "استوديو صحارى للتصميم ذ.م.م", "+971500009616"),
    "hana": ("Hana Yoshida", "هانا يوشيدا", "+971500009617"),
    "mohammed": ("Mohammed Al Rashid", "محمد الراشد", "+971500009618"),
    "grace": ("Grace Okafor", "غريس أوكافور", "+971500009619"),
    "priya": ("Priya Raman", "بريا رامان", "+971500009620"),
    "noor": ("Noor Pharmacy LLC", "صيدلية نور ذ.م.م", "+971500009621"),
}

BANKS = ["Emirates NBD", "FAB", "Dubai Islamic Bank", "ADCB", "Mashreq"]


def cheque(no, date, amount, narration, bank="Emirates NBD", mode="PDC", vat=0, kind="RENT"):
    """One grid row. `vat` is the output VAT inside `amount`, sent explicitly: left to the server's
    pro-rata default, a deposit row sharing a VAT contract's grid would be given part of the VAT."""
    return {"chequeNumber": no, "chequeDate": date, "amount": Decimal(str(amount)), "narration": narration,
            "payeeBank": bank, "mode": mode, "vatAmount": Decimal(str(vat)), "rowKind": kind}


def deposit_row(no, date, amount, bank="Emirates NBD"):
    return cheque(no, date, amount, "Security Deposit", bank, vat=0, kind="DEPOSIT")


def rent_rows(first_no: int, dates, amount, bank, vat=0):
    ords = {1: "1st", 2: "2nd", 3: "3rd"}
    return [cheque(str(first_no + i), d, amount, f"Rent - {ords.get(i + 1, f'{i + 1}th')} Installment", bank, vat=vat)
            for i, d in enumerate(dates)]


# Each contract: lines (charge code, gross, vat) and either an explicit cheque grid ("cheques") or a
# server-generated one ("generate"), then its history. Dates of events are absolute.
#
# Cheque fates, keyed by cheque number, or "on:<ISO date>" for a server-generated grid: CLEAR (banked
# the day after its date, cleared 3 days after it), CLEAR_LATE (banked late, cleared on the given
# date), DEPOSIT (banked on the given date, the bank has not confirmed), BOUNCE (banked the day after,
# returned on the given date), HOLD (registered, in the drawer). Rows not named are CLEAR when dated on
# or before CLEAR_UNTIL, else HOLD. Rows a termination returned are left alone.
CLEAR_UNTIL = D(2026, 9, 5)

CONTRACTS = {
    # The overview's worked example: a clean year, every cheque cleared, ends 30 Sep 2026 (renewal candidate, tutorial 43).
    "omar": {
        "unit": "r101", "tenant": "omar", "contract": D(2025, 9, 20), "start": D(2025, 10, 1), "end": D(2026, 9, 30),
        "lines": [("SECURITY_DEPOSIT", 5000, False), ("RENT", 72000, False)],
        "cheques": [deposit_row("110100", D(2025, 10, 1), 5000)]
                   + rent_rows(110101, [D(2025, 10, 1), D(2026, 1, 1), D(2026, 4, 1), D(2026, 7, 1)], 18000, "Emirates NBD"),
    },
    # Returned cheque replaced by two, cheque-return penalty approved and collected (tutorials 38, 40, 14).
    "layla": {
        "unit": "r102", "tenant": "layla", "contract": D(2025, 11, 5), "start": D(2025, 11, 15), "end": D(2026, 11, 14),
        "lines": [("SECURITY_DEPOSIT", 3000, False), ("RENT", 60000, False)],
        "cheques": [deposit_row("220100", D(2025, 11, 15), 3000, "FAB")]
                   + rent_rows(220101, [D(2025, 11, 15), D(2026, 2, 15), D(2026, 5, 15), D(2026, 8, 15)], 15000, "FAB"),
        "fates": {"220102": ("BOUNCE", D(2026, 2, 19))},
        "replace": {"220102": {"date": D(2026, 3, 1), "rows": [
            cheque("220190", D(2026, 3, 1), 7500, "Replacement 1 of 2 - returned cheque 220102", "FAB"),
            cheque("220191", D(2026, 4, 1), 7500, "Replacement 2 of 2 - returned cheque 220102", "FAB")]}},
        "penalties": [{"reason": "CHEQUE_RETURN", "amount": 500, "cheque": "220102", "date": D(2026, 2, 20),
                       "description": "Returned cheque 220102", "approve": D(2026, 2, 25), "collect": D(2026, 3, 1)}],
    },
    # Returned cheque settled in cash at the office, and the cash later banked by JV (tutorials 40, 18).
    "arjun": {
        "unit": "r103", "tenant": "arjun", "contract": D(2025, 11, 20), "start": D(2025, 12, 1), "end": D(2026, 11, 30),
        "lines": [("SECURITY_DEPOSIT", 4200, False), ("RENT", 84000, False)],
        "cheques": [deposit_row("330100", D(2025, 12, 1), 4200, "Dubai Islamic Bank")]
                   + rent_rows(330101, [D(2025, 12, 1), D(2026, 2, 1), D(2026, 4, 1), D(2026, 6, 1), D(2026, 8, 1),
                                        D(2026, 10, 1)], 14000, "Dubai Islamic Bank"),
        "fates": {"330103": ("BOUNCE", D(2026, 4, 5))},
        "replace": {"330103": {"date": D(2026, 4, 10), "rows": [
            cheque(None, D(2026, 4, 10), 14000, "Cash in place of returned cheque 330103", mode="CASH")],
            "receive": D(2026, 4, 10)}},
        "jv_bank_cash": {"date": D(2026, 4, 12), "amount": 14000,
                         "narration": "Cash from R-103 (returned cheque 330103) banked"},
    },
    # Returned cheque, tenant left: terminated, settled against the deposit, written off, part recovered (tutorials 40, 42).
    "daniel": {
        "unit": "r104", "tenant": "daniel", "contract": D(2025, 9, 1), "start": D(2025, 9, 1), "end": D(2026, 8, 31),
        "lines": [("SECURITY_DEPOSIT", 4500, False), ("RENT", 90000, False)],
        "cheques": [deposit_row("440100", D(2025, 9, 1), 4500, "ADCB")]
                   + rent_rows(440101, [D(2025, 9, 1), D(2025, 12, 1), D(2026, 3, 1), D(2026, 6, 1)], 22500, "ADCB"),
        "fates": {"440102": ("BOUNCE", D(2025, 12, 5))},
        "terminate": {"date": D(2026, 1, 31), "notes": "Tenant vacated without notice after a returned cheque"},
        "settle": {"date": D(2026, 2, 10), "deductions": [("CLEANING", "Deep cleaning after move-out", 600)]},
        "write_off": {"date": D(2026, 3, 31), "reason": "Tenant left the UAE; collection agency returned the file"},
        "recover": {"date": D(2026, 7, 15), "amount": 5000, "note": "Part payment received through the collection agency"},
    },
    # Commercial, 5 % VAT per instalment, every cheque cleared (tutorials 38, 41). Same figures as spec 2026-09-24 §1.
    "meridian": {
        "unit": "bc301", "tenant": "meridian", "contract": D(2025, 9, 22), "start": D(2025, 10, 1), "end": D(2026, 9, 30),
        "vat": True,
        "lines": [("SECURITY_DEPOSIT", 10000, False), ("RENT", 120000, True)],
        "cheques": [deposit_row("550100", D(2025, 10, 1), 10000, "Mashreq")]
                   + rent_rows(550101, [D(2025, 10, 1), D(2026, 1, 1), D(2026, 4, 1), D(2026, 7, 1)], 31500, "Mashreq", vat=1500),
    },
    # Commercial with a rent-free fit-out month (tutorial 43). Grid generated by the server.
    "sahara": {
        "unit": "bc302", "tenant": "sahara", "contract": D(2026, 1, 20), "start": D(2026, 2, 1), "end": D(2027, 1, 31),
        "vat": True,
        "lines": [("SECURITY_DEPOSIT", 9600, False), ("RENT", 96000, True)],
        "rent_free": [(D(2026, 2, 1), D(2026, 2, 28), "Fit-out period")],
        "generate": {"installments": 4, "firstDueDate": D(2026, 3, 1), "payeeBank": "Emirates NBD", "startingNumber": "660100"},
    },
    # Monthly cheques: cleared to August, September banked and unconfirmed, the rest in the drawer (tutorials 35, 45).
    "hana": {
        "unit": "r201", "tenant": "hana", "contract": D(2025, 12, 20), "start": D(2026, 1, 1), "end": D(2026, 12, 31),
        "lines": [("SECURITY_DEPOSIT", 3300, False), ("RENT", 66000, False)],
        "cheques": [deposit_row("770100", D(2026, 1, 1), 3300, "FAB")]
                   + rent_rows(770101, [D(2026, m, 1) for m in range(1, 13)], 5500, "FAB"),
        "fates": {"770109": ("DEPOSIT", D(2026, 9, 2))},
        "ticket": {"title": "Cracked balcony glass panel", "category": "STRUCTURAL", "reported": D(2026, 7, 14),
                   "bill": {"vendor": "coastline", "invoice": "CFS-2607-231", "date": D(2026, 7, 20),
                            "amount": 800, "description": "Replace cracked balcony glass panel, R-201"},
                   "recharge": {"amount": 800, "description": "Balcony glass replacement (tenant damage)"}},
    },
    # Parking fee earned over the term; late-payment penalty proposed and left for finance (tutorials 14, 43 transfer).
    "mohammed": {
        "unit": "r202", "tenant": "mohammed", "contract": D(2026, 2, 20), "start": D(2026, 3, 1), "end": D(2027, 2, 28),
        "lines": [("SECURITY_DEPOSIT", 3900, False), ("RENT", 78000, False), ("PARKING_FEE", 1200, False)],
        "generate": {"installments": 4, "firstDueDate": D(2026, 3, 1), "payeeBank": "Dubai Islamic Bank",
                     "startingNumber": "880100"},
        # The June instalment is banked late and clears after the 5-day grace, so a late-payment
        # penalty is proposed (by the late-clear rule, or by hand if the rule stays quiet) and left
        # PROPOSED for tutorial 14 to approve or waive.
        # The September instalment stays in the drawer, matured and unbanked, so tutorial 35 has a
        # cheque to take to the bank on camera.
        "fates": {"on:2026-06-01": ("CLEAR_LATE", D(2026, 6, 9)), "on:2026-09-01": ("HOLD", D(2026, 9, 1))},
        "penalties": [{"reason": "LATE_PAYMENT", "amount": 250, "cheque_on": D(2026, 6, 1), "date": D(2026, 6, 9),
                       "description": "June instalment cleared after the grace period", "leave_proposed": True}],
    },
    # Early termination with a deposit refund, paid by payment voucher (tutorial 42).
    "grace": {
        "unit": "r203", "tenant": "grace", "contract": D(2025, 10, 5), "start": D(2025, 10, 15), "end": D(2026, 10, 14),
        "lines": [("SECURITY_DEPOSIT", 3500, False), ("RENT", 70000, False)],
        "cheques": [deposit_row("990100", D(2025, 10, 15), 3500, "Emirates NBD")]
                   + rent_rows(990101, [D(2025, 10, 15), D(2026, 1, 15), D(2026, 4, 15), D(2026, 7, 15)], 17500,
                               "Emirates NBD"),
        "terminate": {"date": D(2026, 5, 31), "notes": "Relocating for work; notice given 1 May"},
        "settle": {"date": D(2026, 6, 5), "deductions": [("CLEANING", "Move-out cleaning", 750)]},
        "refund": {"date": D(2026, 6, 10), "reference": "TRF-R203-REFUND"},
    },
    # Renewed on 20 Aug 2026 with a 5 % increase and a renewal fee; deposit carried forward (tutorial 43).
    "priya": {
        "unit": "r204", "tenant": "priya", "contract": D(2025, 9, 1), "start": D(2025, 9, 1), "end": D(2026, 8, 31),
        "lines": [("SECURITY_DEPOSIT", 3200, False), ("RENT", 64000, False)],
        "cheques": [deposit_row("121200", D(2025, 9, 1), 3200, "Mashreq")]
                   + rent_rows(121201, [D(2025, 9, 1), D(2026, 3, 1)], 32000, "Mashreq"),
        "renew": {"contract": D(2026, 8, 20), "start": D(2026, 9, 1), "end": D(2027, 8, 31), "percent": 5,
                  "fee": 525, "installments": 2, "startingNumber": "121300", "payeeBank": "Mashreq"},
    },
    # Left in DRAFT: the VAT tutorial posts it on camera (tutorial 41).
    "noor": {
        "unit": "bcg01", "tenant": "noor", "contract": D(2026, 9, 21), "start": D(2026, 10, 1), "end": D(2027, 9, 30),
        "vat": True, "post": False,
        "lines": [("SECURITY_DEPOSIT", 15000, False), ("RENT", 150000, True)],
        "cheques": [deposit_row("131300", D(2026, 10, 1), 15000, "Emirates NBD")]
                   + rent_rows(131301, [D(2026, 10, 1), D(2027, 1, 1), D(2027, 4, 1), D(2027, 7, 1)], 39375,
                               "Emirates NBD", vat=1875),
    },
}

# ── Suppliers and payables ──────────────────────────────────────────────────────
VENDORS = {
    "coastline": {"nameEn": "Coastline Facility Services LLC", "nameAr": "كوستلاين لخدمات المرافق ذ.م.م",
                  "contactPerson": "Imran Qadri", "phone": "+97140000911", "trn": "100555666700003"},
    "bluewave": {"nameEn": "BlueWave Cleaning LLC", "nameAr": "بلو ويف للتنظيف ذ.م.م",
                 "contactPerson": "Maria Lopez", "phone": "+97140000912", "trn": "100555777800003"},
    "falcon": {"nameEn": "Falcon Lifts & Escalators LLC", "nameAr": "فالكون للمصاعد والسلالم المتحركة ذ.م.م",
               "contactPerson": "Sanjay Pillai", "phone": "+97140000913", "trn": "100555888900003"},
}

# Expense leaves the seed adds under D-01 Direct Expense: key -> (code, English, Arabic)
EXPENSE_LEAVES = {
    "repairs": ("D-01-101", "Repairs & Maintenance", "الإصلاحات والصيانة"),
    "cleaning": ("D-01-102", "Cleaning Services", "خدمات التنظيف"),
    "lifts": ("D-01-103", "Lift Maintenance (AMC)", "صيانة المصاعد (عقد سنوي)"),
}

# Opening position at 31 Aug 2025 (the day before the books start).
OPENING_BANK = {"residences": Decimal("250000.00"), "business": Decimal("180000.00")}
AP_OPENING_ITEMS = [
    {"vendor": "coastline", "invoiceNumber": "CFS-2508-114", "invoiceDate": D(2025, 8, 20), "dueDate": D(2025, 9, 19),
     "amount": Decimal("12600.00"), "property": "residences"},
]


def purchase_invoices():
    """Every supplier bill of the year: {key, vendor, invoiceNumber, date, lines[(expense, property, net)], narration}."""
    out = []
    for i in range(12):                                 # BlueWave cleaning, monthly, the residences
        d = add_months(D(2025, 9, 25), i)
        out.append({"key": f"bluewave-{d:%Y%m}", "vendor": "bluewave", "invoiceNumber": f"BW-{d:%y%m}-07",
                    "date": d, "lines": [("cleaning", "residences", Decimal("2400.00"))],
                    "narration": f"Common-area cleaning {d:%B %Y}"})
    for d in (D(2025, 9, 10), D(2025, 12, 10), D(2026, 3, 10), D(2026, 6, 10)):   # Falcon lifts AMC, quarterly, both buildings
        out.append({"key": f"falcon-{d:%Y%m}", "vendor": "falcon", "invoiceNumber": f"FLE-{d:%y}-Q{(d.month - 1) // 3 + 1}",
                    "date": d, "lines": [("lifts", "residences", Decimal("4500.00")), ("lifts", "business", Decimal("4500.00"))],
                    "narration": f"Lift maintenance contract, quarter from {d:%B %Y}"})
    out.append({"key": "coastline-202601", "vendor": "coastline", "invoiceNumber": "CFS-2601-018", "date": D(2026, 1, 14),
                "lines": [("repairs", "business", Decimal("3600.00"))], "narration": "Chiller service, Business Centre"})
    return out


# Payment runs: (key, payment date, method, cheque date, first cheque number, pay items due on or before)
PAYMENT_RUNS = [
    ("PR-OCT25", D(2025, 10, 10), "TRANSFER", None, None, D(2025, 10, 31)),
    ("PR-JAN26", D(2026, 1, 12), "TRANSFER", None, None, D(2026, 1, 31)),
    ("PR-APR26", D(2026, 4, 12), "TRANSFER", None, None, D(2026, 4, 30)),
    ("PR-JUL26", D(2026, 7, 12), "TRANSFER", None, None, D(2026, 7, 31)),
    # post-dated supplier cheques: presented in September (PDC payable cleared)...
    ("PR-AUG26", D(2026, 8, 25), "CHEQUE", D(2026, 9, 10), "004501", D(2026, 8, 31)),
    # ...and one still outstanding at the end of the story (PDC payable open)
    ("PR-SEP26", D(2026, 9, 21), "CHEQUE", D(2026, 10, 20), "004521", D(2026, 10, 20)),
]
ISSUED_CHEQUE_PRESENTED_ON = D(2026, 9, 12)   # PR-AUG26's cheques, presented by the bank

BANK_ACCOUNTS = {
    "residences": {"bankName": "Emirates Islamic", "accountNumber": "3700000000901", "iban": "AE000340003700000000901"},
    "business": {"bankName": "Emirates Islamic", "accountNumber": "3700000000902", "iban": "AE000340003700000000902"},
}


# ── Arithmetic the narrations quote (pinned by the tests) ──────────────────────
def contract_value(lines) -> Decimal:
    """Σ (net + VAT), VAT per line, deposits never taxed: the figure the cheque grid must equal."""
    total = Decimal(0)
    for code, gross, vat in lines:
        net = money(gross)
        total += net + (vat_of(net) if vat and code not in ("SECURITY_DEPOSIT", "PARKING_DEPOSIT") else 0)
    return total


def statement_csv(opening: Decimal, lines) -> str:
    """A bank statement in the SPLIT layout `STATEMENT_PROFILE` maps: Date, Value Date, Description,
    Reference, Cheque No, Debit, Credit, Balance. `lines`: [{date, desc, ref, chq, amount}] with money
    in positive and money out negative, in any order (sorted here: date, then credits first)."""
    rows = sorted(lines, key=lambda x: (x["date"], -Decimal(str(x["amount"]))))
    bal = money(opening)
    buf = io.StringIO()
    w = csv.writer(buf, lineterminator="\n")
    w.writerow(["Date", "Value Date", "Description", "Reference", "Cheque No", "Debit", "Credit", "Balance"])
    for x in rows:
        amt = money(x["amount"])
        bal = money(bal + amt)
        d = x["date"].strftime("%d/%m/%Y")
        w.writerow([d, d, x["desc"], x.get("ref", ""), x.get("chq", ""),
                    f"{-amt:.2f}" if amt < 0 else "", f"{amt:.2f}" if amt > 0 else "", f"{bal:.2f}"])
    return buf.getvalue()


STATEMENT_PROFILE = {
    "fileKind": "CSV", "headerRow": 1, "firstDataRow": 2, "csvDelimiter": ",", "dateFormats": ["dd/MM/yyyy"],
    "columns": {"txnDate": "Date", "valueDate": "Value Date", "description": "Description", "reference": "Reference",
                "chequeNo": "Cheque No", "debit": "Debit", "credit": "Credit", "balance": "Balance"},
    "amountMode": "SPLIT", "decimalSeparator": ".", "matchWindowDays": 5,
}

# Bank-only lines the statement adds for the reconciliation tutorial (none of them is in the books yet).
STATEMENT_EXTRAS = [
    {"date": D(2026, 8, 28), "desc": "ACCOUNT MAINTENANCE CHARGE + VAT", "amount": Decimal("-105.00")},
    {"date": D(2026, 8, 31), "desc": "PROFIT CREDIT", "amount": Decimal("412.50")},
    {"date": D(2026, 8, 27), "desc": "INWARD TT REF UNKNOWN", "ref": "IPI40211877", "amount": Decimal("2500.00")},
]
