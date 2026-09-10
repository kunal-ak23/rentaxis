import { describe, expect, it } from "vitest";
import * as fs from "node:fs";
import * as path from "node:path";

import ar from "../../../../../../messages/ar.json";
import en from "../../../../../../messages/en.json";

/**
 * The leases list is the screen the lease wizard is opened from, so it sits
 * directly in the demo path. Its table headers, view toggle (Table / Cards /
 * Board), row actions and the supporting-documents modal were all English
 * literals despite useTranslations("MasterData") already being in scope.
 *
 * Asserted against the source: a hardcoded literal renders correctly in
 * English, so no English-locale render can see it.
 */

const PAGE = path.join(__dirname, "..", "page.tsx");
const RBAC = path.join(__dirname, "..", "..", "..", "..", "..", "lib", "rbac.ts");

describe("leases list localization", () => {
    it("has no bare English literal left", () => {
        const source = fs.readFileSync(PAGE, "utf8");
        const jsxText = [...source.matchAll(/>\s*([A-Z][A-Za-z0-9 ,&.'%/()#*—:-]{2,60}?)\s*</g)].map((m) => m[1]);
        const attrs = [...source.matchAll(/(?:placeholder|title|aria-label|label)="([^"]{3,60})"/g)].map((m) => m[1]);
        expect([...jsxText, ...attrs]).toEqual([]);
    });

    it("resolves every MasterData key it calls, in both catalogues", () => {
        const source = fs.readFileSync(PAGE, "utf8");
        const keys = [...new Set([...source.matchAll(/\bt\("([^"]+)"\)/g)].map((m) => m[1]))];
        expect(keys.length).toBeGreaterThan(25);

        const enNs = en.MasterData as Record<string, string>;
        const arNs = ar.MasterData as Record<string, string>;
        expect(keys.filter((k) => !(k in enNs)), "keys absent from en.json").toEqual([]);
        expect(keys.filter((k) => !(k in arNs)), "keys absent from ar.json").toEqual([]);
        expect(keys.filter((k) => arNs[k] === enNs[k]), "keys still holding English").toEqual([]);
    });

    it("covers every role returned by rbac in the Roles namespace", () => {
        // getRoleLabel is the English fallback; the Roles namespace has to know
        // every role it can return, or the top-bar label silently stays English.
        const rbac = fs.readFileSync(RBAC, "utf8");
        const block = /export function getRoleLabel\([\s\S]*?\n\}/.exec(rbac)?.[0] ?? "";
        const roles = [...block.matchAll(/^\s{8}([A-Z_]+):/gm)].map((m) => m[1]);
        expect(roles.length).toBeGreaterThan(4);

        const enRoles = en.Roles as Record<string, string>;
        const arRoles = ar.Roles as Record<string, string>;
        expect(roles.filter((r) => !(r in enRoles)), "roles missing from en.Roles").toEqual([]);
        expect(roles.filter((r) => !(r in arRoles)), "roles missing from ar.Roles").toEqual([]);
        expect(roles.filter((r) => arRoles[r] === enRoles[r]), "roles still holding English").toEqual([]);
    });
});
