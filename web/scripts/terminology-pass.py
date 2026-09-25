# scripts/terminology-pass.py — one-off, run from web/. Rewrites VALUES only.
import json, re, sys
sys.path.insert(0, ".")
TERMS_TS = open("src/lib/__tests__/terminology.test.ts", encoding="utf-8").read()
# "key": { en: "…", ar: "…" } rows of the TERMS table
ROW = re.compile(r'"([\w.]+)":\s*\{\s*en:\s*"((?:[^"\\]|\\.)*)",\s*ar:\s*"((?:[^"\\]|\\.)*)"\s*\}')
terms = {k: (e, a) for k, e, a in ROW.findall(TERMS_TS)}

WORDS = [  # English word rules (order matters: plurals and title case first)
    (r"\bTenancy Contracts\b", "Tenancy Contracts"),
    (r"\bLeases\b", "Tenancy Contracts"), (r"\bLease\b", "Tenancy Contract"),
    (r"\bleases\b", "contracts"), (r"\blease\b", "contract"),
    (r"\bRenters\b", "Tenants"), (r"\bRenter\b", "Tenant"),
    (r"\brenters\b", "tenants"), (r"\brenter\b", "tenant"),
    (r"\bTenant-wide\b", "Company-wide"), (r"\btenant-wide\b", "company-wide"),
]
ENQUIRY = [(r"\bInterests\b", "Enquiries"), (r"\bInterest\b", "Enquiry"),
           (r"\binterests\b", "enquiries"), (r"\binterest\b", "enquiry")]

# Hand fixes after reading the rule output (step 4): substrings the word
# rules made read wrong, and the staff-facing "Tenant Admin" role name.
FIXES = [
    ("Create a DRAFT contract contract.", "Create a DRAFT tenancy contract."),
    ("Ready to Tenancy Contract", "Ready to Let"),
    ("Min Tenancy Contract (months)", "Min Contract Term (months)"),
    ("Tenancy Contract start and end date are required", "Contract start and end date are required"),
    ("Tenancy Contract ends {date}.", "Your contract ends {date}."),
    ("or add a Tenant Admin or", "or add a Company Admin or"),
    ("A tenant admin can close it.", "A company admin can close it."),
]

def set_path(tree, dotted, value):
    *parents, leaf = dotted.split(".")
    for p in parents:
        tree = tree.setdefault(p, {})
    tree[leaf] = value

def walk(tree, prefix, fn):
    for k, v in tree.items():
        path = f"{prefix}{k}"
        if isinstance(v, dict):
            walk(v, path + ".", fn)
        else:
            tree[k] = fn(path, v)

def english(path, value):
    if path.startswith("Common.errors."):
        return value
    # (?<!\{) keeps ICU argument names ({renter}, {leases, plural, …}) intact —
    # they are code, not copy.
    for pat, rep in WORDS:
        value = re.sub(r"(?<!\{)" + pat, rep, value)
    if path.startswith("Listings."):
        for pat, rep in ENQUIRY:
            value = re.sub(r"(?<!\{)" + pat, rep, value)
    for old, new in FIXES:
        value = value.replace(old, new)
    return value

def spans(text):
    """Map dotted key path -> (start, end) of each string leaf's JSON literal, and
    object path -> index of its closing brace, so values are patched in place
    and the file's own formatting (inline objects, key order) is kept."""
    leaves, closes, i = {}, {}, 0
    def ws():
        nonlocal i
        while text[i] in " \t\r\n": i += 1
    def string():
        nonlocal i
        start = i; i += 1
        while text[i] != '"':
            i += 2 if text[i] == "\\" else 1
        i += 1
        return start, i
    def value(path):
        nonlocal i
        ws()
        c = text[i]
        if c == "{":
            i += 1; ws()
            while text[i] != "}":
                ks, ke = string(); key = json.loads(text[ks:ke])
                ws(); i += 1  # colon
                value(f"{path}.{key}" if path else key)
                ws()
                if text[i] == ",": i += 1; ws()
            closes[path] = i; i += 1
        elif c == "[":
            i += 1; ws(); n = 0
            while text[i] != "]":
                value(f"{path}[{n}]"); n += 1; ws()
                if text[i] == ",": i += 1; ws()
            i += 1
        elif c == '"':
            leaves[path] = string()
        else:
            while text[i] not in ",}] \t\r\n": i += 1
    value("")
    return leaves, closes

