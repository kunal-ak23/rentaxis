import { describe, expect, it } from "vitest";
import * as fs from "node:fs";
import * as path from "node:path";

/**
 * No dashboard page may swallow a failed GET.
 *
 * The per-page tests prove the behaviour on one page. This one guards the whole
 * directory, because the defect is an *absence* — a missing else branch — and
 * absences do not show up in any render. A new page written with the old
 * `if (res.ok) { setState(data) }` shape and no failure path would sail past
 * every other test in the suite, exactly as the original fifteen did.
 */

const DASHBOARD = path.join(__dirname, "..");

function tsxFiles(dir: string): string[] {
    return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) return entry.name === "__tests__" ? [] : tsxFiles(full);
        return entry.name.endsWith(".tsx") ? [full] : [];
    });
}

describe("dashboard pages handle failed loads", () => {
    it("has no file that checks res.ok without any failure path", () => {
        const offenders: string[] = [];

        for (const file of tsxFiles(DASHBOARD)) {
            const source = fs.readFileSync(file, "utf8");
            if (!/if \(res\.ok\)/.test(source)) continue;

            // Deliberately narrow. An earlier version also accepted "the file
            // mentions setLoadError somewhere", which a mutation test showed is
            // satisfied by the state declaration alone — it passed on a page
            // whose failure path had been deleted. Only a branch that actually
            // reacts to a non-ok response counts:
            //
            //   } else {              an else on the res.ok check
            //   if (!res.ok)          an explicit negative check
            //   res.status            a status-specific branch (e.g. 404 vs 500)
            const handled =
                /\}\s*else\b/.test(source)
                || /if \(!res\.ok\)/.test(source)
                || /res\.status/.test(source);

            if (!handled) offenders.push(path.relative(DASHBOARD, file));
        }

        expect(offenders, "these pages swallow a failed GET").toEqual([]);
    });

    it("every load-failure message resolves in both catalogues", async () => {
        // The catalogues are nested deeper than one level in places, so a
        // Record<string, Record<string, string>> assertion does not hold; only
        // the Common namespace is needed here.
        const en = (await import("../../../../../messages/en.json")).default.Common as Record<string, string>;
        const ar = (await import("../../../../../messages/ar.json")).default.Common as Record<string, string>;

        const used = new Set<string>();
        for (const file of tsxFiles(DASHBOARD)) {
            const source = fs.readFileSync(file, "utf8");
            for (const m of source.matchAll(/tCommon\("([^"]+)"\)/g)) used.add(m[1]);
        }
        expect(used.size, "expected pages to use the shared Common strings").toBeGreaterThan(3);

        for (const key of used) {
            expect(en, `en.Common.${key}`).toHaveProperty(key);
            expect(ar, `ar.Common.${key}`).toHaveProperty(key);
            expect(ar[key], `ar.Common.${key} is still English`).not.toBe(en[key]);
        }
    });
});
