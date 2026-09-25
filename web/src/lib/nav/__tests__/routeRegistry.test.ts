// src/lib/nav/__tests__/routeRegistry.test.ts
import fs from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { ROUTE_MOVES, matchRoute } from "../routeMap";
import { DASHBOARD_ROUTES } from "../routeRegistry";

const APP = path.resolve(__dirname, "../../../app/[locale]");

/** Every page.tsx under dashboard/ and superadmin/, as a route with [param] segments. */
function pageRoutes(dir: string): string[] {
    const out: string[] = [];
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, e.name);
        if (e.isDirectory()) {
            if (e.name === "__tests__" || e.name.startsWith("_")) continue;
            out.push(...pageRoutes(full));
        } else if (e.name === "page.tsx") {
            out.push("/" + path.relative(APP, dir).split(path.sep).join("/"));
        }
    }
    return out;
}

const onDisk = [...pageRoutes(path.join(APP, "dashboard")), ...pageRoutes(path.join(APP, "superadmin"))].sort();
const asPattern = (p: string) => p.replace(/\[([^\]]+)\]/g, ":$1");

describe("DASHBOARD_ROUTES", () => {
    it("lists every page on disk, or the page is a moved-away wrapper", () => {
        const known = new Set(DASHBOARD_ROUTES.map(r => r.path));
        const missing = onDisk.filter(p => !known.has(p) && !ROUTE_MOVES.some(m => matchRoute(m.from, asPattern(p)) !== null || m.from === p));
        expect(missing).toEqual([]);
    });

    it("lists no route without a page", () => {
        const disk = new Set(onDisk);
        expect(DASHBOARD_ROUTES.map(r => r.path).filter(p => !disk.has(p))).toEqual([]);
    });

    it("lists no moved route as a live one", () => {
        expect(DASHBOARD_ROUTES.map(r => r.path).filter(p => ROUTE_MOVES.some(m => m.from === p))).toEqual([]);
    });
});