def flat(tree, prefix=""):
    for k, v in tree.items():
        if isinstance(v, dict):
            yield from flat(v, f"{prefix}{k}.")
        else:
            yield f"{prefix}{k}", v

for locale in ("en", "ar"):
    fname = f"messages/{locale}.json"
    text = open(fname, encoding="utf-8").read()
    data = json.loads(text)
    before = dict(flat(data))
    if locale == "en":
        walk(data, "", english)
    for key, (e, a) in terms.items():
        set_path(data, key, e if locale == "en" else a)
    after = dict(flat(data))
    leaves, closes = spans(text)
    edits = []  # (pos_start, pos_end, replacement)
    added = {}
    for key, v in after.items():
        if key in before:
            if before[key] != v:
                s0, e0 = leaves[key]
                edits.append((s0, e0, json.dumps(v, ensure_ascii=False)))
        else:
            parent, leaf = key.rsplit(".", 1)
            added.setdefault(parent, []).append((leaf, v))
    for parent, items in added.items():
        close = closes[parent]
        j = close - 1
        while text[j] in " \t\r\n": j -= 1
        indent = "  " * (parent.count(".") + 2)
        ins = "".join(f",\n{indent}{json.dumps(k)}: {json.dumps(v, ensure_ascii=False)}" for k, v in items)
        edits.append((j + 1, j + 1, ins))
    for s0, e0, rep in sorted(edits, key=lambda x: x[0], reverse=True):
        text = text[:s0] + rep + text[e0:]
    assert json.loads(text) == data, fname
    open(fname, "w", encoding="utf-8").write(text)
    print(f"{fname}: {sum(1 for e in edits if e[0] != e[1])} values changed, {sum(len(v) for v in added.values())} keys added")
print(f"applied {len(terms)} table terms")

# Help centre prose (src/content/help/*.md and the mirrored template literals in
# src/lib/helpArticles.ts): the same English word rules, on prose lines only.
# Front matter (slugs, categories, roles, relatedTour) and anything that looks
# like a path or identifier (/dashboard/leases, renter--making-payments) stay.
import glob
PROSE = [(r"\bTenant Admins\b", "Company Admins"), (r"\bTenant Admin\b", "Company Admin"),
         (r"\bTenant Users\b", "Company Users"), (r"\bTenant User\b", "Company User")] + WORDS
GUARD_BEFORE, GUARD_AFTER = r"(?<![/\-_{.`'\w])", r"(?![/\-_`'])"

def prose_line(line):
    for pat, rep in PROSE:
        line = re.sub(GUARD_BEFORE + pat + GUARD_AFTER, rep, line)
    # "lease contract" became "contract contract" — say it once.
    return line.replace("contract contracts", "contracts").replace("contract contract", "contract")

def rewrite_help(fname):
    lines = open(fname, encoding="utf-8").read().split("\n")
    front, out = False, []
    for i, line in enumerate(lines):
        stripped = line.strip()
        if stripped == "---" and (front or (fname.endswith(".md") and i == 0)):
            front = not front if fname.endswith(".md") or front else front
            out.append(line); continue
        if stripped.endswith("`---"):
            front = True; out.append(line); continue
        out.append(line if front or stripped.startswith(("registerArticle(", "import ", "//")) else prose_line(line))
    open(fname, "w", encoding="utf-8").write("\n".join(out))

for f in sorted(glob.glob("src/content/help/*.md")) + ["src/lib/helpArticles.ts"]:
    rewrite_help(f)
print("help prose rewritten")
