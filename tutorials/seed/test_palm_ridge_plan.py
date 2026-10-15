"""Pins the figures the accounting narrations read out, and the seed's local-only guard.

Run: python3 -m unittest tutorials/seed/test_palm_ridge_plan.py
"""
import csv
import datetime as dt
import importlib.util
import io
import pathlib
import unittest
from decimal import Decimal

HERE = pathlib.Path(__file__).parent


def load(name):
    spec = importlib.util.spec_from_file_location(name, HERE / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


P = load("palm_ridge_plan")
D = dt.date
M = Decimal


class ProrationTest(unittest.TestCase):
    def test_spec_fixture_51000(self):
        """Spec §8.2's reference table, row by row."""
        rows = P.recognition_slices(51000, D(2026, 9, 24), D(2027, 9, 23))
        self.assertEqual([r[2] for r in rows], [7, 31, 30, 31, 31, 28, 31, 30, 31, 30, 31, 31, 23])
        self.assertEqual([str(r[3]) for r in rows], [
            "978.08", "4331.51", "4191.78", "4331.51", "4331.51", "3912.33", "4331.51", "4191.78",
            "4331.51", "4191.78", "4331.51", "4331.51", "3213.68"])
        self.assertEqual(sum(r[3] for r in rows), M("51000.00"))

    def test_omar_overview_figures(self):
        c = P.CONTRACTS["omar"]
        self.assertEqual(P.day_rate(72000, c["start"], c["end"]), M("197.260274"))
        rows = P.recognition_slices(72000, c["start"], c["end"])
        self.assertEqual((rows[0][2], rows[0][3]), (31, M("6115.07")))      # October 2025
        self.assertEqual((rows[1][2], rows[1][3]), (30, M("5917.81")))      # November 2025
        self.assertEqual(len(rows), 12)
        self.assertEqual(sum(r[3] for r in rows), M("72000.00"))

    def test_meridian_vat_figures(self):
        c = P.CONTRACTS["meridian"]
        self.assertEqual(P.vat_of(120000), M("6000.00"))
        self.assertEqual(P.contract_value(c["lines"]), M("136000.00"))
        rent_rows = [r for r in c["cheques"] if r["rowKind"] == "RENT"]
        self.assertEqual([r["vatAmount"] for r in rent_rows], [M(1500)] * 4)
        self.assertEqual(P.recognition_slices(120000, c["start"], c["end"])[0][3], M("10191.78"))

    def test_rent_free_month_recognises_nothing_and_the_rest_sums_to_net(self):
        c = P.CONTRACTS["sahara"]
        f_from, f_to, _ = c["rent_free"][0]
        concession = P.rent_free_concession(96000, c["start"], c["end"], f_from, f_to)
        self.assertEqual(concession, M("7364.38"))
        net = M("96000.00") - concession
        self.assertEqual(net, M("88635.62"))
        self.assertEqual(P.vat_of(net), M("4431.78"))
        rows = P.recognition_slices(net, c["start"], c["end"], free=[(f_from, f_to)])
        self.assertEqual(rows[0][0], D(2026, 3, 1))                       # February is free: no row
        self.assertEqual(rows[0][3], M("8153.42"))                        # March
        self.assertEqual(sum(r[3] for r in rows), net)

    def test_daniel_termination_figures(self):
        c = P.CONTRACTS["daniel"]
        earned = P.earned_through(90000, c["start"], c["end"], c["terminate"]["date"])
        self.assertEqual(earned, M("37726.03"))
        self.assertEqual(M("90000.00") - earned, M("52273.97"))           # the TCR: unearned rent
        owed = earned - M("22500.00")                                     # only the first rent cheque cleared
        self.assertEqual(owed, M("15226.03"))
        # settlement: the deposit absorbs part, cleaning is charged, the rest is due
        self.assertEqual(owed + M("600") - M("4500"), M("11326.03"))

    def test_grace_refund_figures(self):
        c = P.CONTRACTS["grace"]
        earned = P.earned_through(70000, c["start"], c["end"], c["terminate"]["date"])
        self.assertEqual(earned, M("43917.81"))
        prepaid = M("52500.00") - earned                                   # three rent cheques cleared
        self.assertEqual(prepaid, M("8582.19"))
        self.assertEqual(M("3500") + prepaid - M("750"), M("11332.19"))   # refund owed to the tenant


class PlanShapeTest(unittest.TestCase):
    def test_every_explicit_grid_equals_its_contract_value(self):
        for key, c in P.CONTRACTS.items():
            if "cheques" not in c:
                continue
            with self.subTest(key):
                self.assertEqual(sum(r["amount"] for r in c["cheques"]), P.contract_value(c["lines"]))
                vat = sum(P.vat_of(g) for code, g, v in c["lines"] if v)
                self.assertEqual(sum(r["vatAmount"] for r in c["cheques"]), vat)

    def test_replacements_never_exceed_the_returned_cheque(self):
        for key, c in P.CONTRACTS.items():
            for no, rep in (c.get("replace") or {}).items():
                with self.subTest(key):
                    bounced = next(r for r in c["cheques"] if r["chequeNumber"] == no)
                    self.assertLessEqual(sum(r["amount"] for r in rep["rows"]), bounced["amount"])

    def test_every_dated_event_is_before_the_earliest_run(self):
        dates = [P.CONTRACTS[k]["contract"] for k in P.CONTRACTS]
        for c in P.CONTRACTS.values():
            dates += [v[1] for v in (c.get("fates") or {}).values()]
            for k in ("terminate", "settle", "write_off", "recover", "refund"):
                if c.get(k):
                    dates.append(c[k]["date"])
        dates += [r[1] for r in P.PAYMENT_RUNS] + [P.ISSUED_CHEQUE_PRESENTED_ON]
        self.assertLess(max(dates), P.EARLIEST_RUN)

    def test_every_dated_event_is_after_the_opening_day_and_unit_and_tenant_exist(self):
        for key, c in P.CONTRACTS.items():
            with self.subTest(key):
                self.assertGreater(c["contract"], P.OPENING_DATE)
                self.assertIn(c["unit"], P.UNITS)
                self.assertIn(c["tenant"], P.TENANTS)

    def test_trns_are_fifteen_digits(self):
        for trn in [P.ORG_TRN] + [v["trn"] for v in P.VENDORS.values()]:
            self.assertRegex(trn, r"^\d{15}$")


class StatementTest(unittest.TestCase):
    def test_running_balance_and_split_columns(self):
        text = P.statement_csv(M("1000.00"), [
            {"date": D(2026, 8, 2), "desc": "CHQ PAID 004501", "chq": "004501", "amount": M("-250.00")},
            {"date": D(2026, 8, 2), "desc": "CHQ CLEARING 770108", "chq": "770108", "amount": M("5500.00")},
        ])
        rows = list(csv.reader(io.StringIO(text)))
        self.assertEqual(rows[0], ["Date", "Value Date", "Description", "Reference", "Cheque No", "Debit", "Credit", "Balance"])
        self.assertEqual(rows[1][2], "CHQ CLEARING 770108")              # credits first on the same day
        self.assertEqual(rows[1][6:], ["5500.00", "6500.00"])
        self.assertEqual(rows[2][5], "250.00")
        self.assertEqual(rows[2][7], "6250.00")

    def test_profile_maps_every_header(self):
        header = P.statement_csv(M(0), []).splitlines()[0].split(",")
        for field, col in P.STATEMENT_PROFILE["columns"].items():
            self.assertIn(col, header, field)


class LocalGuardTest(unittest.TestCase):
    def test_accepts_a_local_tutorial_stack(self):
        self.assertEqual(P.require_local_url("http://localhost:8084/", "X"), "http://localhost:8084")
        self.assertEqual(P.require_local_url("http://127.0.0.1:3004", "X"), "http://127.0.0.1:3004")

    def test_refuses_production_and_empty(self):
        for url in ("https://rentaxis.uaenorth.cloudapp.azure.com", "", None, "http://10.0.0.5:8084", "ftp://localhost:8084"):
            with self.subTest(url), self.assertRaises(SystemExit):
                P.require_local_url(url, "X")

    def test_refuses_the_other_local_stacks(self):
        for port in (3000, 3001, 3002, 3003, 8081, 8082, 8083):
            with self.subTest(port), self.assertRaises(SystemExit):
                P.require_local_url(f"http://localhost:{port}", "X")

    def test_the_seed_never_reads_the_prod_env_file(self):
        """Mutation check on the guard's purpose: the seed must not load web/e2e-prod/.env.local."""
        text = (HERE / "seed_palm_ridge.py").read_text()
        self.assertNotIn("e2e-prod", text)
        self.assertNotIn("load_env(", text)
        self.assertNotIn("PROD_BASE_URL", text)


if __name__ == "__main__":
    unittest.main()
