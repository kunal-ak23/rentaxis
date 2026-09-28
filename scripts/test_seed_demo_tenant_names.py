"""The demo seed's on-screen names (tutorial bug 2026-09-28).

Run: python3 -m unittest scripts/test_seed_demo_tenant_names.py

Records the tutorials film ("Omar Tutorial", "Tutorial Community Cafe",
"TUTORIAL-B2-18" …) must read as a real organisation's. The old names survive only
as LEGACY_* lookups, so a re-run against an organisation seeded with them renames or
reuses the record instead of creating a second one.
"""
import ast
import importlib.util
import pathlib
import unittest

SEED = pathlib.Path(__file__).with_name("seed_demo_tenant.py")


def load_seed():
    spec = importlib.util.spec_from_file_location("seed_demo_tenant", SEED)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class FindNamedTest(unittest.TestCase):
    def setUp(self):
        self.find_named = load_seed().find_named

    def test_prefers_the_current_name(self):
        items = [{"id": "old", "n": "Tutorial Community Cafe"}, {"id": "new", "n": "Palm Corner Cafe"}]
        item, legacy = self.find_named(items, "n", "Palm Corner Cafe", ("Tutorial Community Cafe",))
        self.assertEqual((item["id"], legacy), ("new", False))

    def test_finds_a_legacy_record_and_flags_it_for_renaming(self):
        items = [{"id": "old", "n": "Tutorial Community Cafe"}]
        item, legacy = self.find_named(items, "n", "Palm Corner Cafe", ("Tutorial Community Cafe",))
        self.assertEqual((item["id"], legacy), ("old", True))

    def test_nothing_found_means_create(self):
        self.assertEqual(self.find_named([{"n": "x"}], "n", "y", ("z",)), (None, False))


class NoTutorialNamesTest(unittest.TestCase):
    def test_only_legacy_lookups_still_say_tutorial(self):
        tree = ast.parse(SEED.read_text(encoding="utf-8"))
        allowed = set()
        for node in ast.walk(tree):
            # LEGACY_X = ("…",) and ensure_operator(…, legacy_names=("…",))
            if isinstance(node, ast.Assign) and any(
                    isinstance(t, ast.Name) and t.id.startswith("LEGACY_") for t in node.targets):
                allowed.update(id(c) for c in ast.walk(node.value))
            if isinstance(node, ast.keyword) and node.arg == "legacy_names":
                allowed.update(id(c) for c in ast.walk(node.value))
        docstrings = {id(n.body[0].value) for n in ast.walk(tree)
                      if isinstance(n, (ast.Module, ast.FunctionDef, ast.ClassDef))
                      and n.body and isinstance(n.body[0], ast.Expr)
                      and isinstance(n.body[0].value, ast.Constant)}
        offenders = [
            (node.lineno, node.value) for node in ast.walk(tree)
            if isinstance(node, ast.Constant) and isinstance(node.value, str)
            and id(node) not in allowed and id(node) not in docstrings
            and ("tutorial" in node.value.lower() or "التجريبي" in node.value)
        ]
        self.assertEqual(offenders, [])


if __name__ == "__main__":
    unittest.main()
