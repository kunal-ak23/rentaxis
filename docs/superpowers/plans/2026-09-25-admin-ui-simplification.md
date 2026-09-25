# Admin UI Simplification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An operations manager sees ~9 sidebar entries instead of 46, lands on a home page that lists today's work, and finds every lease action from a short header plus one "More actions" menu — with no feature removed, no permission changed and no existing URL broken.

**Architecture:** Navigation becomes data: pure models (`navModel` → icon-rail sections with their panel pages, `accountingNav` in PACT Finance-ribbon groups, `collectionsModel`, `settingsModel`) turn `(role, tenant flags, books-live)` into the two-level shell (spec §1a), the Collection hub pills and the Settings sections. Every moved URL is listed once in `routeMap.ts`, which the middleware (`src/proxy.ts`) answers with a 308 that keeps the locale and the query string. Existing page bodies move into components and are re-hosted (hub tabs, settings sections, contract sections) without rewriting their logic; labels switch to PACT RevenU terms by message value only; guard tests (per-role nav, RBAC parity, route registry, action coverage, legacy-header matrix, Playwright sweep) prove nothing was lost. Frontend only — no file under `backend/` changes.

**Tech Stack:** Next.js 16 App Router, React 19, TypeScript 5 (strict), Tailwind CSS 4, next-intl 4 (EN/AR), next-auth 4, vitest 4 + @testing-library/react + jsdom, Playwright 1.58.

**Spec:** `docs/superpowers/specs/2026-09-25-admin-ui-simplification-design.md`

All paths below are relative to `/Users/kunalsharma/datagami/rentaxis/web` unless they start with `/` or `docs/`. Commands assume `cd /Users/kunalsharma/datagami/rentaxis/web` inside the same command (`cd /abs && …`), because parallel shells share a cwd.

## Global Constraints

- No feature removed.
- `src/lib/rbac.ts` unchanged — no role gains or loses access to anything.
- Every existing URL keeps working: moved routes answer with a permanent redirect that preserves the query string and `?tab=` deep links.
- EN/AR key parity; RTL through logical properties only (`ms-/me-/ps-/pe-/start-/end-/border-s/border-e/rounded-s/rounded-e`, never `left-/right-/ml-/mr-/pl-/pr-/border-l/border-r`) in new or rewritten markup.
- Frontend API calls go through the `/api/proxy/*` rewrite; no hardcoded backend URLs in client components.
- `useTranslations` for every user-visible string.
- Mobile (Flutter) untouched.
- Commit with a pathspec only (`git add <paths> && git commit -m "…" -- <paths>`), a plain branch per PR (no worktrees), and every commit message ends with the trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. PR bodies end with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.

## Review Focus

The five failure modes most likely to bite, none covered by any test today. Each has its test in the owning task:

1. **Deep links with `?tab=` to removed contract tabs** (`?tab=journals|recognition|vat|penalties|contract|maintenance|documents|interactions`) from bookmarks, emails and e2e specs. → Task 25: `routeMap.test.ts` "lease ?tab= deep links" and `lease-deep-links.test.tsx` ("maps every legacy ?tab= … to its new tab and opens the section").
2. **Bookmarks to old finance/settings URLs with query strings** (`/ar/dashboard/finance/cheques?status=BOUNCED&leaseId=…`, `/en/dashboard/settings/fiscal?tab=close`). → Task 1 `routeMap.test.ts` (every move, query kept, `?tab=` kept, mapped key wins, no chains), Task 2 `proxy.test.ts` (real 308 from the middleware), Task 19 ("keeps a register bookmark's filters and lease"), Task 15 sweep "redirects" tests (real browser, EN and AR).
3. **A role seeing a rail section or panel group with nothing it can open.** → Task 4 "never yields an empty group" / "never yields a group of cross-links alone", Task 5 "never yields an empty section or group for any role × flag combination" (7 roles × 8 flag sets × books live/not) and "hides More when none of its pages is enabled".
4. **RTL layout of the rail, panel, drawer and menus** (rail on the wrong side, indicator/badge mirrored wrong, menus opening off-screen). → Task 8 "uses logical properties only" (shell markup scanned for physical classes), Task 23 "anchors the menu to the inline end (RTL-safe)", and the Task 15 sweep in AR at 390 px (no sideways page scroll).
5. **"More actions" hiding an action that was a role's only path** (e.g. PROPERTY_MANAGER renew, give notice, terminate preview). → Task 22 "every legacy header action stays reachable (primary ∪ menu) for every role × status × posted × contract × transferred" (legacy conditions copied verbatim into the test) and "keeps Renew primary for a property manager".

## File Structure

**Created**

| File | Responsibility |
|---|---|
| `src/lib/nav/types.ts` | `Label`, `NavContext` — shared nav types |
| `src/lib/nav/useLabel.ts` | Resolves a `Label` with the root next-intl translator |
| `src/lib/nav/routeMap.ts` | `ROUTE_MOVES` (old → new), `legacyRedirect`, `canonicalHref`, `matchRoute`, and (PR3) `LEGACY_LEASE_TABS` + `resolveLeaseTab` |
| `src/lib/nav/routeRegistry.ts` | `DASHBOARD_ROUTES`: every dashboard/superadmin route and which roles should reach it (sweep + parity input) |
| `src/lib/nav/collectionsModel.ts` | `buildCollectionsTabs(role)` — Collections tabs and their gates |
| `src/lib/nav/settingsModel.ts` | `buildSettingsSections(role)` — Settings sections and their gates |
| `src/lib/nav/accountingNav.ts` | `buildAccountingNav(role, {booksLive})`, `accountingHome(role)`, `isBooksLive(fs)` |
| `src/lib/nav/navModel.ts` | `buildNav(ctx)` → sidebar groups; `flattenNav` |
| `src/lib/nav/__tests__/legacyNav.fixture.ts` | Checked-in snapshot of the pre-change sidebar per role |
| `src/lib/rentSettings.ts` | `RentSettings` type, defaults, `toRentSettingsBody` shared by Rent and Payments sections |
| `src/components/settings/FinesSettings.tsx`, `RentSettings.tsx`, `GatewaySettings.tsx` | Former settings pages, now embeddable |
| `src/components/settings/OnlinePaymentSwitch.tsx` | Per-property online-payment switch (Payments section) |
| `src/components/settings/OrganisationSection.tsx` | Read-only org name + portal slug |
| `src/components/staff/StaffManager.tsx` | Former `/dashboard/staff` page body |
| `src/components/users/UsersManager.tsx` | Former `/superadmin/users` page body |
| `src/app/[locale]/dashboard/settings/page.tsx` | One Settings page with `?section=` |
| `src/lib/ui/actionCatalog.ts` | Every current action → its new location (coverage test input) |
| `src/components/collections/*Panel.tsx` | Former cheque/penalty pages + `DueChequesPanel` |
| `src/app/[locale]/dashboard/collections/page.tsx` | Collections hub with `?tab=` |
| `src/components/nav/NavShellContext.tsx`, `useNavCounts.ts`, `SectionPanel.tsx` | Shell state (phone drawer), rail badge + status-card counts from existing endpoints, the section panel |
| `src/lib/finance/ledgerReport.ts` | PACT ledger grouping, client-side sub-totals, Dr/Cr |
| `src/lib/dashboard/pipeline.ts`, `src/components/dashboard/ContractPipeline.tsx` | Contract pipeline strip |
| `src/lib/leases/contractListView.ts` | Contract list pills ↔ URL ↔ query |
| `src/components/collections/CollectionPills.tsx` | Status pills with counts |
| `src/lib/__tests__/terminology.test.ts`, `scripts/terminology-pass.py` | PACT RevenU label table + the one-off value rewrite |
| `src/lib/leases/leaseActions.ts` | Lease action availability + primary/menu split |
| `src/components/ui/ActionsMenu.tsx` | Generic "⋯"/"More actions" menu (items always mounted, `hidden` when closed) |
| `src/components/ui/SideDrawer.tsx` | Drawer used for Assignment and Write off |
| `src/components/leases/LeaseSection.tsx` | Collapsible section inside a lease tab |
| `src/components/dashboard/TodayList.tsx` | Home "Today" list |
| `src/components/ui/FiltersButton.tsx` | "Filters" popover + active-filter chips |
| `walkthrough/ui-sweep.config.ts`, `walkthrough/ui-sweep.spec.ts` | Playwright sweep over every route, EN+AR, 3 roles, 2 widths |

**Modified**

| File | Change |
|---|---|
| `src/proxy.ts` | 308 for moved routes before next-intl; matcher gains `/dashboard/:path*` |
| `src/components/ui/MvpSidebar.tsx` | The shell: icon rail + section panel (rail-only < 1280 px, drawer < 768 px, RTL-mirrored); Help removed |
| `src/components/ui/TopHeader.tsx` | Menu button (phones), breadcrumb, Help link; PAYMENT notification link → Collection (PR2) |
| `src/components/finance/LedgerTable.tsx`, `dashboard/finance/accounts/page.tsx` | PACT ledger columns (Unit · Tower · Tenant); Account Code · Name · Type |
| `src/components/layout/AuthenticatedLayout.tsx` | Wraps the shell in `NavShellProvider` |
| `src/app/[locale]/dashboard/settings/{fines,rent-settings,gateway}/page.tsx`, `staff/page.tsx`, `superadmin/users/page.tsx` | Thin wrappers over the moved components (`/dashboard/staff` and `/superadmin/users` stay live URLs) |
| `src/app/[locale]/dashboard/finance/cheques/**/page.tsx`, `finance/penalties/page.tsx` | Thin wrappers over Collections panels (PR2) |
| `src/app/[locale]/dashboard/settings/{account-template,fiscal,charge-types}` | `git mv` to `dashboard/finance/*` (PR1) |
| `src/app/[locale]/dashboard/leases/[id]/page.tsx` | Primary header + menu + drawers; 4 tabs with sections; `?tab=` mapping (PR3) |
| `src/app/[locale]/dashboard/page.tsx` | Today list; one chart; occupancy on its KPI card (PR3) |
| `src/app/[locale]/dashboard/leases/page.tsx`, `properties/page.tsx`, `properties/[id]/page.tsx` | Row menu, Filters, header menu, `?status=`; units-page link |
| `src/lib/help.ts`, `src/lib/helpArticles.ts`, `src/content/help/*.md`, `src/components/tour/tours/*.ts` | New names/paths |
| `messages/en.json`, `messages/ar.json` | PACT label values; new namespaces `Collections`, `AccountingNav`, `SettingsPage`, `LeaseActions`, `Today`, `ListActions`, `ContractList`; new `Navigation` keys |
| `e2e/rbac/sidebar-visibility.spec.ts`, `e2e/**`, `walkthrough/accounting-v2-plan{1,2,3}.spec.ts` | New nav, hub tabs and lease tab test ids |

**Shared types (used verbatim across tasks)**

```ts
// src/lib/nav/types.ts
import type { UserRole } from "../rbac";
/** A message key: `ns` is the next-intl namespace, `key` the key inside it. */
export type Label = { ns: string; key: string };
export interface NavContext {
    role: UserRole | undefined;
    isEnabled: (flag: string) => boolean;
    tenantSlug: string;
}
```

---

# PR 1 — Shell (rail + panel), PACT terms, route map + redirects, Settings page, bug fixes

### Task 0: Branch

- [ ] **Step 1: Confirm the tree is free and branch from main**

```bash
cd /Users/kunalsharma/datagami/rentaxis && git status --porcelain | head -5
```
Expected: no output. If there is output, another agent owns the tree — stop and ask; do not stash or discard.

```bash
cd /Users/kunalsharma/datagami/rentaxis && git fetch origin && git checkout -b feat/admin-ui-pr1-nav-settings origin/main
```
Expected: `Switched to a new branch 'feat/admin-ui-pr1-nav-settings'`.

### Task 1: Route map and `legacyRedirect`

**Files:** Create `src/lib/nav/types.ts` (content above), `src/lib/nav/routeMap.ts`, `src/lib/nav/__tests__/routeMap.test.ts`.

**Interfaces**
- Consumes: nothing.
- Produces:
  - `interface RouteMove { from: string; to: string; query?: Record<string, string> }`
  - `const ROUTE_MOVES: RouteMove[]`
  - `function matchRoute(pattern: string, path: string): Record<string, string> | null`
  - `function legacyRedirect(url: URL): URL | null`
  - `function canonicalHref(href: string): string`

- [ ] **Step 1: Write the failing test**

```ts
// src/lib/nav/__tests__/routeMap.test.ts
import { describe, expect, it } from "vitest";
import { ROUTE_MOVES, canonicalHref, legacyRedirect, matchRoute } from "../routeMap";

const redirectOf = (href: string): string | null => {
    const out = legacyRedirect(new URL(href, "http://localhost:3000"));
    return out ? out.pathname + out.search : null;
};

/** One row per ROUTE_MOVES entry — the last test fails if a move has no row. */
const CASES: [string, string][] = [
    ["/en/dashboard/my-unit", "/en/dashboard"],
    ["/en/dashboard/settings/account-template", "/en/dashboard/finance/account-template"],
    ["/en/dashboard/settings/fiscal", "/en/dashboard/finance/fiscal"],
    ["/en/dashboard/settings/charge-types", "/en/dashboard/finance/charge-types"],
    ["/en/dashboard/settings/fines", "/en/dashboard/settings?section=rent"],
    ["/en/dashboard/settings/rent-settings", "/en/dashboard/settings?section=rent"],
    ["/en/dashboard/settings/gateway", "/en/dashboard/settings?section=payments"],
];

describe("legacyRedirect", () => {
    it.each(CASES)("%s → %s", (from, to) => {
        expect(redirectOf(from)).toBe(to);
    });

    it("keeps the Arabic prefix", () => {
        expect(redirectOf("/ar/dashboard/settings/fines")).toBe("/ar/dashboard/settings?section=rent");
    });

    it("works without a locale prefix", () => {
        expect(redirectOf("/dashboard/settings/gateway")).toBe("/dashboard/settings?section=payments");
    });

    it("tolerates a trailing slash", () => {
        expect(redirectOf("/en/dashboard/settings/fines/")).toBe("/en/dashboard/settings?section=rent");
    });

    it("keeps every incoming query parameter, decoded values intact", () => {
        const out = legacyRedirect(new URL("http://localhost:3000/ar/dashboard/settings/rent-settings?propertyId=p1&q=a%20b"))!;
        expect(out.pathname).toBe("/ar/dashboard/settings");
        expect(out.searchParams.get("propertyId")).toBe("p1");
        expect(out.searchParams.get("q")).toBe("a b");
        expect(out.searchParams.get("section")).toBe("rent");
    });

    it("keeps a ?tab= deep link on a page that moved without a query of its own", () => {
        expect(redirectOf("/en/dashboard/settings/fiscal?tab=close")).toBe("/en/dashboard/finance/fiscal?tab=close");
    });

    it("lets the new home's own parameter win over the same incoming key", () => {
        expect(redirectOf("/en/dashboard/settings/gateway?section=users")).toBe("/en/dashboard/settings?section=payments");
    });

    it("returns null for routes that did not move", () => {
        for (const href of ["/en/dashboard", "/en/dashboard/leases", "/en/dashboard/staff", "/en/dashboard/settings", "/en/dashboard/finance/journals/123", "/en"]) {
            expect(redirectOf(href), href).toBeNull();
        }
    });

    it("never redirects into another move (no chains, no loops)", () => {
        for (const move of ROUTE_MOVES) {
            const target = move.to.replace(/:([A-Za-z]+)/g, "x");
            expect(redirectOf(`/en${target}`), move.from).toBeNull();
        }
    });

    it("has a case for every move", () => {
        const uncovered = ROUTE_MOVES.filter(m => !CASES.some(([from]) => matchRoute(m.from, from.replace(/^\/(en|ar)/, "").replace(/\?.*$/, ""))));
        expect(uncovered.map(m => m.from)).toEqual([]);
    });
});

describe("canonicalHref", () => {
    it("drops the locale, follows moves and sorts the query", () => {
        expect(canonicalHref("/ar/dashboard/settings/gateway?b=2&a=1")).toBe("/dashboard/settings?a=1&b=2&section=payments");
        expect(canonicalHref("/dashboard/leases")).toBe("/dashboard/leases");
    });
});
```

- [ ] **Step 2: Run it — it fails**

Run: `cd /Users/kunalsharma/datagami/rentaxis/web && npx vitest run src/lib/nav/__tests__/routeMap.test.ts`
Expected: FAIL — `Failed to resolve import "../routeMap"`.

- [ ] **Step 3: Implement**

```ts
// src/lib/nav/routeMap.ts
/**
 * Every dashboard URL that moved in the admin UI simplification (spec
 * 2026-09-25 "No-regression safeguards" §1), and where it went.
 *
 * The middleware (src/proxy.ts) answers each `from` with a 308 to `to`,
 * keeping the locale prefix and every incoming query parameter; `query` adds
 * the parameters the new home needs (the Collections tab, the Settings
 * section) and wins over an incoming key of the same name. Paths are
 * locale-less; `:name` matches exactly one segment.
 */
export interface RouteMove {
    from: string;
    to: string;
    query?: Record<string, string>;
}

export const ROUTE_MOVES: RouteMove[] = [
    // TENANT_USER's "My Unit" never had a page (it 404'd); the link is gone.
    { from: "/dashboard/my-unit", to: "/dashboard" },
    // Accounting setup lives in the Accounting area now.
    { from: "/dashboard/settings/account-template", to: "/dashboard/finance/account-template" },
    { from: "/dashboard/settings/fiscal", to: "/dashboard/finance/fiscal" },
    { from: "/dashboard/settings/charge-types", to: "/dashboard/finance/charge-types" },
    // One Settings page with sections.
    { from: "/dashboard/settings/fines", to: "/dashboard/settings", query: { section: "rent" } },
    { from: "/dashboard/settings/rent-settings", to: "/dashboard/settings", query: { section: "rent" } },
    { from: "/dashboard/settings/gateway", to: "/dashboard/settings", query: { section: "payments" } },
    // /dashboard/staff does NOT move: Operations › Staff keeps linking to it, and
    // Settings › Users & staff embeds the same StaffManager component.
];

const LOCALE_PREFIX = /^\/(en|ar)(?=\/|$)/;

export function matchRoute(pattern: string, path: string): Record<string, string> | null {
    const p = pattern.split("/");
    const s = path.split("/");
    if (p.length !== s.length) return null;
    const params: Record<string, string> = {};
    for (let i = 0; i < p.length; i++) {
        if (p[i].startsWith(":")) {
            if (!s[i]) return null;
            params[p[i].slice(1)] = s[i];
        } else if (p[i] !== s[i]) {
            return null;
        }
    }
    return params;
}

function fill(pattern: string, params: Record<string, string>): string {
    return pattern.replace(/:([A-Za-z]+)/g, (_, k: string) => params[k] ?? `:${k}`);
}

/** The moved-to URL for `url`, or null when `url` did not move. */
export function legacyRedirect(url: URL): URL | null {
    const prefix = url.pathname.match(LOCALE_PREFIX)?.[0] ?? "";
    const rest = url.pathname.slice(prefix.length);
    const path = rest.length > 1 ? rest.replace(/\/+$/, "") : rest || "/";
    for (const move of ROUTE_MOVES) {
        const params = matchRoute(move.from, path);
        if (!params) continue;
        const target = new URL(url.toString());
        target.pathname = prefix + fill(move.to, params);
        for (const [k, v] of Object.entries(move.query ?? {})) target.searchParams.set(k, v);
        return target;
    }
    return null;
}

/**
 * A locale-less path with a sorted query, after following any move — the form
 * the RBAC parity test compares destinations in.
 */
export function canonicalHref(href: string): string {
    const url = new URL(href, "http://rentaxis.local");
    const moved = legacyRedirect(url) ?? url;
    const prefix = moved.pathname.match(LOCALE_PREFIX)?.[0] ?? "";
    const sorted = [...moved.searchParams.entries()].sort(([a], [b]) => a.localeCompare(b));
    const qs = new URLSearchParams(sorted).toString();
    return (moved.pathname.slice(prefix.length) || "/") + (qs ? `?${qs}` : "");
}
```

- [ ] **Step 4: Run it — it passes**

Run: `cd /Users/kunalsharma/datagami/rentaxis/web && npx vitest run src/lib/nav/__tests__/routeMap.test.ts`
Expected: `Test Files  1 passed (1)`, 18 tests passed.

- [ ] **Step 5: Commit**

```bash
cd /Users/kunalsharma/datagami/rentaxis/web && git add src/lib/nav/types.ts src/lib/nav/routeMap.ts src/lib/nav/__tests__/routeMap.test.ts && git commit -m "feat(web): route map for the admin UI simplification

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- src/lib/nav/types.ts src/lib/nav/routeMap.ts src/lib/nav/__tests__/routeMap.test.ts
```

### Task 2: Permanent redirects in the middleware

**Files:** Modify `src/proxy.ts`, `src/__tests__/proxy.test.ts`.

**Interfaces**
- Consumes: `legacyRedirect(url: URL): URL | null` (Task 1).
- Produces: `middleware(req)` answers a moved route with `308` + `Location`; `config.matcher` includes `'/dashboard/:path*'`.

- [ ] **Step 1: Add failing tests** — append to `src/__tests__/proxy.test.ts` (the file already mocks `next-auth/jwt`, `next-intl/middleware` and `@/i18n/routing`); change its import line to `import middleware, { config, frameOptionsFor } from "../proxy";`:

```ts
describe("proxy middleware — moved dashboard routes", () => {
    const get = (path: string) => new NextRequest(`http://localhost:3000${path}`, { method: "GET" });

    it("answers a moved route with a permanent 308 that keeps locale and query", async () => {
        const res = await middleware(get("/ar/dashboard/settings/rent-settings?propertyId=p1"));
        expect(res.status).toBe(308);
        const loc = new URL(res.headers.get("location")!);
        expect(loc.pathname).toBe("/ar/dashboard/settings");
        expect(loc.searchParams.get("propertyId")).toBe("p1");
        expect(loc.searchParams.get("section")).toBe("rent");
    });

    it("keeps a ?tab= deep link through the redirect", async () => {
        const res = await middleware(get("/en/dashboard/settings/fiscal?tab=close"));
        expect(res.status).toBe(308);
        expect(new URL(res.headers.get("location")!).search).toBe("?tab=close");
    });

    it("still sets the security headers on the redirect", async () => {
        const res = await middleware(get("/en/dashboard/settings/gateway"));
        expect(res.headers.get("X-Frame-Options")).toBe("DENY");
    });

    it("leaves a route that did not move to next-intl", async () => {
        const res = await middleware(get("/en/dashboard/leases"));
        expect(res.status).toBe(200);
    });

    it("matches unprefixed dashboard paths so old unprefixed links redirect too", () => {
        expect(config.matcher).toContain("/dashboard/:path*");
    });
});
```

- [ ] **Step 2: Run — fails**

Run: `cd /Users/kunalsharma/datagami/rentaxis/web && npx vitest run src/__tests__/proxy.test.ts`
Expected: FAIL — status `200` where `308` expected; matcher assertion fails.

- [ ] **Step 3: Implement** — in `src/proxy.ts` add the import and, directly before `const response = intlMiddleware(req);`, the redirect; extend the matcher.

```ts
import { legacyRedirect } from "./lib/nav/routeMap";
```

```ts
    // Admin UI simplification: a moved page answers its old URL with a
    // permanent redirect that keeps the locale and every query parameter
    // (routeMap.ts). Runs before next-intl so the locale is never rewritten.
    const moved = legacyRedirect(new URL(req.nextUrl.toString()));
    if (moved) {
        return addSecurityHeaders(NextResponse.redirect(moved, 308), req.nextUrl.pathname);
    }

    const response = intlMiddleware(req);
```

```ts
export const config = {
    matcher: ['/', '/(ar|en)/:path*', '/dashboard/:path*', '/api/proxy/:path*']
};
```

- [ ] **Step 4: Run — passes**

Run: `cd /Users/kunalsharma/datagami/rentaxis/web && npx vitest run src/__tests__/proxy.test.ts`
Expected: all tests in the file pass (existing + 5 new).

- [ ] **Step 5: Commit**

```bash
cd /Users/kunalsharma/datagami/rentaxis/web && git add src/proxy.ts src/__tests__/proxy.test.ts && git commit -m "feat(web): 308 redirects for moved dashboard routes

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- src/proxy.ts src/__tests__/proxy.test.ts
```

### Task 3: Terminology pass — PACT RevenU labels (EN + AR values only)

Spec "Terminology — match PACT RevenU". Only message **values** change; keys, routes, API fields and identifiers stay. "Tenant" now means the renter in the UI, so every visible "Tenant" that means the organisation becomes "Organisation"/"Company" first.

**Files:** Modify `messages/en.json`, `messages/ar.json`, `src/components/tour/tours/*.ts`, `src/content/help/*.md`, `src/lib/helpArticles.ts`. Create `scripts/terminology-pass.py` (one-off, committed for review), `src/lib/__tests__/terminology.test.ts`.

**Interfaces**
- Consumes: nothing.
- Produces: `export const TERMS: Record<string, { en: string; ar: string }>` (in the test file — the single source the reviewer reads) and the new keys used by later tasks: `Navigation.contracts`, `Navigation.collections`, `Navigation.home`, `Navigation.accounting`, `Navigation.settings`, `Navigation.more`, `Navigation.sectionAdmin`, `Navigation.expandSection`, `Navigation.openUnitsPage`.

- [ ] **Step 1: Write the failing test**

```ts
// src/lib/__tests__/terminology.test.ts
import { describe, expect, it } from "vitest";
import ar from "../../../messages/ar.json";
import en from "../../../messages/en.json";

type Tree = { [k: string]: string | Tree };
const flatten = (tree: Tree, prefix = ""): [string, string][] =>
    Object.entries(tree).flatMap(([k, v]) =>
        typeof v === "string" ? [[prefix + k, v] as [string, string]] : flatten(v, `${prefix}${k}.`));
const EN = new Map(flatten(en as Tree));
const AR = new Map(flatten(ar as Tree));

/** Spec 2026-09-25 "Terminology" table, key by key. */
export const TERMS: Record<string, { en: string; ar: string }> = {
    // Organisation, never "Tenant"
    "Navigation.tenants": { en: "Organisations", ar: "المؤسسات" },
    "Roles.TENANT_ADMIN": { en: "Company Admin", ar: "مدير المؤسسة" },
    "Roles.TENANT_USER": { en: "Company User", ar: "مستخدم المؤسسة" },
    "Ledger.tenantWide": { en: "Company-wide", ar: "على مستوى المؤسسة" },
    "Ledger.defaultAccountsDesc": { en: "Company-wide accounts used when a property has no mapping.", ar: "الحسابات على مستوى المؤسسة تُستخدم عندما لا يوجد ربط للعقار." },
    // Renter → Tenant, Lease → Tenancy Contract
    "Roles.RENTER": { en: "Tenant", ar: "مستأجر" },
    "MasterData.renters": { en: "Tenants", ar: "المستأجرون" },
    "MasterData.renter": { en: "Tenant", ar: "المستأجر" },
    "MasterData.addRenter": { en: "Add Tenant", ar: "إضافة مستأجر" },
    "MasterData.leases": { en: "Tenancy Contracts", ar: "عقود الإيجار" },
    "Navigation.contracts": { en: "Contracts", ar: "العقود" },
    "Dashboard.newLease": { en: "New Contract", ar: "عقد جديد" },
    "Leasing.extend": { en: "Extend Contract", ar: "تمديد العقد" },
    "Leasing.renew": { en: "Renew", ar: "تجديد" },
    "Leasing.terminate": { en: "Terminate", ar: "إنهاء" },
    "Leasing.ledger": { en: "Ledger", ar: "دفتر الأستاذ" },
    "Ledger.tenantLedger": { en: "Tenant Ledger", ar: "دفتر أستاذ المستأجر" },
    // Particulars grid
    "Leasing.particulars": { en: "Particulars", ar: "البيان" },
    "Leasing.creditAccount": { en: "Credit A/c", ar: "الحساب الدائن" },
    "Leasing.grossAmount": { en: "Rent Amount", ar: "مبلغ الإيجار" },
    "Leasing.discount": { en: "Discount Amount", ar: "مبلغ الخصم" },
    "Leasing.afterDiscount": { en: "After Discount Amount", ar: "المبلغ بعد الخصم" },
    "Leasing.narration": { en: "Narration", ar: "البيان" },
    // Cheque grid
    "Leasing.postingDate": { en: "Posting Date", ar: "تاريخ القيد" },
    "Leasing.chequeNo": { en: "Cheque No", ar: "رقم الشيك" },
    "Leasing.chequeDate": { en: "Date", ar: "التاريخ" },
    "Leasing.payeeBank": { en: "Payee Bank", ar: "بنك المستفيد" },
    "Leasing.debitAccount": { en: "Debit A/c", ar: "الحساب المدين" },
    "Leasing.amount": { en: "Amount", ar: "المبلغ" },
    // Vouchers and reports
    "Cheques.receipt": { en: "Receipt Voucher", ar: "سند قبض" },
    "Vouchers.paymentVoucher": { en: "Payment Voucher", ar: "سند صرف" },
    "Vouchers.purchaseInvoice": { en: "Purchase Invoice", ar: "فاتورة مشتريات" },
    "Ledger.journals": { en: "Journal Voucher", ar: "سند قيد" },
    "PropertyReports.propertyPl": { en: "Property Profit Report", ar: "تقرير أرباح العقار" },
    "PropertyReports.companyPl": { en: "Profit & Loss", ar: "الأرباح والخسائر" },
    "PropertyReports.propertyStatement": { en: "Owner Statement", ar: "كشف حساب المالك" },
    "Ledger.fiscal": { en: "Year End Closing", ar: "إقفال نهاية السنة" },
    // Shell
    "Navigation.home": { en: "Home", ar: "الرئيسية" },
    "Navigation.collections": { en: "Cheque / Cash Collection", ar: "تحصيل الشيكات والنقد" },
    "Navigation.accounting": { en: "Accounting", ar: "المحاسبة" },
    "Navigation.settings": { en: "Settings", ar: "الإعدادات" },
    "Navigation.more": { en: "More", ar: "المزيد" },
    "Navigation.sectionAdmin": { en: "Administration", ar: "الإدارة" },
    "Navigation.expandSection": { en: "Show {name} pages", ar: "عرض صفحات {name}" },
    "Navigation.openUnitsPage": { en: "Open the full units page", ar: "فتح صفحة الوحدات الكاملة" },
    "TenantSwitcher.organization": { en: "Organisation", ar: "المؤسسة" },
};

describe("PACT terminology", () => {
    it.each(Object.entries(TERMS))("%s carries the PACT label in both locales", (key, want) => {
        expect(EN.get(key), `en ${key}`).toBe(want.en);
        expect(AR.get(key), `ar ${key}`).toBe(want.ar);
    });

    it("never shows the organisation as 'Tenant' in English", () => {
        // Keys whose value legitimately names the renter (the PACT meaning).
        const renterMeaning = /^(Ledger\.(tenant|tenantName|tenantLedger)|Cheques\.tenant|MasterData\.(currentTenant|renter|renters|addRenter)|Roles\.RENTER)$/;
        const orgy = [...EN].filter(([k, v]) => /\bTenant\s*(Admin|User|-wide|wide)|\bTenants\b.*organi[sz]ation/i.test(v) && !renterMeaning.test(k));
        expect(orgy).toEqual([]);
    });

    it("says Tenant / Contract, not Renter / Lease, in every English label", () => {
        // Allowlist: product names and API-facing copy that must keep the word.
        const allow = /^(Common\.errors\.)/;
        const stale = [...EN].filter(([k, v]) => !allow.test(k) && /\b(Renters?|renters?|Leases?|leases?)\b/.test(v));
        expect(stale.map(([k, v]) => `${k}: ${v}`)).toEqual([]);
    });

    it("calls a listing interest an Enquiry", () => {
        const stale = [...EN].filter(([k, v]) => k.startsWith("Listings.") && /\binterests?\b/i.test(v));
        expect(stale.map(([k, v]) => `${k}: ${v}`)).toEqual([]);
    });
});
```

- [ ] **Step 2: Run — fails**

Run: `cd /Users/kunalsharma/datagami/rentaxis/web && npx vitest run src/lib/__tests__/terminology.test.ts`
Expected: FAIL — e.g. `en Navigation.tenants: expected 'Tenants' to be 'Organisations'`, and the stale-word test lists ~246 keys (102 "Renter", 144 "Lease" values today).

- [ ] **Step 3: Apply the explicit table, then the word rules, with a reviewed script**

```python
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
    for pat, rep in WORDS:
        value = re.sub(pat, rep, value)
    if path.startswith("Listings."):
        for pat, rep in ENQUIRY:
            value = re.sub(pat, rep, value)
    return value

for locale in ("en", "ar"):
    fname = f"messages/{locale}.json"
    data = json.load(open(fname, encoding="utf-8"))
    if locale == "en":
        walk(data, "", english)
    for key, (e, a) in terms.items():
        set_path(data, key, e if locale == "en" else a)
    json.dump(data, open(fname, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    open(fname, "a", encoding="utf-8").write("\n")
print(f"applied {len(terms)} table terms")
```

Run: `cd /Users/kunalsharma/datagami/rentaxis/web && python3 scripts/terminology-pass.py && git diff --stat messages/`
Expected: `applied 49 table terms`; only `messages/en.json` and `messages/ar.json` change. Arabic word values are not rewritten by rule: عقد الإيجار / المستأجر already are the glossary's PACT terms; only the table keys change in `ar.json`.

- [ ] **Step 4: Read the diff by hand** — `git diff -U0 messages/en.json | grep '^[-+] ' | head -300`. Fix any value where "contract" now reads wrong (e.g. "Tenancy Contract Tenancy Contract", a sentence that already said "contract"), and any ICU plural (`{count, plural, one {# contract} other {# contracts}}`) — it must still parse (the catalog-parity test checks ICU). Do not touch keys.

- [ ] **Step 5: Tours and help text** — apply the same words to user-visible text:
  - `src/components/tour/tours/admin-onboarding.ts`: step `sidebar-leases` title `'Tenancy Contracts'`, text `'Create and manage tenancy contracts, link tenants to units, and track cheque schedules.'`; step `sidebar-finance` title `'Accounting'`, text `'Journal vouchers, registers, receipts & payments and final reports live behind this one door.'`; welcome text `'This is your home page — the numbers that matter and today\'s work.'`.
  - `src/components/tour/tours/finance-overview.ts`: `'Journal Vouchers'` → `'Journal Voucher'`; `'Cheque Register'` → `'Cheque / Cash Collection'`, text `'Deposit, clear, bounce and replace tenants\' cheques as they move through collection.'`.
  - `src/components/tour/tours/property-workflow.ts`, `renter-portal.ts`: replace "renter(s)" → "tenant(s)", "lease(s)" → "contract(s)" in `title`/`text` strings only.
  - `src/content/help/*.md` and the mirrored template literals in `src/lib/helpArticles.ts` (same articles, auto-generated registry — edit both identically): run the Step 3 word rules over the prose, then fix headings by hand. Nav paths in the prose are rewritten in Task 14.

- [ ] **Step 6: Run — passes, and nothing else broke**

Run: `cd /Users/kunalsharma/datagami/rentaxis/web && npx vitest run src/lib/__tests__/terminology.test.ts src/lib/__tests__/catalog-parity.test.ts src/lib/__tests__/help-article-roles.test.ts`
Expected: all pass. Then the full suite: `npx vitest run` — tests that assert the old English words (`getByText("Renters")`, `"Leases"`, `"New lease"`, `"Journal Vouchers"`, `"Property P&L"`, …) fail; update each assertion to read the value from `en.json` (`en.MasterData.renters`) rather than a literal, which is the repo's existing pattern (see `sidebar-role-gating.test.tsx` using `en.Ledger.journals`). Re-run until `Test Files  N passed`.

- [ ] **Step 7: Commit**

```bash
cd /Users/kunalsharma/datagami/rentaxis/web && git add scripts/terminology-pass.py src/lib/__tests__/terminology.test.ts messages/en.json messages/ar.json src/components/tour/tours src/content/help src/lib/helpArticles.ts && git commit -m "enhance(web): PACT RevenU terminology — Tenants, Tenancy Contracts, vouchers, reports

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- scripts/terminology-pass.py src/lib/__tests__/terminology.test.ts messages/en.json messages/ar.json src/components/tour/tours src/content/help src/lib/helpArticles.ts
```
(Add to the pathspec every test file Step 6 touched — list them with `git status --porcelain src`.)

### Task 4: Collections, Settings and Accounting models (pure)

**Files:** Create `src/lib/nav/collectionsModel.ts`, `src/lib/nav/settingsModel.ts`, `src/lib/nav/accountingNav.ts`, `src/lib/nav/useLabel.ts`, `src/lib/nav/__tests__/models.test.ts`. Modify `messages/en.json`, `messages/ar.json`.

**Interfaces**
- Consumes: `hasPermission`, `canConfigureGateway`, `canConfigureFines`, `canConfigureRentSettings`, `Permission`, `UserRole` from `src/lib/rbac.ts`; `Label` from `types.ts`; `FiscalSettings` from `src/lib/api/ledger.ts`.
- Produces:
  - `type CollectionsTabId = "deposit" | "due" | "overdue" | "returned" | "post-dated" | "penalties" | "all"`
  - `interface CollectionsTab { id: CollectionsTabId; href: string; label: Label; testId: string; permission: Permission }`
  - `function buildCollectionsTabs(role: UserRole | undefined): CollectionsTab[]`
  - `type SettingsSectionId = "organisation" | "users" | "rent" | "payments"`
  - `interface SettingsSection { id: SettingsSectionId; href: string; label: Label; testId: string }`
  - `function buildSettingsSections(role: UserRole | undefined): SettingsSection[]`
  - `type AccountingGroupId = "accounts" | "receiptsPayments" | "journalEntries" | "registers" | "receivablesPayables" | "bank" | "finalReports" | "yearEnd" | "setup"`
  - `interface AccountingItem { id: string; href: string; label: Label; testId: string; crossLink?: boolean }`
  - `interface AccountingGroup { id: AccountingGroupId; label: Label; items: AccountingItem[]; defaultOpen: boolean }`
  - `function buildAccountingNav(role: UserRole | undefined, opts: { booksLive: boolean }): AccountingGroup[]`
  - `function accountingHome(role: UserRole | undefined): string | null`
  - `function isBooksLive(fs: Pick<FiscalSettings, "booksStartDate" | "booksLockedThrough"> | null): boolean`
  - `function useLabel(): (label: Label) => string`

- [ ] **Step 1: Messages** — add to `messages/en.json` / `messages/ar.json` (same keys, same order in both):

```json
"Collections": {
  "tabDeposit": "To deposit", "tabDue": "Due", "tabOverdue": "Overdue",
  "tabReturned": "Returned / replace", "tabPostDated": "Post-dated",
  "tabPenalties": "Penalties", "tabAll": "Cheque register"
},
"AccountingNav": {
  "label": "Accounting sections",
  "groupAccounts": "Accounts", "groupReceiptsPayments": "Receipts & Payments",
  "groupJournalEntries": "Journal Entries", "groupRegisters": "Registers",
  "groupReceivablesPayables": "Receivables & Payables", "groupBank": "Bank",
  "groupFinalReports": "Final Reports", "groupYearEnd": "Year End Closing",
  "groupSetup": "One-time setup",
  "receiptPaymentVouchers": "Receipt / Payment Vouchers", "creditNote": "Credit Note",
  "chequeRegisters": "Cheque registers", "penalties": "Penalties",
  "cutoverReconciliation": "Cut-over reconciliation"
},
"SettingsPage": {
  "title": "Settings", "sectionsLabel": "Settings sections",
  "sectionOrganisation": "Organisation", "sectionUsers": "Users & staff",
  "sectionRent": "Rent & fines", "sectionPayments": "Payments"
}
```

```json
"Collections": {
  "tabDeposit": "للإيداع", "tabDue": "مستحقة", "tabOverdue": "متأخرة",
  "tabReturned": "مرتجعة / استبدال", "tabPostDated": "مؤجلة الدفع",
  "tabPenalties": "الغرامات", "tabAll": "سجل الشيكات"
},
"AccountingNav": {
  "label": "أقسام المحاسبة",
  "groupAccounts": "الحسابات", "groupReceiptsPayments": "المقبوضات والمدفوعات",
  "groupJournalEntries": "قيود اليومية", "groupRegisters": "السجلات",
  "groupReceivablesPayables": "الذمم المدينة والدائنة", "groupBank": "البنك",
  "groupFinalReports": "التقارير الختامية", "groupYearEnd": "إقفال نهاية السنة",
  "groupSetup": "إعداد لمرة واحدة",
  "receiptPaymentVouchers": "سندات القبض والصرف", "creditNote": "إشعار دائن",
  "chequeRegisters": "سجلات الشيكات", "penalties": "الغرامات",
  "cutoverReconciliation": "تسوية الانتقال"
},
"SettingsPage": {
  "title": "الإعدادات", "sectionsLabel": "أقسام الإعدادات",
  "sectionOrganisation": "المؤسسة", "sectionUsers": "المستخدمون والموظفون",
  "sectionRent": "الإيجار والغرامات", "sectionPayments": "المدفوعات"
}
```

- [ ] **Step 2: Write the failing test** (ports every finance assertion from today's `sidebar-role-gating.test.tsx` onto the models)

```ts
// src/lib/nav/__tests__/models.test.ts
import { describe, expect, it } from "vitest";
import type { UserRole } from "../../rbac";
import { accountingHome, buildAccountingNav, isBooksLive } from "../accountingNav";
import { buildCollectionsTabs } from "../collectionsModel";
import { buildSettingsSections } from "../settingsModel";

const ROLES: UserRole[] = ["SUPER_ADMIN", "TENANT_ADMIN", "PROPERTY_MANAGER", "ACCOUNTANT", "TENANT_USER", "RENTER", "SECURITY_GUARD"];
const hrefs = (role: UserRole, booksLive = true) =>
    buildAccountingNav(role, { booksLive }).flatMap(g => g.items.map(i => i.href));

describe("buildAccountingNav", () => {
    it("groups a tenant admin's pages the PACT Finance ribbon's way", () => {
        const groups = buildAccountingNav("TENANT_ADMIN", { booksLive: false });
        expect(groups.map(g => [g.id, g.items.map(i => i.id)])).toEqual([
            ["accounts", ["accounts", "opening-balances"]],
            ["receiptsPayments", ["vouchers", "payment-runs", "issued-cheques"]],
            ["journalEntries", ["journals", "credit-note"]],
            ["registers", ["general-ledger", "tenant-ledger", "trial-balance", "cheque-registers"]],
            ["receivablesPayables", ["aging", "opening-items", "vendors", "recognition", "penalties"]],
            ["bank", ["bank-accounts", "bank-reconciliation"]],
            ["finalReports", ["balance-sheet", "company-pl", "property-pl", "property-statement", "vat-return"]],
            ["yearEnd", ["fiscal"]],
            ["setup", ["account-template", "charge-types", "import-batches", "reconciliation"]],
        ]);
    });

    it("offers an accountant NO link whose controller refuses the role", () => {
        const links = hrefs("ACCOUNTANT");
        expect(links).not.toContain("/dashboard/finance/bank-accounts");
        expect(links).toEqual(expect.arrayContaining([
            "/dashboard/finance/payables/payment-runs",
            "/dashboard/finance/payables/issued-cheques",
            "/dashboard/finance/account-template",
            "/dashboard/finance/fiscal",
        ]));
    });

    it("offers a property manager the property reports, balance sheet and payables aging only", () => {
        const own = buildAccountingNav("PROPERTY_MANAGER", { booksLive: true })
            .flatMap(g => g.items).filter(i => !i.crossLink).map(i => i.href);
        expect(own.sort()).toEqual([
            "/dashboard/finance/payables/aging",
            "/dashboard/finance/reports/balance-sheet",
            "/dashboard/finance/reports/property-pl",
            "/dashboard/finance/reports/property-statement",
        ]);
    });

    it("never yields an empty group", () => {
        for (const role of ROLES) {
            for (const booksLive of [true, false]) {
                for (const g of buildAccountingNav(role, { booksLive })) {
                    expect(g.items.length, `${role} ${g.id}`).toBeGreaterThan(0);
                }
            }
        }
    });

    it("never yields a group of cross-links alone", () => {
        for (const role of ROLES) {
            for (const g of buildAccountingNav(role, { booksLive: true })) {
                expect(g.items.some(i => !i.crossLink), `${role} ${g.id}`).toBe(true);
            }
        }
    });

    it("opens One-time setup until the books are live, then collapses it", () => {
        const setup = (live: boolean) => buildAccountingNav("TENANT_ADMIN", { booksLive: live }).find(g => g.id === "setup")!;
        expect(setup(false).defaultOpen).toBe(true);
        expect(setup(true).defaultOpen).toBe(false);
    });

    it("lands on Journal Voucher for finance roles and the Property Profit Report for a manager", () => {
        expect(accountingHome("TENANT_ADMIN")).toBe("/dashboard/finance/journals");
        expect(accountingHome("ACCOUNTANT")).toBe("/dashboard/finance/journals");
        expect(accountingHome("PROPERTY_MANAGER")).toBe("/dashboard/finance/reports/property-pl");
        expect(accountingHome("TENANT_USER")).toBeNull();
        expect(accountingHome("RENTER")).toBeNull();
        expect(accountingHome(undefined)).toBeNull();
    });
});

describe("isBooksLive", () => {
    it("is live once the lock date reaches the cut-over date", () => {
        expect(isBooksLive(null)).toBe(false);
        expect(isBooksLive({ booksStartDate: null, booksLockedThrough: null })).toBe(false);
        expect(isBooksLive({ booksStartDate: "2026-01-01", booksLockedThrough: null })).toBe(false);
        expect(isBooksLive({ booksStartDate: "2026-01-01", booksLockedThrough: "2025-12-31" })).toBe(false);
        expect(isBooksLive({ booksStartDate: "2026-01-01", booksLockedThrough: "2026-01-01" })).toBe(true);
    });
});

describe("buildCollectionsTabs", () => {
    it("gives cheque roles the cheque tabs and the penalty queue", () => {
        for (const role of ["TENANT_ADMIN", "ACCOUNTANT", "PROPERTY_MANAGER", "SUPER_ADMIN"] as UserRole[]) {
            expect(buildCollectionsTabs(role).map(t => t.id), role).toEqual(["deposit", "returned", "post-dated", "penalties", "all"]);
        }
    });
    it("gives everyone else nothing", () => {
        for (const role of ["TENANT_USER", "RENTER", "SECURITY_GUARD"] as UserRole[]) {
            expect(buildCollectionsTabs(role), role).toEqual([]);
        }
        expect(buildCollectionsTabs(undefined)).toEqual([]);
    });
});

describe("buildSettingsSections", () => {
    it("gives a tenant admin all four sections", () => {
        expect(buildSettingsSections("TENANT_ADMIN").map(s => s.href)).toEqual([
            "/dashboard/settings?section=organisation",
            "/dashboard/settings?section=users",
            "/dashboard/settings?section=rent",
            "/dashboard/settings?section=payments",
        ]);
    });
    it("gives no other role a section", () => {
        for (const role of ["PROPERTY_MANAGER", "ACCOUNTANT", "TENANT_USER", "RENTER", "SECURITY_GUARD"] as UserRole[]) {
            expect(buildSettingsSections(role), role).toEqual([]);
        }
    });
});
```

- [ ] **Step 3: Run — fails** (`npx vitest run src/lib/nav/__tests__/models.test.ts` → unresolved imports).

- [ ] **Step 4: Implement**

```ts
// src/lib/nav/useLabel.ts
"use client";
import { useTranslations } from "next-intl";
import type { Label } from "./types";

/** Resolves a model label with the root translator (`ns.key`). */
export function useLabel(): (label: Label) => string {
    const t = useTranslations();
    return (label: Label) => t(`${label.ns}.${label.key}`);
}
```

```ts
// src/lib/nav/collectionsModel.ts
import { hasPermission, type Permission, type UserRole } from "../rbac";
import type { Label } from "./types";

export type CollectionsTabId = "deposit" | "due" | "overdue" | "returned" | "post-dated" | "penalties" | "all";
export interface CollectionsTab { id: CollectionsTabId; href: string; label: Label; testId: string; permission: Permission }

const tab = (id: CollectionsTabId, href: string, key: string, permission: Permission): CollectionsTab =>
    ({ id, href, label: { ns: "Collections", key }, testId: `collections-tab-${id}`, permission });

/**
 * The Cheque / Cash Collection views. PR 1 points at today's pages; PR 2
 * (Task 18) points every tab at the hub (`/dashboard/collections?tab=…`) and
 * adds Due and Overdue. Gates are the pages' own: the cheque register's
 * canManageCheques and the penalty queue's canProposePenalties.
 */
const TABS: CollectionsTab[] = [
    tab("deposit", "/dashboard/finance/cheques/collection", "tabDeposit", "canManageCheques"),
    tab("returned", "/dashboard/finance/cheques/return-replace", "tabReturned", "canManageCheques"),
    tab("post-dated", "/dashboard/finance/cheques/post-dated", "tabPostDated", "canManageCheques"),
    tab("penalties", "/dashboard/finance/penalties", "tabPenalties", "canProposePenalties"),
    tab("all", "/dashboard/finance/cheques", "tabAll", "canManageCheques"),
];

export function buildCollectionsTabs(role: UserRole | undefined): CollectionsTab[] {
    return TABS.filter(t => hasPermission(role, t.permission));
}
```

```ts
// src/lib/nav/settingsModel.ts
import { canConfigureFines, canConfigureGateway, canConfigureRentSettings, hasPermission, type UserRole } from "../rbac";
import type { Label } from "./types";

export type SettingsSectionId = "organisation" | "users" | "rent" | "payments";
export interface SettingsSection { id: SettingsSectionId; href: string; label: Label; testId: string }

/**
 * One Settings page, one section per former page group. Each section's gate
 * is the union of the gates of the pages it hosts, so nobody gains a screen:
 * Users & staff = canManageUsers (users list) ∪ canAccessFinanceOps (Staff);
 * Rent & fines = canConfigureFines ∪ canConfigureRentSettings; Payments =
 * canConfigureGateway. Organisation shows only the org's own name/slug and
 * rides on canConfigureGateway (the SA/TA set every other section needs).
 */
const SECTIONS: { id: SettingsSectionId; key: string; allow: (r: UserRole) => boolean }[] = [
    { id: "organisation", key: "sectionOrganisation", allow: canConfigureGateway },
    { id: "users", key: "sectionUsers", allow: r => hasPermission(r, "canManageUsers") || hasPermission(r, "canAccessFinanceOps") },
    { id: "rent", key: "sectionRent", allow: r => canConfigureFines(r) || canConfigureRentSettings(r) },
    { id: "payments", key: "sectionPayments", allow: canConfigureGateway },
];

export function buildSettingsSections(role: UserRole | undefined): SettingsSection[] {
    if (!role) return [];
    return SECTIONS.filter(s => s.allow(role)).map(s => ({
        id: s.id,
        href: `/dashboard/settings?section=${s.id}`,
        label: { ns: "SettingsPage", key: s.key },
        testId: `settings-nav-${s.id}`,
    }));
}
```

```ts
// src/lib/nav/accountingNav.ts
import type { FiscalSettings } from "../api/ledger";
import { hasPermission, type Permission, type UserRole } from "../rbac";
import { buildCollectionsTabs } from "./collectionsModel";
import type { Label } from "./types";

export type AccountingGroupId =
    | "accounts" | "receiptsPayments" | "journalEntries" | "registers"
    | "receivablesPayables" | "bank" | "finalReports" | "yearEnd" | "setup";
export interface AccountingItem { id: string; href: string; label: Label; testId: string; crossLink?: boolean }
export interface AccountingGroup { id: AccountingGroupId; label: Label; items: AccountingItem[]; defaultOpen: boolean }

type ItemDef = AccountingItem & { allow: Permission };
const F = "/dashboard/finance";
const item = (id: string, href: string, ns: string, key: string, testId: string, allow: Permission, crossLink = false): ItemDef =>
    ({ id, href, label: { ns, key }, testId, allow, ...(crossLink ? { crossLink } : {}) });

/** Cheques and penalties live in Collections; the Accounting menu links there. */
function collectionsHref(id: "all" | "penalties"): string {
    const tabs = buildCollectionsTabs("SUPER_ADMIN");
    return tabs.find(t => t.id === id)!.href;
}

/**
 * The Accounting area, grouped the way the PACT RevenU Finance ribbon groups
 * it (spec "Terminology"). Every item keeps the exact gate its sidebar link
 * had before (MvpSidebar.tsx at 2026-09-25), so no role gains or loses a page.
 * Debit Note is not listed: there is no debit-note page to link to.
 */
const GROUPS: { id: AccountingGroupId; key: string; items: ItemDef[] }[] = [
    { id: "accounts", key: "groupAccounts", items: [
        item("accounts", `${F}/accounts`, "MasterData", "chartOfAccounts", "sidebar-accounts", "canAccessFinance"),
        item("opening-balances", `${F}/opening-balances`, "Cutover", "openingBalances", "sidebar-opening-balances", "canManageOpeningBalances"),
    ] },
    { id: "receiptsPayments", key: "groupReceiptsPayments", items: [
        item("vouchers", `${F}/vouchers`, "AccountingNav", "receiptPaymentVouchers", "sidebar-vouchers", "canManageVouchers"),
        item("payment-runs", `${F}/payables/payment-runs`, "Payables", "paymentRuns", "sidebar-payables-runs", "canManagePayables"),
        item("issued-cheques", `${F}/payables/issued-cheques`, "Payables", "issuedCheques", "sidebar-payables-issued-cheques", "canManagePayables"),
    ] },
    { id: "journalEntries", key: "groupJournalEntries", items: [
        item("journals", `${F}/journals`, "Ledger", "journals", "sidebar-journals", "canAccessFinance"),
        item("credit-note", `${F}/vouchers/credit-note`, "AccountingNav", "creditNote", "sidebar-credit-note", "canManageVouchers"),
    ] },
    { id: "registers", key: "groupRegisters", items: [
        item("general-ledger", `${F}/general-ledger`, "Ledger", "generalLedger", "sidebar-general-ledger", "canAccessFinance"),
        item("tenant-ledger", `${F}/tenant-ledger`, "Ledger", "tenantLedger", "sidebar-tenant-ledger", "canAccessFinance"),
        item("trial-balance", `${F}/trial-balance`, "Ledger", "trialBalance", "sidebar-trial-balance", "canAccessFinance"),
        item("cheque-registers", collectionsHref("all"), "AccountingNav", "chequeRegisters", "sidebar-cheques-register", "canManageCheques", true),
    ] },
    { id: "receivablesPayables", key: "groupReceivablesPayables", items: [
        item("aging", `${F}/payables/aging`, "Payables", "aging", "sidebar-payables-aging", "canViewPayablesAging"),
        item("opening-items", `${F}/payables/opening-items`, "Payables", "openingItems", "sidebar-payables-opening", "canManagePayables"),
        item("vendors", `${F}/vendors`, "Vendors", "title", "sidebar-vendors", "canManageVendors"),
        item("recognition", `${F}/recognition`, "Recognition", "title", "sidebar-recognition", "canRunRecognition"),
        item("penalties", collectionsHref("penalties"), "AccountingNav", "penalties", "sidebar-penalties", "canProposePenalties", true),
    ] },
    { id: "bank", key: "groupBank", items: [
        item("bank-accounts", `${F}/bank-accounts`, "BankAccounts", "title", "sidebar-bank-accounts", "canAccessFinanceOps"),
        item("bank-reconciliation", `${F}/bank-reconciliation`, "BankRec", "sidebar", "sidebar-bank-reconciliation", "canReconcileBank"),
    ] },
    { id: "finalReports", key: "groupFinalReports", items: [
        item("balance-sheet", `${F}/reports/balance-sheet`, "PropertyReports", "balanceSheet", "sidebar-balance-sheet", "canViewPropertyReports"),
        item("company-pl", `${F}/reports/company-pl`, "PropertyReports", "companyPl", "sidebar-company-pl", "canViewCompanyReports"),
        item("property-pl", `${F}/reports/property-pl`, "PropertyReports", "propertyPl", "sidebar-property-pl", "canViewPropertyReports"),
        item("property-statement", `${F}/reports/property-statement`, "PropertyReports", "propertyStatement", "sidebar-property-statement", "canViewPropertyReports"),
        item("vat-return", `${F}/reports/vat-return`, "PropertyReports", "vatReturn", "sidebar-vat-return", "canViewVatReturn"),
    ] },
    { id: "yearEnd", key: "groupYearEnd", items: [
        item("fiscal", `${F}/fiscal`, "Ledger", "fiscal", "sidebar-fiscal", "canManageAccountSetup"),
    ] },
    { id: "setup", key: "groupSetup", items: [
        item("account-template", `${F}/account-template`, "Ledger", "accountTemplate", "sidebar-account-template", "canManageAccountSetup"),
        item("charge-types", `${F}/charge-types`, "Ledger", "chargeTypes", "sidebar-charge-types", "canManageAccountSetup"),
        item("import-batches", `${F}/import-batches`, "Cutover", "importBatches", "sidebar-import-batches", "canManageImportBatches"),
        item("reconciliation", `${F}/reconciliation`, "AccountingNav", "cutoverReconciliation", "sidebar-reconciliation", "canManageOpeningBalances"),
    ] },
];

export function buildAccountingNav(role: UserRole | undefined, opts: { booksLive: boolean }): AccountingGroup[] {
    return GROUPS
        .map(g => ({
            id: g.id,
            label: { ns: "AccountingNav", key: g.key },
            items: g.items.filter(i => hasPermission(role, i.allow)).map(({ allow: _allow, ...rest }) => rest),
            defaultOpen: g.id === "setup" && !opts.booksLive,
        }))
        .filter(g => g.items.some(i => !i.crossLink));
}

/** Where the Accounting entry lands: Journal Voucher, else the Property Profit Report, else the first own page. */
export function accountingHome(role: UserRole | undefined): string | null {
    const own = buildAccountingNav(role, { booksLive: true }).flatMap(g => g.items).filter(i => !i.crossLink);
    return (own.find(i => i.id === "journals") ?? own.find(i => i.id === "property-pl") ?? own[0])?.href ?? null;
}

/** The books are live once the period lock reaches the cut-over (books-start) date. */
export function isBooksLive(fs: Pick<FiscalSettings, "booksStartDate" | "booksLockedThrough"> | null): boolean {
    if (!fs?.booksStartDate || !fs.booksLockedThrough) return false;
    return fs.booksLockedThrough >= fs.booksStartDate;
}
```

- [ ] **Step 5: Run — passes** (`npx vitest run src/lib/nav/__tests__/models.test.ts` → 13 passed). Also `npx vitest run src/lib/__tests__/catalog-parity.test.ts` → passes (new namespaces present in both catalogs).

- [ ] **Step 6: Commit**

```bash
cd /Users/kunalsharma/datagami/rentaxis/web && git add src/lib/nav/collectionsModel.ts src/lib/nav/settingsModel.ts src/lib/nav/accountingNav.ts src/lib/nav/useLabel.ts src/lib/nav/__tests__/models.test.ts messages/en.json messages/ar.json && git commit -m "feat(web): collections, settings and PACT-grouped accounting nav models

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- src/lib/nav/collectionsModel.ts src/lib/nav/settingsModel.ts src/lib/nav/accountingNav.ts src/lib/nav/useLabel.ts src/lib/nav/__tests__/models.test.ts messages/en.json messages/ar.json
```

### Task 5: `navModel` — rail sections + panel items per role (spec §1a)

**Files:** Create `src/lib/nav/navModel.ts`, `src/lib/nav/__tests__/navModel.test.ts`. Modify `messages/en.json`, `messages/ar.json`.

**Interfaces**
- Consumes: `NavContext`, `Label` (types.ts); `buildAccountingNav`, `accountingHome` (Task 4); `buildCollectionsTabs`; `buildSettingsSections`; `hasPermission`, `UserRole`.
- Produces:
  - `type RailId = "home" | "leasing" | "collection" | "accounting" | "operations" | "settings" | "more"`
  - `interface PanelItem { id: string; href: string; label: Label; testId: string; exact?: boolean }`
  - `interface PanelGroup { id: string; label: Label | null; items: PanelItem[]; defaultOpen: boolean }`
  - `type StatusCardKind = "booksLocked" | "chequesToDeposit"`
  - `interface RailSection { id: RailId; href: string; label: Label; tourId: string; match: string[]; groups: PanelGroup[]; savedViews: PanelItem[]; badge: "collection" | null; statusCard: StatusCardKind | null }`
  - `interface NavModelContext extends NavContext { booksLive: boolean }`
  - `function buildNav(ctx: NavModelContext): RailSection[]`
  - `function flattenNav(rail: RailSection[]): string[]`
  - `function activeNav(pathname: string, rail: RailSection[]): { section: RailId | null; item: string | null }`

Decisions recorded here (spec §1a lists them; the plan pins them):
- **Home panel:** Today (`/dashboard`, exact) · Unit Status (`/dashboard#unit-status`).
- **Leasing panel:** Tenancy Contracts · Tenants · Properties & Units · Enquiry (`/dashboard/listings`, the listing page whose Interests drawer holds the enquiries; LISTINGS flag). *Unit Reservation* has no page of its own (reserved units only show inside a property's units) and is omitted — no new pages.
- **Collection panel:** the Collections tabs (Task 4). *Security Deposit* has no page of its own and is omitted.
- **Operations panel:** Tickets · Bookings · Staff (`/dashboard/staff`, unchanged URL).
- **Settings panel:** the Settings sections; SUPER_ADMIN adds an Administration group (Organisations `/superadmin/tenants`, Users `/superadmin/users`). *Notifications* has no settings to show (no notification-preference page or endpoint exists) and is omitted.
- **More:** Meetings [MEETINGS] · Gate pass [GATEPASS, now actually gated] · Promotions. Listings is reached as Leasing › Enquiry.
- **Renter:** one Home section whose panel is today's renter list.
- A section with no visible items is not returned (so its rail icon never shows).

- [ ] **Step 1: Messages** — `en.json` → `"Navigation"` gains: `"leasing": "Leasing"`, `"operations": "Operations"`, `"today": "Today"`, `"unitStatus": "Unit Status"`, `"tenancyContracts": "Tenancy Contracts"`, `"propertiesAndUnits": "Properties & Units"`, `"enquiry": "Enquiry"`, `"staff": "Staff"`, `"pinned": "Pinned views"`, `"sectionPanel": "{name} pages"`, `"openMenu": "Open navigation"`, `"closeMenu": "Close navigation"`, `"hidePanel": "Hide panel"`, `"showPanel": "Show panel"`, `"booksLockedThrough": "Books locked through {date}"`, `"booksNotLocked": "Books not locked yet"`, `"chequesToDeposit": "{count, plural, one {# cheque to deposit} other {# cheques to deposit}}"`, `"badgeLabel": "{count} need attention"`, `"breadcrumb": "Breadcrumb"`. `ar.json` → same keys: `"المستأجرون والعقود"` is NOT used; values: `"leasing": "التأجير"`, `"operations": "العمليات"`, `"today": "اليوم"`, `"unitStatus": "حالة الوحدات"`, `"tenancyContracts": "عقود الإيجار"`, `"propertiesAndUnits": "العقارات والوحدات"`, `"enquiry": "الاستفسارات"`, `"staff": "الموظفون"`, `"pinned": "العروض المثبتة"`, `"sectionPanel": "صفحات {name}"`, `"openMenu": "فتح القائمة"`, `"closeMenu": "إغلاق القائمة"`, `"hidePanel": "إخفاء اللوحة"`, `"showPanel": "إظهار اللوحة"`, `"booksLockedThrough": "الدفاتر مقفلة حتى {date}"`, `"booksNotLocked": "الدفاتر غير مقفلة بعد"`, `"chequesToDeposit": "{count, plural, zero {لا شيكات للإيداع} one {شيك واحد للإيداع} two {شيكان للإيداع} few {# شيكات للإيداع} many {# شيكاً للإيداع} other {# شيك للإيداع}}"`, `"badgeLabel": "{count} بحاجة إلى متابعة"`, `"breadcrumb": "مسار التنقل"`.

- [ ] **Step 2: Write the failing test**

```ts
// src/lib/nav/__tests__/navModel.test.ts
import { describe, expect, it } from "vitest";
import ar from "../../../../messages/ar.json";
import en from "../../../../messages/en.json";
import type { UserRole } from "../../rbac";
import { activeNav, buildNav, flattenNav, type RailSection } from "../navModel";

const ROLES: UserRole[] = ["SUPER_ADMIN", "TENANT_ADMIN", "PROPERTY_MANAGER", "ACCOUNTANT", "TENANT_USER", "RENTER", "SECURITY_GUARD"];
const FLAGS = ["LISTINGS", "MEETINGS", "GATEPASS"] as const;
const ctx = (role: UserRole, on: readonly string[] = FLAGS, booksLive = true) =>
    ({ role, isEnabled: (f: string) => on.includes(f), tenantSlug: "acme", booksLive });
const shape = (rail: RailSection[]) =>
    rail.map(s => [s.id, s.groups.flatMap(g => g.items.map(i => i.id))]);

describe("buildNav — rail sections and panel items per role (all flags on)", () => {
    it("TENANT_ADMIN", () => {
        const rail = buildNav(ctx("TENANT_ADMIN"));
        expect(shape(rail).map(([id]) => id)).toEqual(["home", "leasing", "collection", "accounting", "operations", "settings", "more"]);
        expect(shape(rail)).toEqual([
            ["home", ["today", "unit-status"]],
            ["leasing", ["contracts", "tenants", "properties", "enquiry"]],
            ["collection", ["deposit", "returned", "post-dated", "penalties", "all"]],
            ["accounting", ["accounts", "opening-balances", "vouchers", "payment-runs", "issued-cheques", "journals", "credit-note",
                "general-ledger", "tenant-ledger", "trial-balance", "cheque-registers", "aging", "opening-items", "vendors",
                "recognition", "penalties", "bank-accounts", "bank-reconciliation", "balance-sheet", "company-pl", "property-pl",
                "property-statement", "vat-return", "fiscal", "account-template", "charge-types", "import-batches", "reconciliation"]],
            ["operations", ["tickets", "bookings", "staff"]],
            ["settings", ["organisation", "users", "rent", "payments"]],
            ["more", ["meetings", "gatepass", "promotions"]],
        ]);
    });

    it("SUPER_ADMIN adds the Administration group to Settings", () => {
        const settings = buildNav(ctx("SUPER_ADMIN")).find(s => s.id === "settings")!;
        expect(settings.groups.map(g => [g.id, g.items.map(i => i.href)])).toEqual([
            ["sections", ["/dashboard/settings?section=organisation", "/dashboard/settings?section=users", "/dashboard/settings?section=rent", "/dashboard/settings?section=payments"]],
            ["admin", ["/superadmin/tenants", "/superadmin/users"]],
        ]);
    });

    it("PROPERTY_MANAGER", () => {
        expect(shape(buildNav(ctx("PROPERTY_MANAGER")))).toEqual([
            ["home", ["today", "unit-status"]],
            ["leasing", ["contracts", "tenants", "properties", "enquiry"]],
            ["collection", ["deposit", "returned", "post-dated", "penalties", "all"]],
            ["accounting", ["cheque-registers", "aging", "penalties", "balance-sheet", "property-pl", "property-statement"]],
            ["operations", ["tickets", "bookings"]],
            ["more", ["meetings", "gatepass"]],
        ]);
    });

    it("ACCOUNTANT sees Home, Leasing (contracts only), Collection and Accounting", () => {
        const rail = buildNav(ctx("ACCOUNTANT"));
        expect(rail.map(s => s.id)).toEqual(["home", "leasing", "collection", "accounting"]);
        expect(rail[1].groups.flatMap(g => g.items.map(i => i.id))).toEqual(["contracts"]);
    });

    it("TENANT_USER and SECURITY_GUARD see Home only — no My Unit link", () => {
        for (const role of ["TENANT_USER", "SECURITY_GUARD"] as UserRole[]) {
            const rail = buildNav(ctx(role));
            expect(rail.map(s => s.id), role).toEqual(["home"]);
            expect(flattenNav(rail), role).not.toContain("/dashboard/my-unit");
        }
    });

    it("RENTER keeps its own list under Home", () => {
        expect(flattenNav(buildNav(ctx("RENTER")))).toEqual([
            "/dashboard", "/dashboard/renter-portal", "/dashboard/renter-portal/payments",
            "/dashboard/renter-portal/penalties", "/dashboard/tickets", "/marketplace/acme", "/dashboard/meetings",
        ]);
    });

    it("never links a tenant admin to /superadmin/users", () => {
        expect(flattenNav(buildNav(ctx("TENANT_ADMIN")))).not.toContain("/superadmin/users");
    });
});

describe("flags", () => {
    it("hides More when nothing in it is enabled for a property manager", () => {
        expect(buildNav(ctx("PROPERTY_MANAGER", [])).map(s => s.id)).not.toContain("more");
    });
    it("gates Gate pass on GATEPASS", () => {
        const ids = (on: string[]) => buildNav(ctx("TENANT_ADMIN", on)).find(s => s.id === "more")!.groups[0].items.map(i => i.id);
        expect(ids(["MEETINGS"])).toEqual(["meetings", "promotions"]);
        expect(ids(["GATEPASS"])).toEqual(["gatepass", "promotions"]);
    });
    it("gates Enquiry on LISTINGS", () => {
        const leasing = buildNav(ctx("TENANT_ADMIN", [])).find(s => s.id === "leasing")!;
        expect(leasing.groups[0].items.map(i => i.id)).toEqual(["contracts", "tenants", "properties"]);
    });
});

describe("shape guarantees", () => {
    it("never yields an empty section or group for any role × flag combination", () => {
        const combos = [[], ["LISTINGS"], ["MEETINGS"], ["GATEPASS"], ["LISTINGS", "MEETINGS"], ["LISTINGS", "GATEPASS"], ["MEETINGS", "GATEPASS"], [...FLAGS]];
        for (const role of ROLES) {
            for (const on of combos) {
                for (const booksLive of [true, false]) {
                    for (const s of buildNav(ctx(role, on, booksLive))) {
                        expect(s.groups.length, `${role} ${on} ${s.id}`).toBeGreaterThan(0);
                        for (const g of s.groups) expect(g.items.length, `${role} ${on} ${s.id}/${g.id}`).toBeGreaterThan(0);
                        expect(flattenNav([s]), `${role} ${s.id} href`).toContain(s.href);
                    }
                }
            }
        }
    });

    it("lands each rail icon on a page of its own panel", () => {
        for (const role of ROLES) {
            for (const s of buildNav(ctx(role))) {
                const own = s.groups.flatMap(g => g.items.map(i => i.href));
                expect(own, `${role} ${s.id}`).toContain(s.href);
            }
        }
    });

    it("resolves every label in both catalogs", () => {
        const has = (cat: unknown, ns: string, key: string) =>
            typeof (cat as Record<string, Record<string, unknown>>)[ns]?.[key] === "string";
        for (const role of ROLES) {
            for (const s of buildNav(ctx(role))) {
                const labels = [s.label, ...s.groups.flatMap(g => [g.label, ...g.items.map(i => i.label)]), ...s.savedViews.map(v => v.label)]
                    .filter((l): l is { ns: string; key: string } => l !== null);
                for (const l of labels) {
                    expect(has(en, l.ns, l.key), `en ${l.ns}.${l.key}`).toBe(true);
                    expect(has(ar, l.ns, l.key), `ar ${l.ns}.${l.key}`).toBe(true);
                }
            }
        }
    });

    it("badges only Collection and only for cheque roles", () => {
        expect(buildNav(ctx("TENANT_ADMIN")).filter(s => s.badge).map(s => s.id)).toEqual(["collection"]);
        expect(buildNav(ctx("TENANT_USER")).filter(s => s.badge)).toEqual([]);
    });
});

describe("activeNav", () => {
    const rail = buildNav(ctx("TENANT_ADMIN"));
    it.each([
        ["/en/dashboard", "home", "today"],
        ["/ar/dashboard/leases/abc", "leasing", "contracts"],
        ["/en/dashboard/finance/journals/new", "accounting", "journals"],
        ["/en/dashboard/finance/cheques/collection", "collection", "deposit"],
        ["/en/dashboard/finance/cheques", "collection", "all"],
        ["/en/dashboard/staff", "operations", "staff"],
        ["/en/dashboard/notifications", null, null],
    ])("%s → %s / %s", (path, section, item) => {
        expect(activeNav(path, rail)).toEqual({ section, item });
    });
});
```

- [ ] **Step 3: Run — fails** (`npx vitest run src/lib/nav/__tests__/navModel.test.ts` → unresolved import).

- [ ] **Step 4: Implement**

```ts
// src/lib/nav/navModel.ts
import { hasPermission, type Permission, type UserRole } from "../rbac";
import { accountingHome, buildAccountingNav } from "./accountingNav";
import { buildCollectionsTabs } from "./collectionsModel";
import { buildSettingsSections } from "./settingsModel";
import type { Label, NavContext } from "./types";

export type RailId = "home" | "leasing" | "collection" | "accounting" | "operations" | "settings" | "more";
export interface PanelItem { id: string; href: string; label: Label; testId: string; exact?: boolean }
export interface PanelGroup { id: string; label: Label | null; items: PanelItem[]; defaultOpen: boolean }
export type StatusCardKind = "booksLocked" | "chequesToDeposit";
export interface RailSection {
    id: RailId; href: string; label: Label; tourId: string; match: string[];
    groups: PanelGroup[]; savedViews: PanelItem[]; badge: "collection" | null; statusCard: StatusCardKind | null;
}
export interface NavModelContext extends NavContext { booksLive: boolean }

const N = (key: string): Label => ({ ns: "Navigation", key });
const pi = (id: string, href: string, label: Label, testId: string, exact = false): PanelItem =>
    ({ id, href, label, testId, ...(exact ? { exact } : {}) });
const one = (id: string, items: PanelItem[], label: Label | null = null, defaultOpen = true): PanelGroup[] =>
    items.length ? [{ id, label, items, defaultOpen }] : [];
const pathOf = (href: string) => href.split(/[?#]/)[0];

function section(id: RailId, key: string, tourId: string, match: string[], groups: PanelGroup[],
    extra: Partial<Pick<RailSection, "savedViews" | "badge" | "statusCard" | "href">> = {}): RailSection | null {
    const items = groups.flatMap(g => g.items);
    if (items.length === 0) return null;
    return {
        id, label: N(key), tourId, match, groups,
        href: extra.href ?? items[0].href,
        savedViews: extra.savedViews ?? [], badge: extra.badge ?? null, statusCard: extra.statusCard ?? null,
    };
}

/**
 * The two-level shell (spec §1a): an icon rail of sections, each with a
 * panel of pages. Every item keeps the gate its sidebar link had before
 * (MvpSidebar.tsx at 2026-09-25); the model only regroups. A section with no
 * visible item is dropped, so no role sees an empty rail icon.
 */
export function buildNav(ctx: NavModelContext): RailSection[] {
    const { role, isEnabled, tenantSlug, booksLive } = ctx;
    const can = (p: Permission) => hasPermission(role, p);
    const ops = can("canViewProperties");

    if (can("canViewRenterPortal")) {
        const items = [
            pi("today", "/dashboard", N("home"), "sidebar-home", true),
            pi("my-leases", "/dashboard/renter-portal", N("myLeases"), "sidebar-my-leases"),
            pi("my-payments", "/dashboard/renter-portal/payments", { ns: "OnlinePayments", key: "myPayments" }, "sidebar-my-payments"),
            pi("my-penalties", "/dashboard/renter-portal/penalties", N("myPenalties"), "sidebar-my-penalties"),
            pi("my-tickets", "/dashboard/tickets", N("myTickets"), "sidebar-my-tickets"),
            ...(isEnabled("LISTINGS") && tenantSlug ? [pi("listings", `/marketplace/${tenantSlug}`, N("listings"), "sidebar-listings")] : []),
            ...(isEnabled("MEETINGS") ? [pi("meetings", "/dashboard/meetings", N("meetings"), "sidebar-meetings")] : []),
        ];
        return [section("home", "home", "sidebar-home", ["/dashboard"], one("main", items))!];
    }

    const rail: (RailSection | null)[] = [];

    rail.push(section("home", "home", "sidebar-home", ["/dashboard"], one("main", [
        pi("today", "/dashboard", N("today"), "sidebar-home", true),
        ...(ops ? [pi("unit-status", "/dashboard#unit-status", N("unitStatus"), "sidebar-unit-status", true)] : []),
    ])));

    rail.push(section("leasing", "leasing", "sidebar-leases", ["/dashboard/leases", "/dashboard/renters", "/dashboard/properties", "/dashboard/listings"], one("main", [
        ...(can("canViewLeases") ? [pi("contracts", "/dashboard/leases", N("tenancyContracts"), "sidebar-leases")] : []),
        ...(ops ? [pi("tenants", "/dashboard/renters", { ns: "MasterData", key: "renters" }, "sidebar-renters")] : []),
        ...(ops ? [pi("properties", "/dashboard/properties", N("propertiesAndUnits"), "sidebar-properties")] : []),
        ...(ops && isEnabled("LISTINGS") ? [pi("enquiry", "/dashboard/listings", N("enquiry"), "sidebar-listings")] : []),
    ])));

    const tabs = buildCollectionsTabs(role);
    rail.push(section("collection", "collections", "sidebar-collections", COLLECTION_MATCH,
        one("main", tabs.map(t => pi(t.id, t.href, t.label, t.testId))),
        { badge: tabs.some(t => t.permission === "canManageCheques") ? "collection" : null,
          statusCard: tabs.some(t => t.permission === "canManageCheques") ? "chequesToDeposit" : null }));

    const acc = buildAccountingNav(role, { booksLive });
    rail.push(section("accounting", "accounting", "sidebar-finance", ["/dashboard/finance"],
        acc.map(g => ({ id: g.id, label: g.label, defaultOpen: g.defaultOpen || g.id !== "setup", items: g.items.map(i => pi(i.id, i.href, i.label, i.testId)) })),
        { href: accountingHome(role) ?? undefined, statusCard: can("canManageAccountSetup") ? "booksLocked" : null }));

    rail.push(section("operations", "operations", "sidebar-operations", ["/dashboard/tickets", "/dashboard/bookings", "/dashboard/staff"], one("main", [
        ...(ops ? [pi("tickets", "/dashboard/tickets", N("tickets"), "sidebar-tickets")] : []),
        ...(ops && can("canManageFacilities") ? [pi("bookings", "/dashboard/bookings", { ns: "Bookings", key: "navLabel" }, "sidebar-bookings")] : []),
        ...(can("canAccessFinanceOps") ? [pi("staff", "/dashboard/staff", N("staff"), "sidebar-staff")] : []),
    ])));

    const sections = buildSettingsSections(role);
    rail.push(section("settings", "settings", "sidebar-settings", ["/dashboard/settings", "/superadmin"], [
        ...one("sections", sections.map(s => pi(s.id, s.href, s.label, s.testId))),
        ...one("admin", can("canManageTenants") ? [
            pi("tenants", "/superadmin/tenants", N("tenants"), "sidebar-tenants"),
            pi("users", "/superadmin/users", N("users"), "sidebar-users"),
        ] : [], N("sectionAdmin")),
    ]));

    rail.push(section("more", "more", "sidebar-more", ["/dashboard/meetings", "/dashboard/gatepass", "/dashboard/promotions"], one("main", [
        ...(ops && isEnabled("MEETINGS") ? [pi("meetings", "/dashboard/meetings", N("meetings"), "sidebar-meetings")] : []),
        ...(ops && can("canViewGatePassReport") && isEnabled("GATEPASS") ? [pi("gatepass", "/dashboard/gatepass", { ns: "GatePass", key: "navLabel" }, "sidebar-gatepass")] : []),
        ...(ops && can("canManagePromotions") ? [pi("promotions", "/dashboard/promotions", { ns: "Promotions", key: "navLabel" }, "sidebar-promotions")] : []),
    ])));

    return rail.filter((s): s is RailSection => s !== null);
}

/** PR 1: the cheque/penalty pages; Task 18 (PR 2) replaces this with ["/dashboard/collections"]. */
const COLLECTION_MATCH = ["/dashboard/finance/cheques", "/dashboard/finance/penalties"];

export function flattenNav(rail: RailSection[]): string[] {
    return [...new Set(rail.flatMap(s => [...s.groups.flatMap(g => g.items.map(i => i.href)), ...s.savedViews.map(v => v.href)]))];
}

/**
 * The section and item to light for a pathname: exact items match only
 * themselves; everything else matches whole-segment prefixes and the longest
 * match wins, so /dashboard/finance/cheques lights Collection, not Accounting.
 */
export function activeNav(pathname: string, rail: RailSection[]): { section: RailId | null; item: string | null } {
    const path = pathname.replace(/^\/(en|ar)(?=\/|$)/, "") || "/";
    let best: { section: RailId; item: string | null; len: number } | null = null;
    const offer = (sectionId: RailId, item: string | null, prefix: string, exact: boolean) => {
        const hit = exact ? path === prefix : path === prefix || path.startsWith(`${prefix}/`);
        if (hit && (!best || prefix.length > best.len || (prefix.length === best.len && item && !best.item))) {
            best = { section: sectionId, item, len: prefix.length };
        }
    };
    for (const s of rail) {
        for (const m of s.match) offer(s.id, null, m, m === "/dashboard");
        for (const g of s.groups) for (const i of g.items) {
            if (i.href.includes("#")) continue;
            offer(s.id, i.id, pathOf(i.href), !!i.exact);
        }
    }
    const b = best as { section: RailId; item: string | null } | null;
    return b ? { section: b.section, item: b.item } : { section: null, item: null };
}
```

Note: in `navModel.test.ts` the `"lands each rail icon on a page of its own panel"` test holds because `section()` defaults `href` to the first item and Accounting's `accountingHome` is always one of its items.

- [ ] **Step 5: Run — passes** — `npx vitest run src/lib/nav/__tests__/navModel.test.ts` → all pass. If `activeNav` for `/en/dashboard/finance/cheques` returns `accounting`, the Collection `match` is missing — fix the model, not the test.

- [ ] **Step 6: Commit**

```bash
cd /Users/kunalsharma/datagami/rentaxis/web && git add src/lib/nav/navModel.ts src/lib/nav/__tests__/navModel.test.ts messages/en.json messages/ar.json && git commit -m "feat(web): rail + panel navigation model per role

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- src/lib/nav/navModel.ts src/lib/nav/__tests__/navModel.test.ts messages/en.json messages/ar.json
```

### Task 6: Route registry + filesystem sync test

**Files:** Create `src/lib/nav/routeRegistry.ts`, `src/lib/nav/__tests__/routeRegistry.test.ts`.

**Interfaces**
- Consumes: `hasPermission`, `Permission`, `UserRole`; `buildSettingsSections`; `ROUTE_MOVES`, `matchRoute`.
- Produces:
  - `type RouteAllow = "authenticated" | Permission | ((role: UserRole) => boolean)`
  - `interface RouteEntry { path: string; allow: RouteAllow; flag?: "LISTINGS" | "MEETINGS" | "GATEPASS" }`
  - `const DASHBOARD_ROUTES: RouteEntry[]`
  - `function routeAllows(entry: RouteEntry, role: UserRole): boolean`

- [ ] **Step 1: Write the failing test**

```ts
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
```

- [ ] **Step 2: Run — fails** (unresolved import).

- [ ] **Step 3: Implement** — gates are the ones the page or its sidebar link used on 2026-09-25; sub-pages inherit the parent's.

```ts
// src/lib/nav/routeRegistry.ts
import { hasPermission, type Permission, type UserRole } from "../rbac";
import { buildSettingsSections } from "./settingsModel";

export type RouteAllow = "authenticated" | Permission | ((role: UserRole) => boolean);
export interface RouteEntry { path: string; allow: RouteAllow; flag?: "LISTINGS" | "MEETINGS" | "GATEPASS" }

const r = (path: string, allow: RouteAllow, flag?: RouteEntry["flag"]): RouteEntry => ({ path, allow, ...(flag ? { flag } : {}) });
const D = "/dashboard";
const F = "/dashboard/finance";

/**
 * Every live dashboard/superadmin page and who should be able to reach it —
 * the Playwright sweep's route list and the filesystem guard's source of
 * truth. A moved page is NOT listed here; it is a `from` in routeMap.ts.
 */
export const DASHBOARD_ROUTES: RouteEntry[] = [
    r(D, "authenticated"), r(`${D}/help`, "authenticated"), r(`${D}/help/[slug]`, "authenticated"),
    r(`${D}/notifications`, "authenticated"), r(`${D}/profile`, "authenticated"),
    r(`${D}/properties`, "canViewProperties"), r(`${D}/properties/[id]`, "canViewProperties"), r(`${D}/properties/[id]/units`, "canViewProperties"),
    r(`${D}/renters`, "canViewProperties"), r(`${D}/renters/[id]`, "canViewProperties"),
    r(`${D}/leases`, "canViewLeases"), r(`${D}/leases/[id]`, "canViewLeases"),
    r(`${D}/leases/[id]/terminate`, "canPreviewTermination"), r(`${D}/leases/[id]/settlement`, "canViewSettlement"),
    r(`${D}/listings`, "canViewProperties", "LISTINGS"), r(`${D}/listings/[id]`, "canViewProperties", "LISTINGS"),
    r(`${D}/tickets`, "canViewProperties"), r(`${D}/tickets/[id]`, "canViewProperties"), r(`${D}/tickets/reports`, "canViewProperties"),
    r(`${D}/meetings`, "canManageMeetings", "MEETINGS"), r(`${D}/meetings/[id]`, "canManageMeetings", "MEETINGS"),
    r(`${D}/gatepass`, "canViewGatePassReport", "GATEPASS"),
    r(`${D}/bookings`, "canManageFacilities"), r(`${D}/promotions`, "canManagePromotions"),
    r(`${D}/staff`, "canAccessFinanceOps"),
    r(`${D}/settings`, role => buildSettingsSections(role).length > 0),
    r(`${F}/accounts`, "canAccessFinance"), r(`${F}/journals`, "canAccessFinance"), r(`${F}/journals/new`, "canPostJournals"),
    r(`${F}/journals/[id]`, "canAccessFinance"), r(`${F}/general-ledger`, "canAccessFinance"),
    r(`${F}/tenant-ledger`, "canAccessFinance"), r(`${F}/trial-balance`, "canAccessFinance"),
    r(`${F}/vouchers`, "canManageVouchers"), r(`${F}/vouchers/payment`, "canManageVouchers"),
    r(`${F}/vouchers/credit-note`, "canManageVouchers"), r(`${F}/vouchers/purchase-invoice`, "canManageVouchers"),
    r(`${F}/import-batches`, "canManageImportBatches"),
    r(`${F}/opening-balances`, "canManageOpeningBalances"), r(`${F}/reconciliation`, "canManageOpeningBalances"),
    r(`${F}/recognition`, "canRunRecognition"),
    r(`${F}/cheques`, "canManageCheques"), r(`${F}/cheques/collection`, "canManageCheques"),
    r(`${F}/cheques/return-replace`, "canManageCheques"), r(`${F}/cheques/post-dated`, "canManageCheques"),
    r(`${F}/penalties`, "canProposePenalties"),
    r(`${F}/vendors`, "canManageVendors"), r(`${F}/vendors/[id]`, "canManageVendors"),
    r(`${F}/bank-accounts`, "canAccessFinanceOps"),
    r(`${F}/bank-reconciliation`, "canReconcileBank"), r(`${F}/bank-reconciliation/[id]`, "canReconcileBank"),
    r(`${F}/payables/aging`, "canViewPayablesAging"), r(`${F}/payables/opening-items`, "canManagePayables"),
    r(`${F}/payables/payment-runs`, "canManagePayables"), r(`${F}/payables/payment-runs/new`, "canManagePayables"),
    r(`${F}/payables/payment-runs/[id]`, "canManagePayables"), r(`${F}/payables/issued-cheques`, "canManagePayables"),
    r(`${F}/reports/property-pl`, "canViewPropertyReports"), r(`${F}/reports/property-statement`, "canViewPropertyReports"),
    r(`${F}/reports/balance-sheet`, "canViewPropertyReports"), r(`${F}/reports/company-pl`, "canViewCompanyReports"),
    r(`${F}/reports/vat-return`, "canViewVatReturn"),
    r(`${F}/account-template`, "canManageAccountSetup"), r(`${F}/fiscal`, "canManageAccountSetup"), r(`${F}/charge-types`, "canManageAccountSetup"),
    r(`${D}/renter-portal`, "canViewRenterPortal"), r(`${D}/renter-portal/payments`, "canViewRenterPortal"),
    r(`${D}/renter-portal/penalties`, "canViewRenterPortal"), r(`${D}/renter-portal/facilities`, "canViewRenterPortal"),
    r(`${D}/renter-portal/renewals`, "canViewRenterPortal"), r(`${D}/renter-portal/renewal-intent`, "canViewRenterPortal"),
    r("/superadmin/tenants", "canManageTenants"), r("/superadmin/users", "canManageUsers"),
];

export function routeAllows(entry: RouteEntry, role: UserRole): boolean {
    if (entry.allow === "authenticated") return true;
    if (typeof entry.allow === "function") return entry.allow(role);
    return hasPermission(role, entry.allow);
}
```

The three `${F}/account-template|fiscal|charge-types` rows and `${D}/settings` fail "no route without a page" until Tasks 9 and 11 create those pages; mark them `it.todo` is **not** allowed — instead do Task 9 before running Step 4, or run Step 4 after Task 11 (order: Task 6 Step 1–3, Task 9, Task 11, then Task 6 Step 4–5).

- [ ] **Step 4: Run — passes** (`npx vitest run src/lib/nav/__tests__/routeRegistry.test.ts` → 3 passed).

- [ ] **Step 5: Commit** (`git add src/lib/nav/routeRegistry.ts src/lib/nav/__tests__/routeRegistry.test.ts && git commit -m "test(web): route registry kept in sync with the pages on disk" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- src/lib/nav/routeRegistry.ts src/lib/nav/__tests__/routeRegistry.test.ts`).

### Task 7: RBAC parity test (reachable destinations before == after, per role)

**Files:** Create `src/lib/nav/__tests__/legacyNav.fixture.ts`, `src/lib/nav/__tests__/rbacParity.test.ts`.

**Interfaces**
- Consumes: `buildNav`, `flattenNav`; `canonicalHref`; `UserRole`.
- Produces: `LEGACY_NAV: Record<UserRole, string[]>` (fixture), `reachable(role: UserRole): string[]` (test-local).

- [ ] **Step 1: Write the fixture** — the sidebar exactly as `MvpSidebar.tsx` rendered it on 2026-09-25 with every flag on and `tenantSlug = "acme"` (46 links for TENANT_ADMIN, 32 ACCOUNTANT, 19 PROPERTY_MANAGER — the inventory's counts). `rbac.ts` does not change, so this snapshot is also "what each role could reach from the nav before".

```ts
// src/lib/nav/__tests__/legacyNav.fixture.ts
import type { UserRole } from "../../rbac";

const D = "/dashboard";
const F = "/dashboard/finance";
const SHELL = [D, `${D}/help`];
const LEDGER = [`${F}/accounts`, `${F}/journals`, `${F}/general-ledger`, `${F}/tenant-ledger`, `${F}/trial-balance`];
const CUTOVER = [`${F}/vouchers`, `${F}/import-batches`, `${F}/opening-balances`, `${F}/reconciliation`, `${F}/recognition`];
const CHEQUES = [`${F}/cheques`, `${F}/cheques/collection`, `${F}/cheques/return-replace`, `${F}/cheques/post-dated`, `${F}/penalties`];
const PROPERTY_REPORTS = [`${F}/reports/property-pl`, `${F}/reports/property-statement`, `${F}/reports/balance-sheet`];
const FINANCE_REPORTS = [`${F}/reports/company-pl`, `${F}/reports/vat-return`, `${F}/payables/aging`,
    `${F}/payables/opening-items`, `${F}/payables/payment-runs`, `${F}/payables/issued-cheques`, `${F}/bank-reconciliation`];
const SETUP = [`${D}/settings/account-template`, `${D}/settings/fiscal`, `${D}/settings/charge-types`];
const WORKSPACE_PM = [`${D}/properties`, `${D}/renters`, `${D}/leases`, `${D}/listings`, `${D}/tickets`, `${D}/meetings`, `${D}/gatepass`, `${D}/bookings`];

const TENANT_ADMIN = [...SHELL, ...WORKSPACE_PM, `${D}/promotions`, "/superadmin/users",
    ...LEDGER, ...CUTOVER, ...CHEQUES, `${F}/vendors`, `${F}/bank-accounts`,
    ...PROPERTY_REPORTS, ...FINANCE_REPORTS, `${D}/staff`,
    ...SETUP, `${D}/settings/gateway`, `${D}/settings/rent-settings`, `${D}/settings/fines`];

export const LEGACY_NAV: Record<UserRole, string[]> = {
    SUPER_ADMIN: [...TENANT_ADMIN, "/superadmin/tenants"],
    TENANT_ADMIN,
    ACCOUNTANT: [...SHELL, `${D}/leases`, ...LEDGER, ...CUTOVER, ...CHEQUES, `${F}/vendors`,
        ...PROPERTY_REPORTS, ...FINANCE_REPORTS, ...SETUP],
    PROPERTY_MANAGER: [...SHELL, ...WORKSPACE_PM, ...CHEQUES, ...PROPERTY_REPORTS, `${F}/payables/aging`],
    TENANT_USER: [...SHELL, `${D}/my-unit`],
    RENTER: [...SHELL, `${D}/renter-portal`, `${D}/renter-portal/payments`, `${D}/renter-portal/penalties`,
        `${D}/tickets`, "/marketplace/acme", `${D}/meetings`],
    SECURITY_GUARD: [...SHELL],
};
```

- [ ] **Step 2: Write the test**

```ts
// src/lib/nav/__tests__/rbacParity.test.ts
import { describe, expect, it } from "vitest";
import type { UserRole } from "../../rbac";
import { buildNav, flattenNav } from "../navModel";
import { canonicalHref } from "../routeMap";
import { LEGACY_NAV } from "./legacyNav.fixture";

/** Always in the top header for every signed-in user (Help moved there from the sidebar). */
const HEADER = ["/dashboard/help", "/dashboard/notifications", "/dashboard/profile"];

/** Destinations that are new, each justified by a page the role could already reach. */
const NEW_DESTINATIONS: Record<string, string> = {
    "/dashboard/settings?section=organisation": "/dashboard/settings?section=payments",
    "/dashboard/collections?tab=due": "/dashboard/finance/cheques",
    "/dashboard/collections?tab=overdue": "/dashboard/finance/cheques",
    // Journal Entries › Credit Note: the page was already one click away on Vouchers (same canManageVouchers gate).
    "/dashboard/finance/vouchers/credit-note": "/dashboard/finance/vouchers",
};

/**
 * A tenant admin's "Users" link used to open /superadmin/users; the same
 * component now lives in Settings › Users & staff. Same screen, same gate
 * (canManageUsers), so the two are one destination for parity.
 */
function canonical(href: string, role: UserRole): string {
    if (href === "/superadmin/users" && role !== "SUPER_ADMIN") return canonicalHref("/dashboard/settings?section=users");
    return canonicalHref(href);
}

const pathOnly = (h: string) => h.split("?")[0];
const HUBS = new Set(["/dashboard/settings", "/dashboard/collections"]);

function reachable(role: UserRole): Set<string> {
    const rail = buildNav({ role, isEnabled: () => true, tenantSlug: "acme", booksLive: false });
    return new Set([...flattenNav(rail), ...HEADER].map(h => canonical(h.split("#")[0], role)));
}

const ROLES = Object.keys(LEGACY_NAV) as UserRole[];

describe("RBAC parity — every role reaches exactly what it reached before", () => {
    it.each(ROLES)("%s loses nothing", role => {
        const before = new Set([...LEGACY_NAV[role], ...HEADER].map(h => canonical(h, role)));
        const after = reachable(role);
        expect([...before].filter(h => !after.has(h))).toEqual([]);
    });

    it.each(ROLES)("%s gains nothing", role => {
        const before = new Set([...LEGACY_NAV[role], ...HEADER].map(h => canonical(h, role)));
        const beforePaths = new Set([...before].map(pathOnly));
        const gained = [...reachable(role)].filter(h => {
            if (before.has(h)) return false;
            const why = NEW_DESTINATIONS[h];
            if (why && before.has(canonical(why, role))) return false;
            // A saved view is a filter on a page the role already had (not a hub, where the query IS the page).
            if (!HUBS.has(pathOnly(h)) && beforePaths.has(pathOnly(h))) return false;
            return true;
        });
        expect(gained).toEqual([]);
    });
});
```

- [ ] **Step 3: Run** — `npx vitest run src/lib/nav/__tests__/rbacParity.test.ts`. Expected: 14 passed. (TENANT_USER's `/dashboard/my-unit` canonicalises to `/dashboard` through the route map, which is still reachable; Help is in `HEADER` on both sides.) If a role "loses" a destination, the model is wrong — fix `navModel.ts`, never the fixture.

- [ ] **Step 4: Mutation check** (memory: *mutation-check every guard*) — temporarily delete the `staff` item from `navModel.ts` Operations and re-run: `TENANT_ADMIN loses nothing` must fail listing `/dashboard/staff`. Temporarily change Bookings' gate to `canViewLeases`: `ACCOUNTANT gains nothing` must fail listing `/dashboard/bookings`. Revert both (`git checkout -- src/lib/nav/navModel.ts`) and re-run green.

- [ ] **Step 5: Commit** (`git add src/lib/nav/__tests__/legacyNav.fixture.ts src/lib/nav/__tests__/rbacParity.test.ts && git commit -m "test(web): RBAC parity between the old sidebar and the new shell" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- src/lib/nav/__tests__/legacyNav.fixture.ts src/lib/nav/__tests__/rbacParity.test.ts`).

### Task 8: The shell — icon rail + section panel, drawer on phones, Help and breadcrumb in the header

Spec §1a. `MvpSidebar.tsx` keeps its file name and default export (the layout imports it) but renders `buildNav`. Layout by width (Tailwind 4 breakpoints: `md` = 768 px, `xl` = 1280 px):
- **≥ 1280 px:** rail (64 px) + panel (240 px). A "Hide panel" toggle keeps the old `sidebar_collapsed` preference (persisted in `localStorage`), so the collapse feature survives.
- **768–1279 px:** rail only; clicking a rail icon opens its panel as an overlay beside the rail (closes on Escape, outside click or navigation).
- **< 768 px:** nothing inline; a menu button in the header opens a drawer holding rail + panel.
- **RTL:** the shell is a flex row in document order, so under `dir="rtl"` the rail sits on the right automatically; every edge class is logical.

**Files:** Create `src/components/nav/NavShellContext.tsx`, `src/components/nav/useNavCounts.ts`, `src/components/nav/SectionPanel.tsx`, `src/components/nav/__tests__/shell.test.tsx`. Modify `src/components/ui/MvpSidebar.tsx` (rewrite), `src/components/ui/TopHeader.tsx`, `src/components/layout/AuthenticatedLayout.tsx`, `src/components/ui/__tests__/sidebar-role-gating.test.tsx` (rewrite), `src/components/ui/__tests__/sidebar-localization.test.tsx`, `src/app/[locale]/dashboard/page.tsx` (one `id`).

**Interfaces**
- Consumes: `buildNav`, `activeNav`, `RailSection`, `PanelGroup` (Task 5); `useLabel` (Task 4); `useTenantFeatures`; `chequeApi.toDeposit`, `chequeApi.summary` (`src/lib/api/leasing.ts`); `ledgerApi.fiscal.get` (`src/lib/api/ledger.ts`); `isBooksLive`.
- Produces:
  - `NavShellProvider({ children })`, `useNavShell(): { drawerOpen: boolean; setDrawerOpen: (open: boolean) => void }`
  - `useNavCounts(role: UserRole | undefined): { collectionBadge: number | null; chequesToDeposit: number | null; booksLockedThrough: string | null; booksLive: boolean }`
  - `SectionPanel({ section, activeItem, counts }: { section: RailSection; activeItem: string | null; counts: NavCounts })`
  - `MvpSidebar()` (default export, unchanged name) and `export function activeNavHref(pathname: string, hrefs: string[]): string | null` (kept verbatim — existing tests import it).

- [ ] **Step 1: Write the failing tests**

```tsx
// src/components/nav/__tests__/shell.test.tsx
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import ar from "../../../../messages/ar.json";
import en from "../../../../messages/en.json";

const role = { current: "TENANT_ADMIN" };
const path = { current: "/en/dashboard" };
const flags = { current: ["LISTINGS", "MEETINGS", "GATEPASS"] as string[] };

vi.mock("next/navigation", () => ({ usePathname: () => path.current, useRouter: () => ({ push: vi.fn(), refresh: vi.fn() }) }));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
    useRouter: () => ({ push: vi.fn() }),
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: role.current, name: "U", tenantId: "t1" } } }) }));
vi.mock("@/hooks/useTenantFeatures", () => ({
    useTenantFeatures: () => ({ isEnabled: (f: string) => flags.current.includes(f), tenantSlug: "acme", features: {}, loading: false }),
}));
vi.mock("@/components/nav/useNavCounts", () => ({
    useNavCounts: () => ({ collectionBadge: 7, chequesToDeposit: 3, booksLockedThrough: "2025-12-31", booksLive: true }),
}));
vi.mock("@/components/ui/TenantSwitcher", () => ({ TenantSwitcher: () => <div data-testid="org-switcher" /> }));
vi.mock("next/image", () => ({ default: ({ alt }: { alt: string }) => <img alt={alt} /> }));

import MvpSidebar from "@/components/ui/MvpSidebar";
import { NavShellProvider } from "@/components/nav/NavShellContext";

function renderShell(locale: "en" | "ar" = "en") {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}>
            <NavShellProvider><MvpSidebar /></NavShellProvider>
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    role.current = "TENANT_ADMIN";
    path.current = "/en/dashboard";
    flags.current = ["LISTINGS", "MEETINGS", "GATEPASS"];
    Object.defineProperty(window, "localStorage", { value: { getItem: () => null, setItem: () => {}, removeItem: () => {} }, writable: true });
});
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe("icon rail", () => {
    it("shows the tenant admin's seven sections in order", () => {
        renderShell();
        const rail = screen.getByTestId("nav-rail");
        expect(Array.from(rail.querySelectorAll("[data-rail]")).map(a => a.getAttribute("data-rail"))).toEqual(
            ["home", "leasing", "collection", "accounting", "operations", "settings", "more"]);
    });

    it("shows an accountant four sections", () => {
        role.current = "ACCOUNTANT";
        renderShell();
        expect(Array.from(screen.getByTestId("nav-rail").querySelectorAll("[data-rail]")).map(a => a.getAttribute("data-rail")))
            .toEqual(["home", "leasing", "collection", "accounting"]);
    });

    it("hides More when none of its pages is enabled", () => {
        role.current = "PROPERTY_MANAGER";
        flags.current = [];
        renderShell();
        expect(screen.queryByTestId("rail-more")).toBeNull();
    });

    it("badges Collection with the actionable count", () => {
        renderShell();
        expect(within(screen.getByTestId("rail-collection")).getByText("7")).toBeInTheDocument();
    });

    it("has no Help link — Help lives in the header", () => {
        const { container } = renderShell();
        expect(container.querySelector('a[href="/dashboard/help"]')).toBeNull();
    });
});

describe("section panel", () => {
    it("shows the active section's pages, the org switcher and the status card", () => {
        path.current = "/en/dashboard/finance/journals";
        renderShell();
        const panel = screen.getByTestId("nav-panel");
        expect(within(panel).getByTestId("org-switcher")).toBeInTheDocument();
        expect(within(panel).getByText(en.AccountingNav.groupJournalEntries)).toBeInTheDocument();
        expect(within(panel).getByTestId("sidebar-journals")).toHaveAttribute("aria-current", "page");
        expect(within(panel).getByTestId("nav-status-card")).toHaveTextContent("2025");
    });

    it("lights Collection, not Accounting, on a cheque page", () => {
        path.current = "/en/dashboard/finance/cheques/collection";
        renderShell();
        expect(screen.getByTestId("rail-collection")).toHaveAttribute("aria-current", "true");
        expect(screen.getByTestId("rail-accounting")).not.toHaveAttribute("aria-current");
    });

    it("keeps collapsed accounting groups in the DOM, hidden, and opens them on click", () => {
        path.current = "/en/dashboard/finance/journals";
        renderShell();
        const setup = screen.getByTestId("panel-group-items-setup");
        expect(setup).not.toBeVisible();
        fireEvent.click(screen.getByTestId("panel-group-toggle-setup"));
        expect(setup).toBeVisible();
    });
});

describe("RTL", () => {
    it("uses logical properties only", () => {
        const { container } = renderShell("ar");
        const physical = /(^|\s)(-?(left|right)-|m[lr]-|p[lr]-|border-[lr](\s|$|-)|rounded-[lr](\s|$|-)|text-left|text-right)/;
        const offenders = Array.from(container.querySelectorAll("[class]"))
            .map(el => el.getAttribute("class") ?? "").filter(c => physical.test(c));
        expect(offenders).toEqual([]);
    });

    it("renders Arabic labels", () => {
        path.current = "/ar/dashboard";
        renderShell("ar");
        expect(screen.getAllByText(ar.Navigation.home).length).toBeGreaterThan(0);
        expect(screen.getByText(ar.Navigation.today)).toBeInTheDocument();
    });
});

describe("phone drawer", () => {
    it("renders the drawer only while open", async () => {
        const { useNavShell } = await import("@/components/nav/NavShellContext");
        function Opener() { const { setDrawerOpen } = useNavShell(); return <button onClick={() => setDrawerOpen(true)}>open</button>; }
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <NavShellProvider><Opener /><MvpSidebar /></NavShellProvider>
            </NextIntlClientProvider>,
        );
        expect(screen.queryByTestId("nav-drawer")).toBeNull();
        fireEvent.click(screen.getByText("open"));
        expect(screen.getByTestId("nav-drawer")).toHaveAttribute("role", "dialog");
    });
});
```

Rewrite `src/components/ui/__tests__/sidebar-role-gating.test.tsx`: keep its mocks and its `describe("sidebar active item (M-9)")` block verbatim (it tests `activeNavHref`, which is kept); keep the renter test but assert against the Home panel (`screen.getByTestId("sidebar-my-penalties")` has `href="/dashboard/renter-portal/penalties"`); delete the finance-gating `describe` — those assertions now live in `src/lib/nav/__tests__/models.test.ts` (Task 4). Add `vi.mock("@/components/nav/useNavCounts", …)` as above and wrap renders in `NavShellProvider`.

Update `sidebar-localization.test.tsx`: wrap in `NavShellProvider`, mock `useNavCounts`; `ar.Navigation.tickets` is inside the Operations panel, so set `path.current = "/ar/dashboard/tickets"` before asserting it; `ar.Navigation.meetings` needs `/ar/dashboard/meetings`; `ar.Navigation.listings` becomes `ar.Navigation.enquiry` with `/ar/dashboard/listings`.

- [ ] **Step 2: Run — fails** (`npx vitest run src/components/nav src/components/ui/__tests__` → `nav-rail` not found).

- [ ] **Step 3: Implement**

```tsx
// src/components/nav/NavShellContext.tsx
"use client";
import { createContext, useContext, useState } from "react";

type NavShell = { drawerOpen: boolean; setDrawerOpen: (open: boolean) => void };
const Ctx = createContext<NavShell>({ drawerOpen: false, setDrawerOpen: () => {} });

export function NavShellProvider({ children }: { children: React.ReactNode }) {
    const [drawerOpen, setDrawerOpen] = useState(false);
    return <Ctx.Provider value={{ drawerOpen, setDrawerOpen }}>{children}</Ctx.Provider>;
}
export const useNavShell = () => useContext(Ctx);
```

```ts
// src/components/nav/useNavCounts.ts
"use client";
import { useEffect, useState } from "react";
import { chequeApi } from "@/lib/api/leasing";
import { ledgerApi } from "@/lib/api/ledger";
import { isBooksLive } from "@/lib/nav/accountingNav";
import { hasPermission, type UserRole } from "@/lib/rbac";

export type NavCounts = { collectionBadge: number | null; chequesToDeposit: number | null; booksLockedThrough: string | null; booksLive: boolean };

/**
 * Rail badge and panel status cards, from endpoints that already exist:
 * GET /cheques/to-deposit (its page's totalElements) and GET /cheques/summary
 * (overdueCount) for Collection; GET /finance/fiscal-settings for the books.
 * Each call is made only for a role its controller admits, and a failure
 * leaves the count null (no badge) rather than a wrong number.
 */
export function useNavCounts(role: UserRole | undefined): NavCounts {
    const [counts, setCounts] = useState<NavCounts>({ collectionBadge: null, chequesToDeposit: null, booksLockedThrough: null, booksLive: true });
    useEffect(() => {
        let alive = true;
        if (hasPermission(role, "canManageCheques")) {
            Promise.all([chequeApi.toDeposit({ page: 0, size: 1 }), chequeApi.summary()])
                .then(([toDeposit, summary]) => {
                    if (!alive) return;
                    setCounts(c => ({ ...c, chequesToDeposit: toDeposit.totalElements, collectionBadge: toDeposit.totalElements + summary.overdueCount }));
                })
                .catch(() => {});
        }
        if (hasPermission(role, "canManageAccountSetup")) {
            ledgerApi.fiscal.get()
                .then(fs => { if (alive) setCounts(c => ({ ...c, booksLockedThrough: fs.booksLockedThrough, booksLive: isBooksLive(fs) })); })
                .catch(() => {});
        }
        return () => { alive = false; };
    }, [role]);
    return counts;
}
```

```tsx
// src/components/nav/SectionPanel.tsx
"use client";
import { ChevronDown } from "lucide-react";
import { useLocale, useTranslations } from "next-intl";
import { useState } from "react";
import { Link } from "@/i18n/routing";
import { cn } from "@/lib/utils";
import type { RailSection } from "@/lib/nav/navModel";
import { useLabel } from "@/lib/nav/useLabel";
import { TenantSwitcher } from "@/components/ui/TenantSwitcher";
import type { NavCounts } from "./useNavCounts";

function fmtDate(iso: string, locale: string) {
    const [y, m, d] = iso.slice(0, 10).split("-").map(Number);
    return new Date(y, m - 1, d).toLocaleDateString(locale === "ar" ? "ar-AE" : "en-GB");
}

export function SectionPanel({ section, activeItem, counts }: { section: RailSection; activeItem: string | null; counts: NavCounts }) {
    const label = useLabel();
    const t = useTranslations("Navigation");
    const locale = useLocale();
    const [open, setOpen] = useState<Record<string, boolean>>(() =>
        Object.fromEntries(section.groups.map(g => [g.id, g.defaultOpen || g.items.some(i => i.id === activeItem)])));

    return (
        <div data-testid="nav-panel" aria-label={t("sectionPanel", { name: label(section.label) })}
            className="flex h-full w-[240px] flex-col border-e border-border bg-surface">
            <div className="border-b border-border p-3"><TenantSwitcher isCollapsed={false} /></div>
            <div className="flex-1 overflow-y-auto thinscroll px-3 py-3 space-y-3">
                <div className="px-2 text-[15px] font-semibold text-foreground">{label(section.label)}</div>
                {section.groups.map(g => {
                    const isOpen = open[g.id] ?? true;
                    return (
                        <div key={g.id}>
                            {g.label && (
                                <button type="button" data-testid={`panel-group-toggle-${g.id}`} aria-expanded={isOpen}
                                    aria-controls={`panel-group-items-${g.id}`}
                                    onClick={() => setOpen(o => ({ ...o, [g.id]: !isOpen }))}
                                    className="flex w-full items-center justify-between px-2 py-1 text-[10.5px] font-semibold uppercase tracking-[0.08em] text-[var(--ink-500)] cursor-pointer">
                                    {label(g.label)}
                                    <ChevronDown size={12} className={cn("transition-transform", isOpen && "rotate-180")} />
                                </button>
                            )}
                            <div id={`panel-group-items-${g.id}`} data-testid={`panel-group-items-${g.id}`} hidden={!isOpen} className="space-y-0.5">
                                {g.items.map(i => (
                                    <Link key={i.id} href={i.href} data-testid={i.testId} data-tour={i.testId}
                                        aria-current={i.id === activeItem ? "page" : undefined}
                                        className={cn("block rounded-[var(--radius-sm)] px-2.5 py-1.5 text-[13.5px]",
                                            i.id === activeItem ? "bg-[var(--sand-100)] font-semibold text-[var(--ink-900)]" : "text-[var(--ink-600)] hover:bg-[var(--sand-100)]")}>
                                        {label(i.label)}
                                    </Link>
                                ))}
                            </div>
                        </div>
                    );
                })}
                {section.savedViews.length > 0 && (
                    <div data-testid="panel-saved-views">
                        <div className="px-2 py-1 text-[10.5px] font-semibold uppercase tracking-[0.08em] text-[var(--ink-500)]">{t("pinned")}</div>
                        {section.savedViews.map(v => (
                            <Link key={v.id} href={v.href} data-testid={v.testId} className="block rounded-[var(--radius-sm)] px-2.5 py-1.5 text-[13px] text-[var(--ink-600)] hover:bg-[var(--sand-100)]">
                                {label(v.label)}
                            </Link>
                        ))}
                    </div>
                )}
            </div>
            {section.statusCard === "booksLocked" && (
                <div data-testid="nav-status-card" className="m-3 rounded-[var(--radius)] border border-border bg-[var(--sand-100)] p-3 text-[12px] text-[var(--ink-700)]">
                    {counts.booksLockedThrough ? t("booksLockedThrough", { date: fmtDate(counts.booksLockedThrough, locale) }) : t("booksNotLocked")}
                </div>
            )}
            {section.statusCard === "chequesToDeposit" && counts.chequesToDeposit !== null && (
                <div data-testid="nav-status-card" className="m-3 rounded-[var(--radius)] border border-border bg-[var(--sand-100)] p-3 text-[12px] text-[var(--ink-700)]">
                    {t("chequesToDeposit", { count: counts.chequesToDeposit })}
                </div>
            )}
        </div>
    );
}
```

```tsx
// src/components/ui/MvpSidebar.tsx — full replacement
"use client";

import { AlertTriangle, BookOpen, Briefcase, FileText, Home, LayoutGrid, PanelLeftClose, PanelLeftOpen, Settings, Wallet, X } from "lucide-react";
import Image from "next/image";
import { usePathname } from "next/navigation";
import { useSession } from "next-auth/react";
import { useTranslations } from "next-intl";
import { useEffect, useState } from "react";
import { Link } from "@/i18n/routing";
import { cn } from "@/lib/utils";
import type { UserRole } from "@/lib/rbac";
import { useTenantFeatures } from "@/hooks/useTenantFeatures";
import { activeNav, buildNav, type RailId, type RailSection } from "@/lib/nav/navModel";
import { useLabel } from "@/lib/nav/useLabel";
import { useNavShell } from "@/components/nav/NavShellContext";
import { useNavCounts } from "@/components/nav/useNavCounts";
import { SectionPanel } from "@/components/nav/SectionPanel";

const APP_VERSION = process.env.NEXT_PUBLIC_APP_VERSION || '0.6.0.dev';

const ICONS: Record<RailId, React.ElementType> = {
    home: Home, leasing: FileText, collection: Wallet, accounting: BookOpen,
    operations: Briefcase, settings: Settings, more: LayoutGrid,
};

/**
 * The nav item to highlight for a pathname: the longest href that equals the
 * path or is a whole-segment prefix of it. (Kept verbatim: M-9.)
 */
export function activeNavHref(pathname: string, hrefs: string[]): string | null {
    const path = pathname.replace(/^\/(en|ar)(?=\/|$)/, "") || "/";
    let best: string | null = null;
    for (const href of hrefs) {
        if ((path === href || path.startsWith(`${href}/`)) && (best === null || href.length > best.length)) {
            best = href;
        }
    }
    return best;
}

function Rail({ rail, active, onPick, badge }: { rail: RailSection[]; active: RailId | null; onPick: (id: RailId) => void; badge: number | null }) {
    const label = useLabel();
    const t = useTranslations("Navigation");
    return (
        <nav data-testid="nav-rail" data-tour="sidebar-nav" className="flex h-full w-16 flex-col items-center gap-1 border-e border-border bg-surface py-3">
            <Link href="/" className="mb-3 rounded-lg focus:outline-none focus:ring-2 focus:ring-[var(--gold-500)]/30">
                <Image src="/logo.png" alt="RentAxis" width={32} height={32} className="h-8 w-8 object-contain" priority />
            </Link>
            {rail.map(s => {
                const Icon = ICONS[s.id] ?? AlertTriangle;
                const isActive = s.id === active;
                return (
                    <Link key={s.id} href={s.href} data-rail={s.id} data-testid={`rail-${s.id}`} data-tour={s.tourId}
                        aria-current={isActive ? "true" : undefined} title={label(s.label)}
                        onClick={() => onPick(s.id)}
                        className={cn("relative flex w-14 flex-col items-center gap-0.5 rounded-[var(--radius-sm)] py-2 text-[10px] font-medium",
                            isActive ? "bg-[var(--sand-100)] text-[var(--ink-900)]" : "text-[var(--ink-600)] hover:bg-[var(--sand-100)]")}>
                        <Icon size={18} className={isActive ? "text-[var(--gold-500)]" : "text-[var(--ink-500)]"} />
                        <span className="max-w-full truncate">{label(s.label)}</span>
                        {s.badge === "collection" && badge !== null && badge > 0 && (
                            <span aria-label={t("badgeLabel", { count: badge })}
                                className="absolute top-1 end-1.5 min-w-4 rounded-full bg-error px-1 text-center text-[9px] font-bold leading-4 text-white">
                                {badge > 99 ? "99+" : badge}
                            </span>
                        )}
                    </Link>
                );
            })}
            <div className="mt-auto px-1 text-center font-mono text-[9px] text-[var(--ink-500)]">v{APP_VERSION}</div>
        </nav>
    );
}

export default function MvpSidebar() {
    const t = useTranslations("Navigation");
    const pathname = usePathname();
    const { data: session } = useSession();
    const role = session?.user?.role as UserRole | undefined;
    const { isEnabled, tenantSlug } = useTenantFeatures();
    const counts = useNavCounts(role);
    const { drawerOpen, setDrawerOpen } = useNavShell();
    const rail = buildNav({ role, isEnabled, tenantSlug, booksLive: counts.booksLive });
    const active = activeNav(pathname, rail);
    const [picked, setPicked] = useState<RailId | null>(null);
    const [flyout, setFlyout] = useState(false);
    const [panelHidden, setPanelHidden] = useState(() => {
        try { return typeof window !== "undefined" && localStorage.getItem("sidebar_collapsed") === "true"; } catch { return false; }
    });

    useEffect(() => { try { localStorage.setItem("sidebar_collapsed", String(panelHidden)); } catch { /* private mode */ } }, [panelHidden]);
    useEffect(() => { setPicked(null); setFlyout(false); setDrawerOpen(false); }, [pathname, setDrawerOpen]);
    useEffect(() => {
        const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") { setFlyout(false); setDrawerOpen(false); } };
        window.addEventListener("keydown", onKey);
        return () => window.removeEventListener("keydown", onKey);
    }, [setDrawerOpen]);

    const shownId = picked ?? active.section ?? rail[0]?.id ?? null;
    const shown = rail.find(s => s.id === shownId) ?? null;
    const pick = (id: RailId) => { setPicked(id); setFlyout(true); };
    const panel = shown && <SectionPanel key={shown.id} section={shown} activeItem={shown.id === active.section ? active.item : null} counts={counts} />;

    return (
        <>
            {/* ≥ 768 px: rail always; ≥ 1280 px: panel beside it unless hidden. */}
            <aside className="relative sticky top-0 z-40 hidden h-screen shrink-0 md:flex">
                <Rail rail={rail} active={active.section} onPick={pick} badge={counts.collectionBadge} />
                <div className={cn("hidden h-full", !panelHidden && "xl:flex")}>{panel}</div>
                {flyout && (
                    <div data-testid="nav-flyout" className="absolute top-0 start-16 z-50 h-full shadow-lg xl:hidden">{panel}</div>
                )}
                <button type="button" onClick={() => setPanelHidden(h => !h)} aria-label={panelHidden ? t("showPanel") : t("hidePanel")}
                    className="absolute top-12 -end-3 z-50 hidden rounded-full border border-border bg-surface p-1.5 shadow-sm hover:bg-[var(--sand-100)] xl:block cursor-pointer">
                    {panelHidden ? <PanelLeftOpen size={10} className="rtl:-scale-x-100" /> : <PanelLeftClose size={10} className="rtl:-scale-x-100" />}
                </button>
            </aside>

            {/* < 768 px: drawer. */}
            {drawerOpen && (
                <div className="fixed inset-0 z-50 md:hidden">
                    <button type="button" aria-label={t("closeMenu")} onClick={() => setDrawerOpen(false)} className="absolute inset-0 bg-black/30" />
                    <div data-testid="nav-drawer" role="dialog" aria-modal="true" aria-label={t("openMenu")} className="absolute inset-y-0 start-0 flex max-w-full bg-surface shadow-xl">
                        <Rail rail={rail} active={active.section} onPick={id => setPicked(id)} badge={counts.collectionBadge} />
                        {panel}
                        <button type="button" onClick={() => setDrawerOpen(false)} aria-label={t("closeMenu")} className="absolute top-2 end-2 p-1 cursor-pointer"><X size={16} /></button>
                    </div>
                </div>
            )}
        </>
    );
}
```

`src/components/layout/AuthenticatedLayout.tsx`: import `NavShellProvider` and wrap the `<div className="flex h-screen …">` (inside `TourProvider`) with `<NavShellProvider>…</NavShellProvider>`.

`src/components/ui/TopHeader.tsx`:
1. Imports: add `HelpCircle, Menu as MenuIcon` to the lucide import; `import { useNavShell } from "@/components/nav/NavShellContext";`, `import { activeNav, buildNav } from "@/lib/nav/navModel";`, `import { useLabel } from "@/lib/nav/useLabel";`, `import { useTenantFeatures } from "@/hooks/useTenantFeatures";`.
2. In the component: `const { setDrawerOpen } = useNavShell(); const label = useLabel(); const { isEnabled, tenantSlug } = useTenantFeatures(); const rail = buildNav({ role: userRole, isEnabled, tenantSlug, booksLive: true }); const here = activeNav(pathname, rail); const hereSection = rail.find(s => s.id === here.section); const hereItem = hereSection?.groups.flatMap(g => g.items).find(i => i.id === here.item);`
3. As the header's first child, before `<GlobalSearch …/>`:

```tsx
<button type="button" onClick={() => setDrawerOpen(true)} aria-label={tNav("openMenu")} data-testid="header-menu"
    className="md:hidden w-9 h-9 flex items-center justify-center border border-border rounded-[var(--radius)] bg-surface cursor-pointer">
    <MenuIcon size={18} />
</button>
{hereSection && (
    <nav aria-label={tNav("breadcrumb")} data-testid="header-breadcrumb" className="hidden lg:flex items-center gap-1.5 text-[13px] text-[var(--ink-500)] whitespace-nowrap">
        <span>{label(hereSection.label)}</span>
        {hereItem && <><span aria-hidden className="rtl:-scale-x-100">›</span><span className="font-semibold text-foreground">{label(hereItem.label)}</span></>}
    </nav>
)}
```
4. Before the notification bell: 

```tsx
<Link href="/dashboard/help" aria-label={tNav("helpAndGuides")} data-tour="header-help" data-testid="header-help"
    className="w-9 h-9 flex items-center justify-center border border-border rounded-[var(--radius)] bg-surface text-[var(--ink-600)] hover:text-foreground hover:bg-[var(--sand-100)] transition-colors">
    <HelpCircle size={18} />
</Link>
```
5. The header's `px-7` becomes `px-4 md:px-7` so the 390 px sweep has room.

Add to `src/components/ui/__tests__/topheader-profile-menu.test.tsx` (it already renders `TopHeader`; add `vi.mock("@/hooks/useTenantFeatures", …)` like the shell test and wrap in `NavShellProvider`):

```tsx
it("links Help from the header", () => {
    render(<NavShellProvider><TopHeader /></NavShellProvider>);
    expect(screen.getByTestId("header-help")).toHaveAttribute("href", "/dashboard/help");
});
```
(The file's two existing `render(<TopHeader />)` calls also get the `NavShellProvider` wrapper.)

`src/app/[locale]/dashboard/page.tsx`: give the occupancy `StatCard`'s wrapper `id="unit-status"` (wrap it: `<div id="unit-status"><StatCard label={t("occupancy")} … /></div>`) so Home › Unit Status lands on it. PR 3 (Task 26) turns this card into the Unit Status card.

- [ ] **Step 4: Run** — `npx vitest run src/components/nav src/components/ui src/components/layout` → all pass. Then `npx tsc --noEmit` → no output.

- [ ] **Step 5: Commit**

```bash
cd /Users/kunalsharma/datagami/rentaxis/web && git add src/components/nav src/components/ui/MvpSidebar.tsx src/components/ui/TopHeader.tsx src/components/layout/AuthenticatedLayout.tsx src/components/ui/__tests__ "src/app/[locale]/dashboard/page.tsx" && git commit -m "feat(web): icon rail + section panel shell, Help and breadcrumb in the header

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- src/components/nav src/components/ui/MvpSidebar.tsx src/components/ui/TopHeader.tsx src/components/layout/AuthenticatedLayout.tsx src/components/ui/__tests__ "src/app/[locale]/dashboard/page.tsx"
```

### Task 9: Move the accounting-setup pages under `/dashboard/finance`

**Files:** `git mv` `src/app/[locale]/dashboard/settings/{account-template,fiscal,charge-types}` → `src/app/[locale]/dashboard/finance/{account-template,fiscal,charge-types}` (their `__tests__` move with them; imports are `@/…` aliases and `../page`, so nothing inside changes).

**Interfaces** — Consumes: `ROUTE_MOVES` rows added in Task 1. Produces: pages at `/dashboard/finance/account-template`, `/dashboard/finance/fiscal`, `/dashboard/finance/charge-types`.

- [ ] **Step 1: Move**

```bash
cd /Users/kunalsharma/datagami/rentaxis/web && for p in account-template fiscal charge-types; do git mv "src/app/[locale]/dashboard/settings/$p" "src/app/[locale]/dashboard/finance/$p"; done && git status --short | head
```
Expected: three `R` rename groups.

- [ ] **Step 2: No stale in-app links** — `grep -rn "settings/account-template\|settings/fiscal\|settings/charge-types" src --include='*.ts' --include='*.tsx' | grep -v __tests__ | grep -v routeMap.ts` → no output (the old sidebar was the only linker; `legacyNav.fixture.ts` is a test file on purpose).

- [ ] **Step 3: Run** — `npx vitest run "src/app/[locale]/dashboard/finance/charge-types" src/lib/nav` → pass; `npx tsc --noEmit` → no output.

- [ ] **Step 4: Commit** — `git commit -m "feat(web): accounting setup pages live under Accounting" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- "src/app/[locale]/dashboard/settings" "src/app/[locale]/dashboard/finance/account-template" "src/app/[locale]/dashboard/finance/fiscal" "src/app/[locale]/dashboard/finance/charge-types"`.

### Task 10: Settings building blocks — embeddable settings/users/staff components

Page bodies move into components with an `embedded` prop (heading becomes `h2`, cross-links hidden — no logic touched). Each old `page.tsx` becomes a wrapper so its URL (until redirected) and its existing tests keep working. A page file cannot export a component with custom props (Next 16's generated `PageProps` check fails the build), hence wrappers rather than re-exports.

**Files:**
- `git mv src/app/[locale]/dashboard/settings/fines/page.tsx src/components/settings/FinesSettings.tsx`, same for `rent-settings` → `RentSettings.tsx`, `gateway` → `GatewaySettings.tsx`, `src/app/[locale]/dashboard/staff/page.tsx` → `src/components/staff/StaffManager.tsx`, `src/app/[locale]/superadmin/users/page.tsx` → `src/components/users/UsersManager.tsx`.
- Create wrappers at the five old `page.tsx` paths.
- Create `src/lib/rentSettings.ts`, `src/lib/__tests__/rentSettings.test.ts`, `src/components/settings/__tests__/RentSettings.hide-toggle.test.tsx`.

**Interfaces** — Produces:
- `FinesSettings({ embedded }: { embedded?: boolean })`, `GatewaySettings({ embedded })`, `StaffManager({ embedded })`, `UsersManager({ embedded })`
- `RentSettings({ embedded, hideOnlinePaymentToggle }: { embedded?: boolean; hideOnlinePaymentToggle?: boolean })`
- `src/lib/rentSettings.ts`: `type RentSettingsData`, `const DEFAULT_RENT_SETTINGS: Omit<RentSettingsData, "propertyId">`, `function toRentSettingsBody(s: RentSettingsData): Omit<RentSettingsData, "id" | "propertyId">`, `function readRentSettings(res: Response, propertyId: string): Promise<RentSettingsData | null>`

- [ ] **Step 1: Failing tests**

```ts
// src/lib/__tests__/rentSettings.test.ts
import { describe, expect, it } from "vitest";
import { DEFAULT_RENT_SETTINGS, readRentSettings, toRentSettingsBody } from "../rentSettings";

describe("rent settings helpers", () => {
    it("treats 204 as defaults", async () => {
        const s = await readRentSettings({ status: 204, ok: true } as Response, "p1");
        expect(s).toEqual({ ...DEFAULT_RENT_SETTINGS, propertyId: "p1" });
    });
    it("merges a 200 body over the defaults", async () => {
        const s = await readRentSettings({ status: 200, ok: true, json: async () => ({ dueDayOfMonth: 5, onlinePaymentEnabled: true }) } as unknown as Response, "p1");
        expect(s?.dueDayOfMonth).toBe(5);
        expect(s?.gracePeriodDays).toBe(DEFAULT_RENT_SETTINGS.gracePeriodDays);
    });
    it("answers null on an error status", async () => {
        expect(await readRentSettings({ status: 500, ok: false } as Response, "p1")).toBeNull();
    });
    it("sends every field the save endpoint takes, onlinePaymentEnabled included", () => {
        const body = toRentSettingsBody({ ...DEFAULT_RENT_SETTINGS, propertyId: "p1", id: "x", onlinePaymentEnabled: true });
        expect(Object.keys(body).sort()).toEqual([
            "dueDayOfMonth", "fineAccountClosedAmount", "fineBounceAmount", "fineGraceDays", "fineSignatureMismatchAmount",
            "finePerDayRate", "gracePeriodDays", "onlinePaymentEnabled", "penaltyAmount", "penaltyType", "renewalIncreaseWarnPercent",
        ]);
        expect(body.onlinePaymentEnabled).toBe(true);
    });
});
```

```tsx
// src/components/settings/__tests__/RentSettings.hide-toggle.test.tsx
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
import RentSettings from "../RentSettings";

const posted: unknown[] = [];
beforeEach(() => {
    posted.length = 0;
    global.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (url.includes("/v1/properties")) return { ok: true, status: 200, json: async () => [{ property: { id: "p1", nameEn: "Belle Vue" } }] } as unknown as Response;
        if (url.includes("/v1/settings/fines")) return { ok: false, status: 404, json: async () => ({}) } as unknown as Response;
        if (url.includes("/v1/rent-settings/") && init?.method === "POST") {
            posted.push(JSON.parse(String(init.body)));
            return { ok: true, status: 200, json: async () => JSON.parse(String(init.body)) } as unknown as Response;
        }
        if (url.includes("/v1/rent-settings/")) return { ok: true, status: 200, json: async () => ({ propertyId: "p1", onlinePaymentEnabled: true, dueDayOfMonth: 3 }) } as unknown as Response;
        return { ok: true, status: 200, json: async () => [] } as unknown as Response;
    }) as unknown as typeof fetch;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("RentSettings inside Settings › Rent & fines", () => {
    it("hides the online-payment switch but saves the value it loaded, so Payments' choice is never clobbered", async () => {
        render(<RentSettings embedded hideOnlinePaymentToggle />);
        await screen.findByText("Belle Vue");
        fireEvent.change(screen.getAllByRole("combobox")[0], { target: { value: "p1" } });
        await screen.findByText("Settings");
        expect(screen.queryByRole("switch")).toBeNull();
        fireEvent.click(screen.getByRole("button", { name: /save/i }));
        await waitFor(() => expect(posted).toHaveLength(1));
        expect(posted[0]).toMatchObject({ onlinePaymentEnabled: true, dueDayOfMonth: 3 });
    });

    it("renders its title as h2 when embedded", async () => {
        render(<RentSettings embedded />);
        expect(await screen.findByRole("heading", { level: 2, name: /rent settings/i })).toBeInTheDocument();
    });
});
```

- [ ] **Step 2: Run — fails** (`npx vitest run src/lib/__tests__/rentSettings.test.ts src/components/settings` → unresolved).

- [ ] **Step 3: Implement `src/lib/rentSettings.ts`** — cut `RentSettings` type (renamed `RentSettingsData`), `DEFAULT_SETTINGS` (renamed `DEFAULT_RENT_SETTINGS`) out of the rent-settings page verbatim, and add:

```ts
export function toRentSettingsBody(s: RentSettingsData): Omit<RentSettingsData, "id" | "propertyId"> {
    return {
        dueDayOfMonth: s.dueDayOfMonth, gracePeriodDays: s.gracePeriodDays, penaltyType: s.penaltyType,
        penaltyAmount: s.penaltyAmount, onlinePaymentEnabled: s.onlinePaymentEnabled,
        fineBounceAmount: s.fineBounceAmount, fineSignatureMismatchAmount: s.fineSignatureMismatchAmount,
        fineAccountClosedAmount: s.fineAccountClosedAmount, fineGraceDays: s.fineGraceDays,
        finePerDayRate: s.finePerDayRate, renewalIncreaseWarnPercent: s.renewalIncreaseWarnPercent,
    };
}

/** GET /v1/rent-settings/{id}: 204 = no row yet (defaults; a 204 body must not be parsed), 2xx = merge, else null. */
export async function readRentSettings(res: Response, propertyId: string): Promise<RentSettingsData | null> {
    if (res.status === 204) return { ...DEFAULT_RENT_SETTINGS, propertyId };
    if (!res.ok) return null;
    return { ...DEFAULT_RENT_SETTINGS, ...(await res.json()) };
}
```

- [ ] **Step 4: Move the five bodies and add the prop**

```bash
cd /Users/kunalsharma/datagami/rentaxis/web && mkdir -p src/components/settings src/components/staff && \
git mv "src/app/[locale]/dashboard/settings/fines/page.tsx" src/components/settings/FinesSettings.tsx && \
git mv "src/app/[locale]/dashboard/settings/rent-settings/page.tsx" src/components/settings/RentSettings.tsx && \
git mv "src/app/[locale]/dashboard/settings/gateway/page.tsx" src/components/settings/GatewaySettings.tsx && \
git mv "src/app/[locale]/dashboard/staff/page.tsx" src/components/staff/StaffManager.tsx && \
git mv "src/app/[locale]/superadmin/users/page.tsx" src/components/users/UsersManager.tsx
```

In each moved file:
- signature: `export default function FinesSettingsPage()` → `export default function FinesSettings({ embedded = false }: { embedded?: boolean } = {})` (likewise `GatewaySettings`, `StaffManager`, `UsersManager`; `RentSettings({ embedded = false, hideOnlinePaymentToggle = false }: { embedded?: boolean; hideOnlinePaymentToggle?: boolean } = {})`).
- heading: add `const Heading = embedded ? "h2" : "h1";` at the top of the body and change the page title element (`FinesSettings.tsx:164`, `RentSettings.tsx:280`, `GatewaySettings.tsx:212`, `StaffManager.tsx:272`, `UsersManager.tsx:255` — the only `<h1` in each) from `<h1 …>…</h1>` to `<Heading …>…</Heading>`, classes unchanged.
- `UsersManager.tsx`: the title is the literal `Super Admin: Manage Users` — replace with `{tNav("users")}` (`const tNav = useTranslations("Navigation");`), EN "Users" / AR "المستخدمون" already exist, closing an existing i18n gap.
- `RentSettings.tsx`: replace the local type/defaults with `import { DEFAULT_RENT_SETTINGS, readRentSettings, toRentSettingsBody, type RentSettingsData } from "@/lib/rentSettings";`; `fetchSettings` becomes `const s = await readRentSettings(res, propertyId); if (s) setSettings(s); else { setSettings(null); setError(t("loadSettingsFailed")); }`; `handleSave`'s body becomes `JSON.stringify(toRentSettingsBody(settings))`; wrap the `{/* Online Payment Toggle */}` block in `{!hideOnlinePaymentToggle && ( … )}`.

- [ ] **Step 5: Wrappers at the old paths** (one per file; example for fines):

```tsx
// src/app/[locale]/dashboard/settings/fines/page.tsx
import FinesSettings from "@/components/settings/FinesSettings";

/** Moved to Settings › Rent & fines; this route 308s there (routeMap.ts). Kept for its tests. */
export default function FinesSettingsPage() {
    return <FinesSettings />;
}
```
Same pattern: `settings/rent-settings/page.tsx` → `<RentSettings />`; `settings/gateway/page.tsx` → `<GatewaySettings />`; `dashboard/staff/page.tsx` → `<StaffManager />` (this URL stays live); `superadmin/users/page.tsx` → `<UsersManager />` (stays live for SUPER_ADMIN and still admits TENANT_ADMIN by URL — `rbac.ts` unchanged).

- [ ] **Step 6: Run** — `npx vitest run src/lib/__tests__/rentSettings.test.ts src/components/settings "src/app/[locale]/dashboard/settings" "src/app/[locale]/dashboard/staff"` → all pass (the existing `settings/*/__tests__/page.test.tsx` import `../page`, now the wrapper). `npx tsc --noEmit` → no output.

- [ ] **Step 7: Commit** — `git add src/lib/rentSettings.ts src/lib/__tests__/rentSettings.test.ts src/components/settings src/components/staff src/components/users "src/app/[locale]/dashboard/settings" "src/app/[locale]/dashboard/staff" "src/app/[locale]/superadmin/users" && git commit -m "refactor(web): settings, staff and users bodies become embeddable components" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- src/lib/rentSettings.ts src/lib/__tests__/rentSettings.test.ts src/components/settings src/components/staff src/components/users "src/app/[locale]/dashboard/settings" "src/app/[locale]/dashboard/staff" "src/app/[locale]/superadmin/users"`.

### Task 11: One Settings page — Organisation · Users & staff · Rent & fines · Payments

Users & staff uses the existing `GET /api/proxy/admin/users` (UserController admits TENANT_ADMIN and scopes to the caller's organisation) — no backend change. Notifications is not a section: no notification-preference setting exists to show.

**Files:** Create `src/app/[locale]/dashboard/settings/page.tsx`, `src/components/settings/OnlinePaymentSwitch.tsx`, `src/components/settings/OrganisationSection.tsx`, `src/app/[locale]/dashboard/settings/__tests__/settings-page.test.tsx`, `src/components/settings/__tests__/OnlinePaymentSwitch.test.tsx`. Modify `messages/{en,ar}.json`, `src/app/[locale]/dashboard/properties/[id]/page.tsx` (one link).

**Interfaces**
- Consumes: `buildSettingsSections`, `SettingsSectionId` (Task 4); `FinesSettings`, `RentSettings`, `GatewaySettings`, `StaffManager`, `UsersManager` (Task 10); `readRentSettings`, `toRentSettingsBody` (Task 10); `hasPermission`, `canConfigureFines`, `canConfigureRentSettings`.
- Produces: `SettingsPage()`; `OnlinePaymentSwitch()`; `OrganisationSection()`.

- [ ] **Step 1: Messages** — `SettingsPage` gains (EN / AR): `orgName` "Organisation name" / "اسم المؤسسة"; `orgSlug` "Portal address" / "عنوان البوابة"; `orgLoadFailed` "Could not load organisation details." / "تعذر تحميل بيانات المؤسسة."; `noAccessTitle` "No settings for your role" / "لا توجد إعدادات لدورك"; `noAccessBody` "Ask a company admin to change organisation settings." / "اطلب من مدير المؤسسة تغيير إعدادات المؤسسة."; `onlinePaymentsHeading` "Online payments by property" / "الدفع الإلكتروني حسب العقار"; `onlinePaymentsHint` "Turn online rent payment on or off for each property." / "فعّل أو أوقف دفع الإيجار إلكترونياً لكل عقار."; `selectProperty` "Choose a property" / "اختر عقاراً"; `onlineSaved` "Saved." / "تم الحفظ."; `onlineSaveFailed` "Could not save the online-payment setting." / "تعذر حفظ إعداد الدفع الإلكتروني.".

- [ ] **Step 2: Failing tests**

```tsx
// src/app/[locale]/dashboard/settings/__tests__/settings-page.test.tsx
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
const role = { current: "TENANT_ADMIN" };
const query = { current: "" };
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: role.current } } }) }));
vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams(query.current) }));
vi.mock("@/i18n/routing", () => ({ Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a> }));
vi.mock("@/components/settings/FinesSettings", () => ({ default: (p: { embedded?: boolean }) => <div data-testid="probe-fines" data-embedded={String(p.embedded)} /> }));
vi.mock("@/components/settings/RentSettings", () => ({ default: (p: { hideOnlinePaymentToggle?: boolean }) => <div data-testid="probe-rent" data-hide={String(p.hideOnlinePaymentToggle)} /> }));
vi.mock("@/components/settings/GatewaySettings", () => ({ default: () => <div data-testid="probe-gateway" /> }));
vi.mock("@/components/settings/OnlinePaymentSwitch", () => ({ default: () => <div data-testid="probe-online-switch" /> }));
vi.mock("@/components/settings/OrganisationSection", () => ({ default: () => <div data-testid="probe-organisation" /> }));
vi.mock("@/components/staff/StaffManager", () => ({ default: () => <div data-testid="probe-staff" /> }));
vi.mock("@/components/users/UsersManager", () => ({ default: () => <div data-testid="probe-users" /> }));
import SettingsPage from "../page";

beforeEach(() => { role.current = "TENANT_ADMIN"; query.current = ""; });
afterEach(cleanup);

describe("Settings page", () => {
    it.each([
        ["", ["probe-organisation"]],
        ["section=organisation", ["probe-organisation"]],
        ["section=users", ["probe-users", "probe-staff"]],
        ["section=rent", ["probe-fines", "probe-rent"]],
        ["section=payments", ["probe-gateway", "probe-online-switch"]],
        ["section=nonsense", ["probe-organisation"]],
    ])("?%s renders %j", (q, probes) => {
        query.current = q;
        render(<SettingsPage />);
        for (const p of probes) expect(screen.getByTestId(p)).toBeInTheDocument();
    });

    it("hides the online-payment switch inside Rent & fines (it lives in Payments)", () => {
        query.current = "section=rent";
        render(<SettingsPage />);
        expect(screen.getByTestId("probe-rent")).toHaveAttribute("data-hide", "true");
    });

    it("lists the four sections as links", () => {
        render(<SettingsPage />);
        expect(screen.getByTestId("settings-nav-rent")).toHaveAttribute("href", "/dashboard/settings?section=rent");
        expect(screen.getByTestId("settings-nav-organisation")).toHaveAttribute("aria-current", "page");
    });

    it("shows a no-access panel to a role with no section", () => {
        role.current = "ACCOUNTANT";
        render(<SettingsPage />);
        expect(screen.getByTestId("settings-no-access")).toBeInTheDocument();
        expect(screen.queryByTestId("probe-organisation")).toBeNull();
    });
});
```

```tsx
// src/components/settings/__tests__/OnlinePaymentSwitch.test.tsx
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
import OnlinePaymentSwitch from "../OnlinePaymentSwitch";

const posted: Record<string, unknown>[] = [];
beforeEach(() => {
    posted.length = 0;
    global.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (url.endsWith("/v1/properties")) return { ok: true, status: 200, json: async () => [{ property: { id: "p1", nameEn: "Belle Vue" } }] } as unknown as Response;
        if (init?.method === "POST") { posted.push(JSON.parse(String(init.body))); return { ok: true, status: 200, json: async () => JSON.parse(String(init.body)) } as unknown as Response; }
        return { ok: true, status: 200, json: async () => ({ propertyId: "p1", onlinePaymentEnabled: false, dueDayOfMonth: 9, penaltyType: "PERCENTAGE", penaltyAmount: 2 }) } as unknown as Response;
    }) as unknown as typeof fetch;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("OnlinePaymentSwitch", () => {
    it("flips only onlinePaymentEnabled and sends every other rent setting back unchanged", async () => {
        render(<OnlinePaymentSwitch />);
        await screen.findByText("Belle Vue");
        fireEvent.change(screen.getByRole("combobox"), { target: { value: "p1" } });
        fireEvent.click(await screen.findByRole("switch"));
        await waitFor(() => expect(posted).toHaveLength(1));
        expect(posted[0]).toMatchObject({ onlinePaymentEnabled: true, dueDayOfMonth: 9, penaltyType: "PERCENTAGE", penaltyAmount: 2 });
        expect(fetch).toHaveBeenCalledWith("/api/proxy/v1/rent-settings/p1", expect.objectContaining({ method: "POST" }));
    });
});
```

- [ ] **Step 3: Run — fails** (unresolved `../page`, `../OnlinePaymentSwitch`).

- [ ] **Step 4: Implement**

```tsx
// src/components/settings/OnlinePaymentSwitch.tsx
"use client";
import { useEffect, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { cn } from "@/lib/utils";
import { readRentSettings, toRentSettingsBody, type RentSettingsData } from "@/lib/rentSettings";

type Property = { id: string; nameEn: string; nameAr?: string };

/**
 * Settings › Payments: the per-property online-payment switch that used to sit
 * inside Rent settings. The save endpoint takes the whole row, so the switch
 * re-reads it and sends every other field back exactly as loaded.
 */
export default function OnlinePaymentSwitch() {
    const t = useTranslations("SettingsPage");
    const tOp = useTranslations("OnlinePayments");
    const locale = useLocale();
    const [properties, setProperties] = useState<Property[]>([]);
    const [propertyId, setPropertyId] = useState("");
    const [settings, setSettings] = useState<RentSettingsData | null>(null);
    const [status, setStatus] = useState<"idle" | "saving" | "saved" | "error">("idle");

    useEffect(() => {
        fetch("/api/proxy/v1/properties").then(r => (r.ok ? r.json() : [])).then((rows: { property: Property }[]) => setProperties(rows.map(r => r.property))).catch(() => setProperties([]));
    }, []);
    useEffect(() => {
        setSettings(null);
        if (!propertyId) return;
        fetch(`/api/proxy/v1/rent-settings/${propertyId}`).then(res => readRentSettings(res, propertyId)).then(setSettings).catch(() => setSettings(null));
    }, [propertyId]);

    const toggle = async () => {
        if (!settings) return;
        const next = { ...settings, onlinePaymentEnabled: !settings.onlinePaymentEnabled };
        setStatus("saving");
        try {
            const res = await fetch(`/api/proxy/v1/rent-settings/${propertyId}`, {
                method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(toRentSettingsBody(next)),
            });
            if (!res.ok) throw new Error(String(res.status));
            setSettings(next);
            setStatus("saved");
        } catch {
            setStatus("error");
        }
    };

    return (
        <section className="bg-surface rounded-xl border border-border p-5 space-y-4" data-testid="online-payment-switch">
            <div>
                <h3 className="text-sm font-bold text-foreground">{t("onlinePaymentsHeading")}</h3>
                <p className="text-[11px] text-muted mt-0.5">{t("onlinePaymentsHint")}</p>
            </div>
            <select value={propertyId} onChange={e => setPropertyId(e.target.value)} aria-label={t("selectProperty")}
                className="w-full border border-border rounded-lg bg-surface p-3 text-sm">
                <option value="">{t("selectProperty")}</option>
                {properties.map(p => <option key={p.id} value={p.id}>{locale === "ar" && p.nameAr ? p.nameAr : p.nameEn}</option>)}
            </select>
            {settings && (
                <div className="flex items-center justify-between bg-input rounded-xl p-4 border border-border">
                    <p className="text-sm font-bold text-foreground">{tOp("onlinePaymentEnabled")}</p>
                    <button type="button" role="switch" aria-checked={settings.onlinePaymentEnabled} aria-label={tOp("toggleOnlinePayment")}
                        disabled={status === "saving"} onClick={toggle}
                        className={cn("relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors",
                            settings.onlinePaymentEnabled ? "bg-primary" : "bg-muted/40")}>
                        <span className={cn("pointer-events-none inline-block h-5 w-5 rounded-full bg-white shadow transition-transform",
                            settings.onlinePaymentEnabled ? "translate-x-5 rtl:-translate-x-5" : "translate-x-0")} />
                    </button>
                </div>
            )}
            {status === "saved" && <p className="text-xs text-success" role="status">{t("onlineSaved")}</p>}
            {status === "error" && <p className="text-xs text-error" role="alert">{t("onlineSaveFailed")}</p>}
        </section>
    );
}
```

```tsx
// src/components/settings/OrganisationSection.tsx
"use client";
import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";

/** Read-only: GET /v1/tenant/info (name, slug) — the only organisation fields an endpoint returns today. */
export default function OrganisationSection() {
    const t = useTranslations("SettingsPage");
    const [info, setInfo] = useState<{ name: string; slug: string } | null>(null);
    const [failed, setFailed] = useState(false);
    useEffect(() => {
        fetch("/api/proxy/v1/tenant/info")
            .then(r => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
            .then(setInfo)
            .catch(() => setFailed(true));
    }, []);
    if (failed) return <p className="text-xs text-error" role="alert">{t("orgLoadFailed")}</p>;
    return (
        <dl className="bg-surface rounded-xl border border-border p-5 grid grid-cols-1 sm:grid-cols-2 gap-4" data-testid="organisation-section">
            <div><dt className="text-[11px] text-muted">{t("orgName")}</dt><dd className="text-sm font-semibold">{info?.name ?? "—"}</dd></div>
            <div><dt className="text-[11px] text-muted">{t("orgSlug")}</dt><dd className="text-sm font-mono"><bdi dir="ltr">{info?.slug ?? "—"}</bdi></dd></div>
        </dl>
    );
}
```

```tsx
// src/app/[locale]/dashboard/settings/page.tsx
"use client";
import { useSearchParams } from "next/navigation";
import { useSession } from "next-auth/react";
import { useTranslations } from "next-intl";
import { Link } from "@/i18n/routing";
import { cn } from "@/lib/utils";
import { canConfigureFines, canConfigureRentSettings, hasPermission, type UserRole } from "@/lib/rbac";
import { buildSettingsSections } from "@/lib/nav/settingsModel";
import { useLabel } from "@/lib/nav/useLabel";
import FinesSettings from "@/components/settings/FinesSettings";
import RentSettings from "@/components/settings/RentSettings";
import GatewaySettings from "@/components/settings/GatewaySettings";
import OnlinePaymentSwitch from "@/components/settings/OnlinePaymentSwitch";
import OrganisationSection from "@/components/settings/OrganisationSection";
import StaffManager from "@/components/staff/StaffManager";
import UsersManager from "@/components/users/UsersManager";

export default function SettingsPage() {
    const t = useTranslations("SettingsPage");
    const label = useLabel();
    const { data: session } = useSession();
    const role = session?.user?.role as UserRole | undefined;
    const sections = buildSettingsSections(role);
    const requested = useSearchParams()?.get("section");
    const active = sections.find(s => s.id === requested) ?? sections[0];

    if (!active) {
        return (
            <div className="max-w-3xl bg-surface rounded-xl border border-border p-10 text-center" data-testid="settings-no-access">
                <h1 className="text-lg font-bold mb-2">{t("noAccessTitle")}</h1>
                <p className="text-sm text-muted">{t("noAccessBody")}</p>
            </div>
        );
    }

    return (
        <div className="max-w-5xl">
            <h1 className="text-xl font-bold text-foreground tracking-tight mb-4">{t("title")}</h1>
            <nav aria-label={t("sectionsLabel")} data-testid="settings-sections" className="flex gap-1 border-b border-border overflow-x-auto mb-6">
                {sections.map(s => (
                    <Link key={s.id} href={s.href} data-testid={s.testId} aria-current={s.id === active.id ? "page" : undefined}
                        className={cn("px-3.5 py-2.5 text-[13.5px] -mb-px whitespace-nowrap",
                            s.id === active.id ? "font-semibold text-foreground border-b-2 border-[var(--gold-500)]" : "text-[var(--ink-500)] hover:text-foreground")}>
                        {label(s.label)}
                    </Link>
                ))}
            </nav>
            <section id={active.id} data-testid={`settings-section-${active.id}`} className="space-y-8">
                {active.id === "organisation" && <OrganisationSection />}
                {active.id === "users" && (
                    <>
                        {hasPermission(role, "canManageUsers") && <UsersManager embedded />}
                        {hasPermission(role, "canAccessFinanceOps") && <StaffManager embedded />}
                    </>
                )}
                {active.id === "rent" && role && (
                    <>
                        {canConfigureFines(role) && <FinesSettings embedded />}
                        {canConfigureRentSettings(role) && <RentSettings embedded hideOnlinePaymentToggle />}
                    </>
                )}
                {active.id === "payments" && (
                    <>
                        <GatewaySettings embedded />
                        <OnlinePaymentSwitch />
                    </>
                )}
            </section>
        </div>
    );
}
```

`src/app/[locale]/dashboard/properties/[id]/page.tsx:238`: `href="/dashboard/settings/rent-settings"` → `href="/dashboard/settings?section=rent"`.

- [ ] **Step 5: Run** — `npx vitest run "src/app/[locale]/dashboard/settings" src/components/settings` → pass; now run the deferred Task 6 Steps 4–5 (`routeRegistry.test.ts` → 3 passed) and commit it.

- [ ] **Step 6: Commit** — `git add "src/app/[locale]/dashboard/settings/page.tsx" "src/app/[locale]/dashboard/settings/__tests__" src/components/settings/OnlinePaymentSwitch.tsx src/components/settings/OrganisationSection.tsx src/components/settings/__tests__/OnlinePaymentSwitch.test.tsx "src/app/[locale]/dashboard/properties/[id]/page.tsx" messages/en.json messages/ar.json && git commit -m "feat(web): one Settings page — Organisation, Users & staff, Rent & fines, Payments" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- "src/app/[locale]/dashboard/settings/page.tsx" "src/app/[locale]/dashboard/settings/__tests__" src/components/settings/OnlinePaymentSwitch.tsx src/components/settings/OrganisationSection.tsx src/components/settings/__tests__/OnlinePaymentSwitch.test.tsx "src/app/[locale]/dashboard/properties/[id]/page.tsx" messages/en.json messages/ar.json`.

### Task 12: Orphan units page gets its inbound link

`/dashboard/properties/[id]/units` has no link today. It is not removed (its occupancy view with RESERVED/MAINTENANCE states is richer than the Units tab); the Units tab links to it.

**Files:** Modify `src/app/[locale]/dashboard/properties/[id]/page.tsx`; create `src/app/[locale]/dashboard/properties/[id]/__tests__/units-page-link.test.tsx`.

- [ ] **Step 1: Failing test** — reuse the mocks of the nearest existing property-detail test (copy the `vi.mock` block of `src/app/[locale]/dashboard/properties/[id]/units/__tests__/units-occupancy.test.tsx` for `next/navigation` `useParams: () => ({ id: "p1" })`, `@/i18n/routing` `Link`, `next-auth/react` role `TENANT_ADMIN`, and a `global.fetch` answering `[]`/`{}`):

```tsx
it("links the Units tab to the full units page", async () => {
    render(<NextIntlClientProvider locale="en" messages={en}><PropertyDetailPage /></NextIntlClientProvider>);
    fireEvent.click(await screen.findByRole("button", { name: /units/i }));
    expect(await screen.findByTestId("property-units-page-link")).toHaveAttribute("href", "/dashboard/properties/p1/units");
});
```

- [ ] **Step 2: Run — fails** (no such test id).

- [ ] **Step 3: Implement** — directly above `<UnitsTab … />` inside `{activeTab === "units" && (`, wrap in a fragment and add:

```tsx
<div className="flex justify-end mb-3">
    <Link href={`/dashboard/properties/${propertyId}/units`} data-testid="property-units-page-link"
        className="text-xs font-semibold text-primary hover:underline">
        {tNav("openUnitsPage")}
    </Link>
</div>
```
with `const tNav = useTranslations("Navigation");` next to the page's other `useTranslations` calls (key added in Task 3).

- [ ] **Step 4: Run — passes; commit** (`-- "src/app/[locale]/dashboard/properties/[id]/page.tsx" "src/app/[locale]/dashboard/properties/[id]/__tests__/units-page-link.test.tsx"`, message `fix(web): link the property units page from the Units tab`).

### Task 13: Action catalog + coverage test (v1: Settings, shell)

Spec safeguard 2. The catalog is the checked-in list of every action in today's inventory with its new location; the test renders each location and fails when a listed action is missing. PR 1 fills the Settings and shell rows; Task 20 (PR 2) adds Collections and ledgers; Task 28 (PR 3) adds lease, list and Home rows.

**Files:** Create `src/lib/ui/actionCatalog.ts`, `src/lib/ui/__tests__/action-coverage.test.tsx`.

**Interfaces** — Produces:
- `type ActionLocation = "settings.organisation" | "settings.users" | "settings.rent" | "settings.payments" | "operations.staff" | "header"` (PR 2 and PR 3 extend this union)
- `interface CatalogEntry { id: string; was: string; location: ActionLocation; probe: string }`
- `const ACTION_CATALOG: CatalogEntry[]`

- [ ] **Step 1: Catalog**

```ts
// src/lib/ui/actionCatalog.ts
/**
 * Every action a user could take on 2026-09-25 (inventory: sidebar, lease
 * header/cards/tabs, cheque grid, list rows, settings), mapped to where it
 * lives now. `probe` is the data-testid the coverage test must find inside
 * that location. Adding a location means adding its renderer to
 * src/lib/ui/__tests__/action-coverage.test.tsx.
 */
export type ActionLocation =
    | "settings.organisation" | "settings.users" | "settings.rent" | "settings.payments"
    | "operations.staff" | "header";

export interface CatalogEntry { id: string; was: string; location: ActionLocation; probe: string }

export const ACTION_CATALOG: CatalogEntry[] = [
    { id: "users.manage", was: "/superadmin/users (sidebar Users)", location: "settings.users", probe: "probe-users" },
    { id: "staff.manage", was: "/dashboard/staff (sidebar Staff)", location: "settings.users", probe: "probe-staff" },
    { id: "staff.page", was: "/dashboard/staff (sidebar Staff)", location: "operations.staff", probe: "probe-staff" },
    { id: "fines.org", was: "/dashboard/settings/fines", location: "settings.rent", probe: "probe-fines" },
    { id: "rent.perProperty", was: "/dashboard/settings/rent-settings", location: "settings.rent", probe: "probe-rent" },
    { id: "gateway.config", was: "/dashboard/settings/gateway", location: "settings.payments", probe: "probe-gateway" },
    { id: "online.switch", was: "rent-settings online-payment toggle", location: "settings.payments", probe: "probe-online-switch" },
    { id: "org.info", was: "(new, read-only)", location: "settings.organisation", probe: "probe-organisation" },
    { id: "help", was: "sidebar Help & Guides", location: "header", probe: "header-help" },
    { id: "notifications", was: "header bell", location: "header", probe: "header-notifications" },
];
```

(`TopHeader`'s bell `<button>` gets `data-testid="header-notifications"` in this task.)

- [ ] **Step 2: Coverage test**

```tsx
// src/lib/ui/__tests__/action-coverage.test.tsx
import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ACTION_CATALOG, type ActionLocation } from "../actionCatalog";

vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
const query = { current: "" };
vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams(query.current), usePathname: () => "/en/dashboard", useRouter: () => ({ push: vi.fn() }), useParams: () => ({}) }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN", name: "U" } } }), signOut: vi.fn() }));
vi.mock("@/i18n/routing", () => ({ Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>, useRouter: () => ({ push: vi.fn() }) }));
vi.mock("@/hooks/useTenantFeatures", () => ({ useTenantFeatures: () => ({ isEnabled: () => true, tenantSlug: "acme", features: {}, loading: false }) }));
vi.mock("@/components/ui/GlobalSearch", () => ({ default: () => null }));
for (const [mod, id] of [
    ["@/components/settings/FinesSettings", "probe-fines"], ["@/components/settings/RentSettings", "probe-rent"],
    ["@/components/settings/GatewaySettings", "probe-gateway"], ["@/components/settings/OnlinePaymentSwitch", "probe-online-switch"],
    ["@/components/settings/OrganisationSection", "probe-organisation"], ["@/components/staff/StaffManager", "probe-staff"],
    ["@/components/users/UsersManager", "probe-users"],
] as const) {
    vi.doMock(mod, () => ({ default: () => <div data-testid={id} /> }));
}

async function renderLocation(loc: ActionLocation): Promise<HTMLElement> {
    global.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => [] })) as unknown as typeof fetch;
    if (loc.startsWith("settings.")) {
        query.current = `section=${loc.split(".")[1]}`;
        const { default: Page } = await import("@/app/[locale]/dashboard/settings/page");
        return render(<Page />).container;
    }
    if (loc === "operations.staff") {
        const { default: Page } = await import("@/app/[locale]/dashboard/staff/page");
        return render(<Page />).container;
    }
    const { TopHeader } = await import("@/components/ui/TopHeader");
    const { NavShellProvider } = await import("@/components/nav/NavShellContext");
    return render(<NavShellProvider><TopHeader /></NavShellProvider>).container;
}

afterEach(cleanup);

describe("action coverage — every inventoried action is still reachable", () => {
    const byLocation = new Map<ActionLocation, typeof ACTION_CATALOG>();
    for (const e of ACTION_CATALOG) byLocation.set(e.location, [...(byLocation.get(e.location) ?? []), e]);

    it.each([...byLocation.keys()])("%s", async loc => {
        const container = await renderLocation(loc);
        const missing = byLocation.get(loc)!.filter(e => !container.querySelector(`[data-testid="${e.probe}"]`));
        expect(missing.map(e => `${e.id} (was: ${e.was})`)).toEqual([]);
    });

    it("has unique ids", () => {
        const ids = ACTION_CATALOG.map(e => e.id);
        expect(new Set(ids).size).toBe(ids.length);
    });
});
```

(`vi.doMock` inside a loop is hoisting-safe because each module is only imported inside `renderLocation`, after the mocks are registered.)

- [ ] **Step 3: Run** — `npx vitest run src/lib/ui/__tests__/action-coverage.test.tsx` → 7 passed. Mutation check: delete `<OnlinePaymentSwitch />` from the Settings page → `settings.payments` fails naming `online.switch`; revert.

- [ ] **Step 4: Commit** (`-- src/lib/ui src/components/ui/TopHeader.tsx`, message `test(web): action catalog and coverage test for settings and shell`).

### Task 14: Help centre, guided tours and e2e/walkthrough specs follow the new shell

**Files:** Modify `src/lib/help.ts`, `src/content/help/{admin--tenant-settings,admin--managing-staff,admin--super-admin-guide,finance--chart-of-accounts,leases--payment-schedules,getting-started--welcome}.md` and the mirrored entries in `src/lib/helpArticles.ts`, `src/components/tour/tours/{admin-onboarding,finance-overview,super-admin}.ts`, `e2e/rbac/sidebar-visibility.spec.ts`, `walkthrough/accounting-v2-plan1.spec.ts`; create `src/lib/__tests__/help-nav-paths.test.ts`.

- [ ] **Step 1: Failing test**

```ts
// src/lib/__tests__/help-nav-paths.test.ts
import { describe, expect, it } from "vitest";
import "../helpArticles";
import { getAllArticles } from "../helpLoader";
import { HELP_PAGE_MAP } from "../help";
import { legacyRedirect } from "../nav/routeMap";

describe("help centre follows the new navigation", () => {
    it("maps no help page to a moved URL", () => {
        const moved = Object.keys(HELP_PAGE_MAP).filter(p => legacyRedirect(new URL(p, "http://x")) !== null);
        expect(moved).toEqual([]);
    });
    it("covers the new pages", () => {
        for (const p of ["/dashboard/settings", "/dashboard/finance/journals"]) expect(HELP_PAGE_MAP[p], p).toBeTruthy();
    });
    it("tells no one to find a page by an old sidebar path", () => {
        const stale = /Finance > Payments|Settings > (Account|Payment Gateway|Rent Settings)|\*\*Staff\*\* from the sidebar|\*\*Users\*\* from the sidebar|Finance > (Chart|Journal)/;
        const hits = getAllArticles().filter(a => stale.test(a.content)).map(a => a.slug);
        expect(hits).toEqual([]);
    });
});
```
(`getAllArticles` is exported by `src/lib/helpLoader.ts`; importing `../helpArticles` registers every article.)

- [ ] **Step 2: Run — fails** (`/dashboard/settings/gateway` etc. still mapped; stale phrases present).

- [ ] **Step 3: Update**
  - `src/lib/help.ts` `HELP_PAGE_MAP`: remove `'/dashboard/settings/gateway'`, `'/dashboard/settings/rent-settings'`; add `'/dashboard/settings': { article: 'admin--tenant-settings' }`, `'/dashboard/finance/journals': { article: 'finance--chart-of-accounts', tour: 'finance-overview' }`, `'/dashboard/finance/account-template': { article: 'finance--chart-of-accounts' }`. (`/dashboard/staff` stays.)
  - Help prose (md + `helpArticles.ts`, identical edits): "Go to **Settings > Account Mappings**" → "Go to **Accounting › One-time setup › Property account template**"; "**Settings > Payment Gateway Configuration**" → "**Settings › Payments**"; "**Settings > Rent Settings**" → "**Settings › Rent & fines**"; "Go to **Staff** from the sidebar" → "Go to **Operations › Staff** (also under **Settings › Users & staff**)"; "Go to **Users** from the sidebar" → "Go to **Settings › Administration › Users**"; "**Finance > Chart of Accounts**" → "**Accounting › Accounts › Chart of Accounts**"; "**Finance > Journal Vouchers**" → "**Accounting › Journal Entries › Journal Voucher**"; "**Finance > Payments** from the sidebar" → "**Cheque / Cash Collection** from the left rail"; "Navigate between modules using the left sidebar menu" → "Pick a section on the left rail, then a page in the panel beside it".
  - Tours: `admin-onboarding.ts` step `sidebar-nav` text → `'The rail on the left holds your sections; the panel beside it lists the pages of the section you are in.'`; `sidebar-properties` target → `'[data-tour="sidebar-leases"]'` is the Leasing rail icon (tourId of the Leasing section), so change `sidebar-properties` step's target to `'[data-tour="sidebar-leases"]'` and title `'Leasing'`, text `'Tenancy contracts, tenants, properties & units.'`; delete the now-duplicate `sidebar-leases` step; `help-fab` step stays; add after it `{ id: 'header-help', target: '[data-tour="header-help"]', title: 'Help & Guides', text: 'Articles and tours for every page.', position: 'bottom' }`. `finance-overview.ts`: its `sidebar-journals`, `sidebar-general-ledger`, `sidebar-cheques-register` targets are panel items with the same `data-tour` ids (Task 5 kept the ids) — they render only while the Accounting panel is showing, so set `nextRoute: '/dashboard/finance/journals'` on the `journals-link` step. `super-admin.ts`: targets `sidebar-tenants`/`sidebar-users` are items of the Settings panel's Administration group — add `nextRoute: '/dashboard/settings'` to the first of them.
  - `e2e/rbac/sidebar-visibility.spec.ts`: the sidebar locator becomes `page.getByTestId('nav-rail')`; expectations become rail sections by `data-rail`:

```ts
const RAIL: Record<string, { visible: string[]; hidden: string[] }> = {
  'tenant-admin': { visible: ['home', 'leasing', 'collection', 'accounting', 'operations', 'settings'], hidden: [] },
  'property-manager': { visible: ['home', 'leasing', 'collection', 'accounting', 'operations'], hidden: ['settings'] },
  'tenant-user': { visible: ['home'], hidden: ['leasing', 'collection', 'accounting', 'operations', 'settings'] },
  'renter': { visible: ['home'], hidden: ['leasing', 'collection', 'accounting', 'settings'] },
};
// visible: await expect(rail.locator(`[data-rail="${id}"]`)).toBeVisible();
// hidden:  await expect(rail.locator(`[data-rail="${id}"]`)).toHaveCount(0);
// and: await expect(page.locator('a[href$="/dashboard/my-unit"]')).toHaveCount(0);
```
  - `walkthrough/accounting-v2-plan1.spec.ts` (lines ~300, ~708, ~735): replace `page.locator('nav[data-tour="sidebar-nav"]')` with `page.getByTestId('nav-panel')` after `await page.goto('/en/dashboard/finance/journals')`; `toBeVisible()` on links in collapsed groups becomes `toHaveCount(1)`; `/settings/account-template` and `/settings/fiscal` hrefs become `/finance/account-template` and `/finance/fiscal`; headings `'Fiscal year & period lock'` → `en.Ledger.fiscal` ("Year End Closing"). Old `page.goto('/en/dashboard/settings/…')` lines stay — they now exercise the redirect.

- [ ] **Step 4: Run** — `npx vitest run src/lib/__tests__` → pass. `npx tsc --noEmit` → no output (tours are TS).

- [ ] **Step 5: Commit** (`-- src/lib/help.ts src/lib/helpArticles.ts src/content/help src/components/tour/tours src/lib/__tests__/help-nav-paths.test.ts e2e/rbac/sidebar-visibility.spec.ts walkthrough/accounting-v2-plan1.spec.ts`, message `docs(web): help centre, tours and e2e follow the rail + panel shell`).

### Task 15: Playwright sweep — every dashboard route, EN + AR, three roles, two widths

Spec safeguard 4. Standalone config modelled on `walkthrough/accounting-v2-plan4.config.ts`: local dev stack (Next on 3001, backend on 8081 — the ports the walkthroughs use), a disposable organisation provisioned through the API with the same calls plan 4 makes (`POST /api/auth/login` as `admin@rentaxis.com`, `POST /api/admin/tenants`, `POST /api/admin/users` per role, `POST /api/v1/finance/accounts/seed`), sign-in through the real login form (`#login-email`, `#login-password`) and the session banked only once its cookie exists (plan 4's `bankSession`). Route list = `DASHBOARD_ROUTES` filtered by `routeAllows`; moved routes = `ROUTE_MOVES`.

**Files:** Create `walkthrough/ui-sweep.config.ts`, `walkthrough/ui-sweep.spec.ts`.

**Interfaces** — Consumes: `DASHBOARD_ROUTES`, `routeAllows` (Task 6), `ROUTE_MOVES` (Task 1), `createProperty`, `createUnit`, `createRenter`, `createLease`, `generateCheques`, `postLease` from `e2e/helpers/api-client.ts` (which reads `BACKEND_URL`).

- [ ] **Step 1: Config**

```ts
// walkthrough/ui-sweep.config.ts
import { defineConfig, devices } from '@playwright/test';
import path from 'node:path';

/** Admin UI simplification sweep (spec 2026-09-25 safeguard 4). No video: this is a gate, not a recording. */
export default defineConfig({
    testDir: __dirname,
    fullyParallel: false,
    workers: 1,
    retries: 0,
    timeout: 15 * 60_000,
    expect: { timeout: 20_000 },
    reporter: [['list']],
    outputDir: path.join(__dirname, 'raw'),
    use: { ...devices['Desktop Chrome'], baseURL: process.env.WT_BASE_URL || 'http://localhost:3001', trace: 'retain-on-failure' },
    projects: [{ name: 'sweep', testMatch: /ui-sweep\.spec\.ts/ }],
});
```

- [ ] **Step 2: Spec**

```ts
// walkthrough/ui-sweep.spec.ts
import { test, expect, type Browser, type Page } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { DASHBOARD_ROUTES, routeAllows } from '../src/lib/nav/routeRegistry';
import { ROUTE_MOVES } from '../src/lib/nav/routeMap';
import type { UserRole } from '../src/lib/rbac';
import { createLease, createProperty, createRenter, createUnit, generateCheques, postLease } from '../e2e/helpers/api-client';

const BACKEND = process.env.WT_BACKEND_URL || 'http://localhost:8081';
const BASE_URL = process.env.WT_BASE_URL || 'http://localhost:3001';
const SUFFIX = Math.random().toString(36).slice(2, 7);
const STATE_DIR = path.join(__dirname, 'raw', `ui-sweep-${SUFFIX}`);
const ROLES: { role: UserRole; slug: string }[] = [
    { role: 'TENANT_ADMIN', slug: 'admin' }, { role: 'PROPERTY_MANAGER', slug: 'manager' }, { role: 'ACCOUNTANT', slug: 'accountant' },
];
const LOCALES = ['en', 'ar'] as const;
const WIDTHS = [{ width: 1366, height: 800 }, { width: 390, height: 844 }] as const;
/** [id] routes this run seeds no row for; each is listed so skipping is a decision, not an accident. */
const UNSEEDED = new Set([
    '/dashboard/tickets/[id]', '/dashboard/meetings/[id]', '/dashboard/listings/[id]', '/dashboard/finance/vendors/[id]',
    '/dashboard/finance/payables/payment-runs/[id]', '/dashboard/finance/bank-reconciliation/[id]',
]);
/** Console errors that predate this change, each with its reason. Empty on purpose — add only with a linked issue. */
const KNOWN_CONSOLE: RegExp[] = [];

type Actor = { id: string; role: string; tenantId: string | null };
type Fx = { tenantId: string; creds: Record<string, { email: string; password: string }>; propertyId: string; renterId: string; leaseId: string; journalId: string };
let fx: Fx;

async function api<T>(actor: Actor | null, method: string, apiPath: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (actor) {
        headers['X-User-Id'] = actor.id; headers['X-User-Role'] = actor.role;
        if (actor.tenantId) { headers['X-Tenant-Id'] = actor.tenantId; headers['X-User-Tenant-Id'] = actor.tenantId; }
    }
    const res = await fetch(`${BACKEND}${apiPath}`, { method, headers, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    const text = await res.text();
    if (res.status >= 400) throw new Error(`${method} ${apiPath} → ${res.status}: ${text.slice(0, 300)}`);
    return (text ? JSON.parse(text) : {}) as T;
}

async function bankSession(browser: Browser, email: string, password: string, file: string) {
    const context = await browser.newContext({ baseURL: BASE_URL });
    const page = await context.newPage();
    await page.goto('/en/auth/login');
    await page.locator('#login-email').fill(email);
    await page.locator('#login-password').fill(password);
    await page.getByRole('button', { name: /sign in/i }).click();
    await page.waitForURL(/\/dashboard/, { timeout: 60_000 });
    await expect.poll(async () => (await context.cookies()).some(c => c.name.endsWith('next-auth.session-token')), { timeout: 20_000 }).toBe(true);
    await context.storageState({ path: file });
    await context.close();
}

function resolve(route: string): string | null {
    if (UNSEEDED.has(route)) return null;
    return route
        .replace('/properties/[id]', `/properties/${fx.propertyId}`)
        .replace('/renters/[id]', `/renters/${fx.renterId}`)
        .replace('/leases/[id]', `/leases/${fx.leaseId}`)
        .replace('/journals/[id]', `/journals/${fx.journalId}`)
        .replace('/help/[slug]', '/help/getting-started--welcome');
}

async function check(page: Page, url: string, failures: string[]) {
    const errors: string[] = [];
    const onConsole = (m: { type(): string; text(): string }) => {
        if (m.type() === 'error' && !KNOWN_CONSOLE.some(re => re.test(m.text()))) errors.push(m.text());
    };
    const onPageError = (e: Error) => errors.push(`pageerror: ${e.message}`);
    page.on('console', onConsole);
    page.on('pageerror', onPageError);
    const res = await page.goto(url, { waitUntil: 'networkidle' });
    if (!res || res.status() !== 200) failures.push(`${url}: HTTP ${res?.status()}`);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    if (overflow > 1) failures.push(`${url}: page scrolls sideways by ${overflow}px`);
    for (const e of errors) failures.push(`${url}: console ${e.slice(0, 200)}`);
    page.off('console', onConsole);
    page.off('pageerror', onPageError);
}

test.describe.configure({ mode: 'serial' });

test('00 provision an organisation, the three roles and a posted contract', async ({ browser }) => {
    fs.mkdirSync(STATE_DIR, { recursive: true });
    const su = await api<{ id: string; role: string }>(null, 'POST', '/api/auth/login', { email: 'admin@rentaxis.com', password: 'admin123' });
    const superAdmin: Actor = { id: su.id, role: su.role, tenantId: null };
    const tenant = await api<{ id: string }>(superAdmin, 'POST', '/api/admin/tenants', { name: `WALKTHROUGH-UI-SWEEP ${SUFFIX}` });
    const scoped: Actor = { ...superAdmin, tenantId: tenant.id };
    const password = `Sweep!${SUFFIX}9`;
    const creds: Fx['creds'] = {};
    const ids: Record<string, string> = {};
    for (const { role, slug } of ROLES) {
        const email = `sweep-${slug}-${SUFFIX}@example.invalid`;
        const user = await api<{ id: string }>(scoped, 'POST', '/api/admin/users', { name: `Sweep ${slug}`, email, password, role, tenantId: tenant.id });
        creds[role] = { email, password };
        ids[role] = user.id;
    }
    await api(scoped, 'POST', '/api/v1/finance/accounts/seed');
    const admin = { id: ids.TENANT_ADMIN, role: 'TENANT_ADMIN', tenantId: tenant.id };
    const property = await createProperty(admin.id, admin.role, tenant.id, { nameEn: `Sweep House ${SUFFIX}`, emirate: 'DUBAI', type: 'RESIDENTIAL' });
    await api(scoped, 'POST', `/api/admin/users/${ids.PROPERTY_MANAGER}/properties/${property.id}`);
    const unit = await createUnit(admin.id, admin.role, tenant.id, { propertyId: property.id, unitNumber: `SW-${SUFFIX}`, expectedRent: 60_000 });
    const renter = await createRenter(admin.id, admin.role, tenant.id, { nameEn: `Sweep Tenant ${SUFFIX}`, email: `sweep-renter-${SUFFIX}@example.invalid` });
    const draft = await createLease(admin.id, admin.role, tenant.id, { unitId: unit.id, renterId: renter.id, startDate: '2026-01-01', endDate: '2026-12-31', rentAmount: 60_000 });
    await generateCheques(admin.id, admin.role, tenant.id, draft.id, { installments: 4 });
    const posted = await postLease(admin.id, admin.role, tenant.id, draft.id);
    fx = { tenantId: tenant.id, creds, propertyId: property.id, renterId: renter.id, leaseId: draft.id, journalId: posted.tcoJournalId };
    for (const { role } of ROLES) await bankSession(browser, creds[role].email, creds[role].password, path.join(STATE_DIR, `${role}.json`));
    fs.writeFileSync(path.join(STATE_DIR, 'fixture.json'), JSON.stringify(fx, null, 2));
});

for (const { role } of ROLES) {
    for (const locale of LOCALES) {
        for (const viewport of WIDTHS) {
            test(`sweep ${role} ${locale} ${viewport.width}px`, async ({ browser }) => {
                const context = await browser.newContext({ baseURL: BASE_URL, viewport, storageState: path.join(STATE_DIR, `${role}.json`) });
                const page = await context.newPage();
                const failures: string[] = [];
                const skipped: string[] = [];
                for (const entry of DASHBOARD_ROUTES.filter(r => routeAllows(r, role))) {
                    const target = resolve(entry.path);
                    if (!target) { skipped.push(entry.path); continue; }
                    await check(page, `/${locale}${target}`, failures);
                }
                test.info().annotations.push({ type: 'skipped-unseeded', description: skipped.join(', ') });
                await context.close();
                expect(failures).toEqual([]);
            });
        }
    }
}

for (const locale of LOCALES) {
    test(`redirects ${locale}: every moved URL lands on its new home with the query kept`, async ({ browser }) => {
        const context = await browser.newContext({ baseURL: BASE_URL, storageState: path.join(STATE_DIR, 'TENANT_ADMIN.json') });
        const page = await context.newPage();
        for (const move of ROUTE_MOVES) {
            const from = move.from.replace(/:([A-Za-z]+)/g, 'x');
            await page.goto(`/${locale}${from}?probe=1&tab=keep`);
            const url = new URL(page.url());
            expect(url.pathname, move.from).toBe(`/${locale}${move.to.replace(/:([A-Za-z]+)/g, 'x')}`);
            expect(url.searchParams.get('probe'), move.from).toBe('1');
            for (const [k, v] of Object.entries(move.query ?? { tab: 'keep' })) expect(url.searchParams.get(k), `${move.from} ${k}`).toBe(v);
        }
        await context.close();
    });
}
```

The disposable organisation is named `WALKTHROUGH-UI-SWEEP …`; it is local-only and never touches prod.

- [ ] **Step 3: Run** (local stack up: backend on 8081, `PORT=3001 npm run dev` in `web/`):

```bash
cd /Users/kunalsharma/datagami/rentaxis/web && BACKEND_URL=http://localhost:8081 WT_BACKEND_URL=http://localhost:8081 WT_BASE_URL=http://localhost:3001 npx playwright test -c walkthrough/ui-sweep.config.ts
```
Expected: `15 passed` (1 provision + 12 sweeps + 2 redirect tests). Every failure line names the URL and the reason; fix the page (logical properties, `min-w-0`, `overflow-x-auto` on wide tables) rather than adding to `KNOWN_CONSOLE`. A pre-existing console error that is out of scope goes into `KNOWN_CONSOLE` with a comment naming the issue you opened for it.

- [ ] **Step 4: Commit** (`-- walkthrough/ui-sweep.config.ts walkthrough/ui-sweep.spec.ts`, message `test(web): Playwright sweep over every dashboard route in EN/AR for three roles`). `walkthrough/raw/` is already ignored by the walkthrough convention — confirm with `git check-ignore walkthrough/raw/x` (prints the path); if not ignored, do not commit anything under it.

### Task 16: PR 1 gates, push, PR, deploy check

- [ ] **Step 1: Full gates**

```bash
cd /Users/kunalsharma/datagami/rentaxis/web && npx vitest run 2>&1 | tail -5
```
Expected: `Test Files  N passed (N)` with no failures.

```bash
cd /Users/kunalsharma/datagami/rentaxis/web && npx tsc --noEmit && echo TSC_OK
```
Expected: `TSC_OK`.

```bash
cd /Users/kunalsharma/datagami/rentaxis/web && npx vitest run src/lib/__tests__/catalog-parity.test.ts src/lib/__tests__/terminology.test.ts src/components/ui/__tests__/sidebar-localization.test.tsx
```
Expected: all passed (EN/AR key parity, valid ICU, PACT terms).

```bash
cd /Users/kunalsharma/datagami/rentaxis/web && npx eslint src/lib/nav src/components/nav src/components/ui/MvpSidebar.tsx src/components/ui/TopHeader.tsx src/components/settings "src/app/[locale]/dashboard/settings"
```
Expected: no errors.

```bash
cd /Users/kunalsharma/datagami/rentaxis/web && npm run build 2>&1 | tail -3
```
Expected: build succeeds (catches the `PageProps` constraint on the new pages).

Run the Task 15 sweep again → `15 passed`. No backend file changed: `git diff --name-only origin/main -- ../backend | wc -l` → `0`.

- [ ] **Step 2: Push and open the PR**

```bash
cd /Users/kunalsharma/datagami/rentaxis && git push -u origin feat/admin-ui-pr1-nav-settings && gh pr create --base main --title "feat(web): admin UI simplification 1/3 — rail + panel shell, route map, Settings, PACT terms" --body "$(cat <<'BODY'
## What
- Two-level shell (spec §1a): icon rail Home · Leasing · Collection · Accounting · Operations · Settings (+ More), section panel with org switcher, pinned views and status card; rail-only < 1280 px, drawer < 768 px, mirrored in RTL.
- PACT RevenU terminology (labels only, EN + AR).
- routeMap.ts + 308 redirects that keep locale and query (settings/*, accounting setup, my-unit).
- One Settings page (Organisation, Users & staff, Rent & fines, Payments); online-payment switch moved to Payments.
- Fixes: My Unit 404 link removed, units page linked, Gate pass behind GATEPASS, tenant admins no longer sent to /superadmin/users.

## Guards
Per-role nav model tests, RBAC parity (old sidebar vs new shell), route registry ↔ filesystem, action coverage, Playwright sweep (EN/AR × TA/PM/ACC × 1366/390).

Frontend only — no backend change.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
BODY
)"
```

- [ ] **Step 3: Review loop, merge, deploy, production check** — per the standing OK (memory: *PR → review loop → merge → deploy*): address review, keep suites green, merge, deploy. Then on the prod test org walk the ops-manager day (create contract → post → deposit → overdue → renew → settle) and the accountant path through Accounting, in EN and AR, and open three old bookmarks (`/en/dashboard/settings/gateway?x=1`, `/ar/dashboard/settings/fiscal`, `/en/dashboard/my-unit`) to confirm the 308s. Note: a tenant using Gate pass without the GATEPASS flag loses the rail link (spec decision "now actually gated"); the page itself still loads by URL — flag it in the PR description's rollout notes.

---

# PR 2 — Cheque / Cash Collection hub + Accounting (ledger layout)

The Accounting menu itself shipped in PR 1 as the Accounting section panel (spec §1a replaces the top tab bar), including the One-time setup group that collapses once the books are live and the renamed "Cut-over reconciliation". PR 2 builds the Collection hub and the PACT ledger layout.

### Task 17: Branch; cheque and penalty pages become embeddable panels

- [ ] **Step 0: Branch** — after PR 1 is merged: `cd /Users/kunalsharma/datagami/rentaxis && git status --porcelain | head -3` (must be empty) `&& git fetch origin && git checkout -b feat/admin-ui-pr2-collection-accounting origin/main`.

**Files:** `git mv` `src/app/[locale]/dashboard/finance/cheques/page.tsx` → `src/components/collections/ChequeRegisterPanel.tsx`; `…/cheques/collection/page.tsx` → `ToDepositPanel.tsx`; `…/cheques/return-replace/page.tsx` → `ReturnReplacePanel.tsx`; `…/cheques/post-dated/page.tsx` → `PostDatedPanel.tsx`; `src/app/[locale]/dashboard/finance/penalties/page.tsx` → `PenaltiesPanel.tsx`. Wrappers at the five old paths (Task 10 pattern). Create `src/components/collections/__tests__/embedded.test.tsx`.

**Interfaces** — Produces (all default exports):
- `ChequeRegisterPanel({ embedded }: { embedded?: boolean })` — reads `propertyId`/`status`/`search`/`leaseId` from the URL as today, plus `receive=1` (opens the cash-receipt dialog; used by PR 3's "Record payment").
- `ToDepositPanel`, `ReturnReplacePanel`, `PostDatedPanel`: `({ embedded, propertyId }: { embedded?: boolean; propertyId?: string })` — when `propertyId` is passed the panel's own property `<select>` is hidden and the prop drives the query.
- `PenaltiesPanel({ embedded }: { embedded?: boolean })`.

- [ ] **Step 1: Failing test**

```tsx
// src/components/collections/__tests__/embedded.test.tsx
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";

const api = vi.hoisted(() => ({ toDeposit: vi.fn(), list: vi.fn() }));
vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams("receive=1&leaseId=l1") }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
vi.mock("@/i18n/routing", () => ({ Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a> }));
vi.mock("@/components/cheques/ReceiveCashDialog", () => ({ default: ({ open }: { open: boolean }) => (open ? <div data-testid="cash-dialog-open" /> : null) }));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    const page = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 25 };
    return { ...m, chequeApi: { ...m.chequeApi, toDeposit: api.toDeposit.mockResolvedValue(page), list: api.list.mockResolvedValue(page),
        summary: vi.fn().mockResolvedValue({}), aging: vi.fn().mockResolvedValue({ buckets: [] }) } };
});
import ToDepositPanel from "../ToDepositPanel";
import ChequeRegisterPanel from "../ChequeRegisterPanel";

const wrap = (ui: React.ReactNode) => render(<NextIntlClientProvider locale="en" messages={en}>{ui}</NextIntlClientProvider>);
beforeEach(() => { global.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => [] })) as unknown as typeof fetch; });
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe("embedded collection panels", () => {
    it("drop the page h1 for an h2 and hide their own property picker when the hub passes one", async () => {
        wrap(<ToDepositPanel embedded propertyId="p9" />);
        await waitFor(() => expect(api.toDeposit).toHaveBeenCalledWith(expect.objectContaining({ propertyId: "p9" })));
        expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
        expect(screen.getByRole("heading", { level: 2 })).toBeInTheDocument();
        expect(screen.queryByRole("combobox")).toBeNull();
    });

    it("hide the cross-links to the other cheque pages (they are the hub's tabs)", () => {
        const { container } = wrap(<ChequeRegisterPanel embedded />);
        expect(container.querySelector('a[href="/dashboard/finance/cheques/collection"]')).toBeNull();
    });

    it("open the cash receipt straight away for ?receive=1", async () => {
        wrap(<ChequeRegisterPanel embedded />);
        expect(await screen.findByTestId("cash-dialog-open")).toBeInTheDocument();
    });
});
```

- [ ] **Step 2: Run — fails** (unresolved imports).

- [ ] **Step 3: Move and add the props**

```bash
cd /Users/kunalsharma/datagami/rentaxis/web && mkdir -p src/components/collections && F="src/app/[locale]/dashboard/finance" && \
git mv "$F/cheques/page.tsx" src/components/collections/ChequeRegisterPanel.tsx && \
git mv "$F/cheques/collection/page.tsx" src/components/collections/ToDepositPanel.tsx && \
git mv "$F/cheques/return-replace/page.tsx" src/components/collections/ReturnReplacePanel.tsx && \
git mv "$F/cheques/post-dated/page.tsx" src/components/collections/PostDatedPanel.tsx && \
git mv "$F/penalties/page.tsx" src/components/collections/PenaltiesPanel.tsx
```

In each panel: signature with the props above; `const Heading = embedded ? "h2" : "h1";` on the single title element (`ChequeRegisterPanel.tsx:258`, `ToDepositPanel.tsx:111`, `ReturnReplacePanel.tsx:82`, `PostDatedPanel.tsx:106`, `PenaltiesPanel.tsx` `<h1`). Cross-links: in `ChequeRegisterPanel.tsx` wrap the three `<Link href="/dashboard/finance/cheques/{collection,return-replace,post-dated}">` (lines ~262–281) in `{!embedded && (<>…</>)}` — the `open-cash-receipt` button stays; in the other three wrap their back-link `<Link href="/dashboard/finance/cheques">` the same way. Controlled property (`ToDepositPanel`, `ReturnReplacePanel`, `PostDatedPanel`): rename the state to `const [ownPropertyId, setPropertyId] = useState("");`, add `const propertyId = props.propertyId ?? ownPropertyId;` (keep every existing use of `propertyId`), and wrap the property `<select>` in `{props.propertyId === undefined && ( … )}`. Receive: in `ChequeRegisterPanel.tsx` line 89, `useState(false)` → `useState(() => searchParams?.get("receive") === "1")`.

Wrappers (example):

```tsx
// src/app/[locale]/dashboard/finance/cheques/collection/page.tsx
import ToDepositPanel from "@/components/collections/ToDepositPanel";
/** Moved to Cheque / Cash Collection › To deposit; this route 308s there. Kept for its tests. */
export default function ChequeCollectionPage() { return <ToDepositPanel />; }
```

- [ ] **Step 4: Run** — `npx vitest run src/components/collections "src/app/[locale]/dashboard/finance/cheques" "src/app/[locale]/dashboard/finance/penalties"` → all pass (existing `url-filters`, `clear-batch`, `register-actions`, `batch`, `post-dated`, `queue` tests run through the wrappers unchanged).

- [ ] **Step 5: Commit** (`-- src/components/collections "src/app/[locale]/dashboard/finance/cheques" "src/app/[locale]/dashboard/finance/penalties"`, message `refactor(web): cheque and penalty pages become embeddable collection panels`).

### Task 18: Due / Overdue panel

No page lists due or overdue cheques today; `GET /cheques/due` (used by the Home overdue widget) returns every matured unpaid row with its `overdue` flag. The panel is a read view over that endpoint — the cheque actions stay on the register (All cheques tab), which each row links to.

**Files:** Create `src/components/collections/DueChequesPanel.tsx`, `src/components/collections/__tests__/DueChequesPanel.test.tsx`; messages.

**Interfaces** — Consumes: `chequeApi.due(q: ChequeDueQuery)`, `Cheque`, `fmtAmount`, `fmtIsoDate`. Produces: `DueChequesPanel({ overdueOnly, propertyId }: { overdueOnly: boolean; propertyId?: string })`.

- [ ] **Step 1: Messages** — `Collections` gains (EN / AR): `title` "Cheque / Cash Collection" / "تحصيل الشيكات والنقد"; `tabsLabel` "Collection views" / "عروض التحصيل"; `colUnit` "Unit" / "الوحدة"; `colTenant` "Tenant" / "المستأجر"; `colCheque` "Cheque No" / "رقم الشيك"; `colDueDate` "Date" / "التاريخ"; `colAmount` "Amount" / "المبلغ"; `colDaysOverdue` "Days overdue" / "أيام التأخير"; `empty` "Nothing here right now." / "لا يوجد شيء هنا حالياً."; `loadFailed` "Could not load cheques." / "تعذر تحميل الشيكات."; `noAccess` "You do not have access to collection." / "ليست لديك صلاحية الوصول إلى التحصيل."; `firstRows` "Showing the first {count} rows — open the cheque register to filter further." / "يُعرض أول {count} صف — افتح سجل الشيكات لمزيد من التصفية."; `allProperties` "All properties" / "كل العقارات"; `searchPlaceholder` "Search cheque no, tenant or unit" / "ابحث برقم الشيك أو المستأجر أو الوحدة".

- [ ] **Step 2: Failing test**

```tsx
// src/components/collections/__tests__/DueChequesPanel.test.tsx
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("@/i18n/routing", () => ({ Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a> }));
const due = vi.hoisted(() => vi.fn());
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, chequeApi: { ...m.chequeApi, due } };
});
import DueChequesPanel from "../DueChequesPanel";

const row = (id: string, overdue: boolean) => ({ id, leaseId: `l-${id}`, unitIdentifier: `U-${id}`, renterName: `T ${id}`, chequeNumber: id, postingDate: "2026-09-01", chequeDate: "2026-09-01", amount: 1000, overdue, daysOverdue: overdue ? 12 : 0 });
afterEach(cleanup);

describe("DueChequesPanel", () => {
    it("lists every due row on Due and only overdue rows on Overdue", async () => {
        due.mockResolvedValue({ content: [row("1", true), row("2", false)], totalElements: 2, totalPages: 1, number: 0, size: 200 });
        const { unmount } = render(<DueChequesPanel overdueOnly={false} />);
        expect(await screen.findByText("U-1")).toBeInTheDocument();
        expect(screen.getByText("U-2")).toBeInTheDocument();
        unmount();
        render(<DueChequesPanel overdueOnly propertyId="p1" />);
        expect(await screen.findByText("U-1")).toBeInTheDocument();
        expect(screen.queryByText("U-2")).toBeNull();
        expect(due).toHaveBeenLastCalledWith({ propertyId: "p1", page: 0, size: 200 });
    });

    it("links each row to its contract", async () => {
        due.mockResolvedValue({ content: [row("1", true)], totalElements: 1, totalPages: 1, number: 0, size: 200 });
        render(<DueChequesPanel overdueOnly />);
        expect((await screen.findByText("U-1")).closest("a")).toHaveAttribute("href", "/dashboard/leases/l-1");
    });
});
```

- [ ] **Step 3: Implement**

```tsx
// src/components/collections/DueChequesPanel.tsx
"use client";
import { useEffect, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { Link } from "@/i18n/routing";
import { fmtIsoDate } from "@/components/leases/leaseMath";
import { fmtAmount } from "@/lib/api/ledger";
import { chequeApi, type Cheque } from "@/lib/api/leasing";

const FETCH_SIZE = 200;
const th = "text-start px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider whitespace-nowrap";
const td = "px-4 py-3 text-xs text-foreground";

/**
 * Due and Overdue tabs. GET /cheques/due has no overdue-only filter (the
 * Home widget filters client-side the same way), so this reads one bounded
 * page, nearest maturity first, and says so when there may be more.
 */
export default function DueChequesPanel({ overdueOnly, propertyId }: { overdueOnly: boolean; propertyId?: string }) {
    const t = useTranslations("Collections");
    const locale = useLocale();
    const [rows, setRows] = useState<Cheque[] | null>(null);
    const [total, setTotal] = useState(0);
    const [failed, setFailed] = useState(false);

    useEffect(() => {
        setRows(null); setFailed(false);
        chequeApi.due({ propertyId: propertyId || undefined, page: 0, size: FETCH_SIZE })
            .then(p => { const list = p.content ?? []; setRows(overdueOnly ? list.filter(c => c.overdue) : list); setTotal(p.totalElements); })
            .catch(() => setFailed(true));
    }, [overdueOnly, propertyId]);

    if (failed) return <p className="text-xs text-error" role="alert">{t("loadFailed")}</p>;
    if (rows === null) return null;
    if (rows.length === 0) return <p className="text-sm text-muted py-10 text-center">{t("empty")}</p>;
    return (
        <div className="bg-surface border border-border rounded-xl overflow-x-auto" data-testid={overdueOnly ? "collections-overdue" : "collections-due"}>
            <table className="w-full min-w-[640px]">
                <thead><tr className="bg-input/50">
                    <th className={th}>{t("colUnit")}</th><th className={th}>{t("colTenant")}</th><th className={th}>{t("colCheque")}</th>
                    <th className={th}>{t("colDueDate")}</th><th className={`${th} text-end`}>{t("colAmount")}</th>
                    {overdueOnly && <th className={`${th} text-end`}>{t("colDaysOverdue")}</th>}
                </tr></thead>
                <tbody>
                    {rows.map(c => (
                        <tr key={c.id} className="border-t border-border hover:bg-input/30">
                            <td className={td}><Link href={`/dashboard/leases/${c.leaseId}`} className="text-primary hover:underline">{c.unitIdentifier ?? "—"}</Link></td>
                            <td className={td}>{c.renterName ?? "—"}</td>
                            <td className={td}><bdi dir="ltr">{c.chequeNumber ?? "—"}</bdi></td>
                            <td className={td}>{fmtIsoDate(c.chequeDate ?? c.postingDate, locale)}</td>
                            <td className={`${td} text-end tabular-nums`}>{fmtAmount(c.amount)}</td>
                            {overdueOnly && <td className={`${td} text-end tabular-nums text-error`}>{c.daysOverdue}</td>}
                        </tr>
                    ))}
                </tbody>
            </table>
            {total > FETCH_SIZE && <p className="px-4 py-2 text-[11px] text-muted">{t("firstRows", { count: FETCH_SIZE })}</p>}
        </div>
    );
}
```
(`fmtIsoDate(iso, locale)` from `src/components/leases/leaseMath.ts` — the To-deposit page formats the same column the same way.)

- [ ] **Step 4: Run — passes; commit** (`-- src/components/collections/DueChequesPanel.tsx src/components/collections/__tests__/DueChequesPanel.test.tsx messages/en.json messages/ar.json`, message `feat(web): due and overdue cheque views`).

### Task 19: The Collection hub — status pills with counts, property filter, search; old URLs redirect

Spec §2 + §1a list-page pattern: pills **To deposit · Due · Overdue · Returned / replace · Post-dated · Penalties** (plus **Cheque register**, the old register, which must stay reachable: it is the only home of cancel, clear-batch, receipt and the cash receipt), then a property filter and a search box.

Counts come from endpoints that already exist: To deposit = `GET /cheques/to-deposit` `totalElements`; Due, Overdue, Returned = `GET /cheques/summary` `dueCount`, `overdueCount`, `bouncedCount`; Penalties = `GET /penalties?status=PROPOSED` `totalElements`. Post-dated and Cheque register show no count (post-dated is per month; the register is everything).

**Files:** Create `src/app/[locale]/dashboard/collections/page.tsx`, `src/components/collections/CollectionPills.tsx`, `src/app/[locale]/dashboard/collections/__tests__/hub.test.tsx`. Modify `src/lib/nav/collectionsModel.ts`, `src/lib/nav/navModel.ts`, `src/lib/nav/routeMap.ts`, `src/lib/nav/routeRegistry.ts`, `src/lib/nav/__tests__/{routeMap,models,navModel}.test.ts`, `src/app/[locale]/dashboard/page.tsx`, `src/components/dashboard/{ChequesToDepositWidget,OverduePaymentsWidget}.tsx`, `src/components/ui/{GlobalSearch,TopHeader}.tsx`, `src/app/[locale]/dashboard/notifications/page.tsx`, `src/app/[locale]/dashboard/__tests__/overdue-card-link.test.tsx`, `src/lib/help.ts`.

**Interfaces**
- Consumes: `buildCollectionsTabs`, `CollectionsTabId`; the five panels + `DueChequesPanel`; `chequeApi.toDeposit`, `chequeApi.summary`, `penaltyApi.list`.
- Produces: `CollectionsPage()`; `CollectionPills({ tabs, active, counts, propertyId })`; `type PillCounts = Partial<Record<CollectionsTabId, number>>`; `function usePillCounts(role: UserRole | undefined, propertyId: string): PillCounts`.

- [ ] **Step 1: Point the model at the hub (tests first)** — in `models.test.ts` change the Collections expectation to `["deposit", "due", "overdue", "returned", "post-dated", "penalties", "all"]` and add:

```ts
it("points every tab at the hub", () => {
    expect(buildCollectionsTabs("TENANT_ADMIN").map(t => t.href)).toEqual(
        ["deposit", "due", "overdue", "returned", "post-dated", "penalties", "all"].map(id => `/dashboard/collections?tab=${id}`));
});
```
In `navModel.test.ts`: TENANT_ADMIN/PROPERTY_MANAGER `collection` items become `["deposit", "due", "overdue", "returned", "post-dated", "penalties", "all"]`; the `activeNav` rows `/en/dashboard/finance/cheques/collection` and `/en/dashboard/finance/cheques` become `["/en/dashboard/collections", "collection", null]`; add `expect(buildNav(ctx("TENANT_ADMIN")).find(s => s.id === "collection")!.savedViews.map(v => v.href)).toEqual(["/dashboard/collections?tab=overdue"]);`. In `routeMap.test.ts` `CASES` add:

```ts
["/en/dashboard/finance/cheques", "/en/dashboard/collections?tab=all"],
["/en/dashboard/finance/cheques/collection", "/en/dashboard/collections?tab=deposit"],
["/en/dashboard/finance/cheques/return-replace", "/en/dashboard/collections?tab=returned"],
["/en/dashboard/finance/cheques/post-dated", "/en/dashboard/collections?tab=post-dated"],
["/en/dashboard/finance/penalties", "/en/dashboard/collections?tab=penalties"],
```
and the bookmark case from Review Focus #2:

```ts
it("keeps a register bookmark's filters and lease", () => {
    const out = legacyRedirect(new URL("http://x/ar/dashboard/finance/cheques?status=BOUNCED&leaseId=l1&propertyId=p1"))!;
    expect(out.pathname).toBe("/ar/dashboard/collections");
    expect(Object.fromEntries(out.searchParams)).toEqual({ status: "BOUNCED", leaseId: "l1", propertyId: "p1", tab: "all" });
});
```

- [ ] **Step 2: Hub test**

```tsx
// src/app/[locale]/dashboard/collections/__tests__/hub.test.tsx
import { cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
const role = { current: "TENANT_ADMIN" };
const query = { current: "" };
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: role.current } } }) }));
vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams(query.current), useRouter: () => ({ replace: vi.fn(), push: vi.fn() }), usePathname: () => "/en/dashboard/collections" }));
vi.mock("@/i18n/routing", () => ({ Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>, useRouter: () => ({ replace: vi.fn(), push: vi.fn() }) }));
vi.mock("@/components/finance/useNameLookup", () => ({ useNameLookup: () => ({ options: [{ id: "p1", label: "Belle Vue" }], name: () => "", loading: false }) }));
for (const [m, id] of [["ToDepositPanel", "deposit"], ["ReturnReplacePanel", "returned"], ["PostDatedPanel", "post-dated"], ["PenaltiesPanel", "penalties"], ["ChequeRegisterPanel", "all"]] as const) {
    vi.doMock(`@/components/collections/${m}`, () => ({ default: (p: { propertyId?: string }) => <div data-testid={`panel-${id}`} data-property={p.propertyId ?? ""} /> }));
}
vi.doMock("@/components/collections/DueChequesPanel", () => ({ default: ({ overdueOnly }: { overdueOnly: boolean }) => <div data-testid={overdueOnly ? "panel-overdue" : "panel-due"} /> }));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m,
        chequeApi: { ...m.chequeApi, toDeposit: vi.fn().mockResolvedValue({ totalElements: 3 }), summary: vi.fn().mockResolvedValue({ dueCount: 5, overdueCount: 2, bouncedCount: 1 }) },
        penaltyApi: { ...m.penaltyApi, list: vi.fn().mockResolvedValue({ totalElements: 4 }) } };
});

async function renderHub() {
    const { default: Page } = await import("../page");
    return render(<Page />);
}
beforeEach(() => { role.current = "TENANT_ADMIN"; query.current = ""; });
afterEach(cleanup);

describe("Cheque / Cash Collection hub", () => {
    it.each([["", "deposit"], ["tab=due", "due"], ["tab=overdue", "overdue"], ["tab=returned", "returned"],
        ["tab=post-dated", "post-dated"], ["tab=penalties", "penalties"], ["tab=all", "all"], ["tab=bogus", "deposit"]])("?%s shows %s", async (q, id) => {
        query.current = q;
        await renderHub();
        expect(screen.getByTestId(`panel-${id}`)).toBeInTheDocument();
    });

    it("shows counts on the pills from the existing summary endpoints", async () => {
        await renderHub();
        expect(await within(screen.getByTestId("collections-tab-deposit")).findByText("3")).toBeInTheDocument();
        expect(within(screen.getByTestId("collections-tab-due")).getByText("5")).toBeInTheDocument();
        expect(within(screen.getByTestId("collections-tab-overdue")).getByText("2")).toBeInTheDocument();
        expect(within(screen.getByTestId("collections-tab-returned")).getByText("1")).toBeInTheDocument();
        expect(within(screen.getByTestId("collections-tab-penalties")).getByText("4")).toBeInTheDocument();
    });

    it("hands the property filter to the panel and keeps it on the pill links", async () => {
        query.current = "tab=returned&propertyId=p1";
        await renderHub();
        expect(screen.getByTestId("panel-returned")).toHaveAttribute("data-property", "p1");
        expect(screen.getByTestId("collections-tab-due")).toHaveAttribute("href", "/dashboard/collections?tab=due&propertyId=p1");
    });

    it("sends a search to the cheque register", async () => {
        await renderHub();
        expect(screen.getByTestId("collections-search").closest("form")).toHaveAttribute("action", "/en/dashboard/collections");
    });

    it("refuses a role with no collection tab", async () => {
        role.current = "TENANT_USER";
        await renderHub();
        expect(screen.getByTestId("collections-no-access")).toBeInTheDocument();
    });
});
```

- [ ] **Step 3: Run — fails.**

- [ ] **Step 4: Implement**

`src/lib/nav/collectionsModel.ts` — `TABS` becomes (hrefs at the hub, Due/Overdue added):

```ts
const hub = (id: CollectionsTabId) => `/dashboard/collections?tab=${id}`;
const TABS: CollectionsTab[] = [
    tab("deposit", hub("deposit"), "tabDeposit", "canManageCheques"),
    tab("due", hub("due"), "tabDue", "canManageCheques"),
    tab("overdue", hub("overdue"), "tabOverdue", "canManageCheques"),
    tab("returned", hub("returned"), "tabReturned", "canManageCheques"),
    tab("post-dated", hub("post-dated"), "tabPostDated", "canManageCheques"),
    tab("penalties", hub("penalties"), "tabPenalties", "canProposePenalties"),
    tab("all", hub("all"), "tabAll", "canManageCheques"),
];
```

`src/lib/nav/navModel.ts`: `const COLLECTION_MATCH = ["/dashboard/collections"];` and the Collection section gets `savedViews: tabs.some(t => t.id === "overdue") ? [pi("saved-overdue", "/dashboard/collections?tab=overdue", { ns: "Collections", key: "tabOverdue" }, "saved-overdue")] : []`. `activeNav` must light the pill: for `/dashboard/collections` the item is resolved by the page itself, so `activeNav` returns `item: null` there (items carry a query) — the panel highlights none; the hub's pills show the active one.

`src/lib/nav/routeMap.ts` `ROUTE_MOVES` gains:

```ts
    // Cheque / Cash Collection hub (spec §2).
    { from: "/dashboard/finance/cheques", to: "/dashboard/collections", query: { tab: "all" } },
    { from: "/dashboard/finance/cheques/collection", to: "/dashboard/collections", query: { tab: "deposit" } },
    { from: "/dashboard/finance/cheques/return-replace", to: "/dashboard/collections", query: { tab: "returned" } },
    { from: "/dashboard/finance/cheques/post-dated", to: "/dashboard/collections", query: { tab: "post-dated" } },
    { from: "/dashboard/finance/penalties", to: "/dashboard/collections", query: { tab: "penalties" } },
```

`src/lib/nav/routeRegistry.ts`: delete the five `${F}/cheques…` and `${F}/penalties` rows; add `r(\`${D}/collections\`, role => hasPermission(role, "canManageCheques") || hasPermission(role, "canProposePenalties"))`.

```tsx
// src/components/collections/CollectionPills.tsx
"use client";
import { Link } from "@/i18n/routing";
import { cn } from "@/lib/utils";
import type { CollectionsTab, CollectionsTabId } from "@/lib/nav/collectionsModel";
import { useLabel } from "@/lib/nav/useLabel";

export type PillCounts = Partial<Record<CollectionsTabId, number>>;

export default function CollectionPills({ tabs, active, counts, propertyId, label }:
    { tabs: CollectionsTab[]; active: CollectionsTabId; counts: PillCounts; propertyId: string; label: string }) {
    const tr = useLabel();
    return (
        <nav aria-label={label} className="flex flex-wrap gap-2" data-testid="collections-pills">
            {tabs.map(t => {
                const href = propertyId ? `${t.href}&propertyId=${encodeURIComponent(propertyId)}` : t.href;
                const n = counts[t.id];
                return (
                    <Link key={t.id} href={href} data-testid={t.testId} aria-current={t.id === active ? "page" : undefined}
                        className={cn("inline-flex items-center gap-1.5 rounded-full border px-3 py-1.5 text-[12.5px] font-medium",
                            t.id === active ? "border-[var(--ink-900)] bg-[var(--ink-900)] text-white" : "border-border bg-surface text-[var(--ink-600)] hover:bg-[var(--sand-100)]")}>
                        {tr(t.label)}
                        {n !== undefined && <span className={cn("rounded-full px-1.5 text-[11px] tabular-nums", t.id === active ? "bg-white/20" : "bg-[var(--sand-100)]")}>{n}</span>}
                    </Link>
                );
            })}
        </nav>
    );
}
```

```tsx
// src/app/[locale]/dashboard/collections/page.tsx
"use client";
import { useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useSession } from "next-auth/react";
import { useLocale, useTranslations } from "next-intl";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { buildCollectionsTabs, type CollectionsTabId } from "@/lib/nav/collectionsModel";
import { chequeApi, penaltyApi } from "@/lib/api/leasing";
import { useNameLookup } from "@/components/finance/useNameLookup";
import CollectionPills, { type PillCounts } from "@/components/collections/CollectionPills";
import ToDepositPanel from "@/components/collections/ToDepositPanel";
import DueChequesPanel from "@/components/collections/DueChequesPanel";
import ReturnReplacePanel from "@/components/collections/ReturnReplacePanel";
import PostDatedPanel from "@/components/collections/PostDatedPanel";
import PenaltiesPanel from "@/components/collections/PenaltiesPanel";
import ChequeRegisterPanel from "@/components/collections/ChequeRegisterPanel";

/** Pill counts from endpoints that already exist; a failed call just leaves its pill without a number. */
export function usePillCounts(role: UserRole | undefined, propertyId: string): PillCounts {
    const [counts, setCounts] = useState<PillCounts>({});
    useEffect(() => {
        setCounts({});
        const pid = propertyId || undefined;
        if (hasPermission(role, "canManageCheques")) {
            chequeApi.toDeposit({ propertyId: pid, page: 0, size: 1 }).then(p => setCounts(c => ({ ...c, deposit: p.totalElements }))).catch(() => {});
            chequeApi.summary(pid).then(s => setCounts(c => ({ ...c, due: s.dueCount, overdue: s.overdueCount, returned: s.bouncedCount }))).catch(() => {});
        }
        if (hasPermission(role, "canProposePenalties")) {
            penaltyApi.list({ status: "PROPOSED", propertyId: pid, page: 0, size: 1 }).then(p => setCounts(c => ({ ...c, penalties: p.totalElements }))).catch(() => {});
        }
    }, [role, propertyId]);
    return counts;
}

export default function CollectionsPage() {
    const t = useTranslations("Collections");
    const locale = useLocale();
    const { data: session } = useSession();
    const role = session?.user?.role as UserRole | undefined;
    const params = useSearchParams();
    const tabs = buildCollectionsTabs(role);
    const requested = params?.get("tab") as CollectionsTabId | null;
    const active = tabs.find(x => x.id === requested)?.id ?? tabs[0]?.id;
    const propertyId = params?.get("propertyId") ?? "";
    const counts = usePillCounts(role, propertyId);
    const properties = useNameLookup("properties", tabs.length > 0);

    if (!active) {
        return <div className="max-w-3xl bg-surface rounded-xl border border-border p-10 text-center" data-testid="collections-no-access"><p className="text-sm text-muted">{t("noAccess")}</p></div>;
    }
    return (
        <div className="space-y-5">
            <h1 className="text-xl font-bold text-foreground tracking-tight">{t("title")}</h1>
            <CollectionPills tabs={tabs} active={active} counts={counts} propertyId={propertyId} label={t("tabsLabel")} />
            <form action={`/${locale}/dashboard/collections`} method="get" className="flex flex-col sm:flex-row gap-2" data-testid="collections-filters">
                <input type="hidden" name="tab" value={active === "all" ? "all" : active} />
                <select name="propertyId" defaultValue={propertyId} aria-label={t("allProperties")}
                    onChange={e => e.currentTarget.form?.requestSubmit()}
                    className="bg-surface border border-border rounded-lg px-3 py-2 text-xs">
                    <option value="">{t("allProperties")}</option>
                    {properties.options.map(p => <option key={p.id} value={p.id}>{p.label}</option>)}
                </select>
                <input type="search" name="search" defaultValue={params?.get("search") ?? ""} placeholder={t("searchPlaceholder")}
                    data-testid="collections-search" onFocus={e => { const tab = e.currentTarget.form?.elements.namedItem("tab") as HTMLInputElement | null; if (tab) tab.value = "all"; }}
                    className="flex-1 bg-surface border border-border rounded-lg px-3 py-2 text-xs" />
            </form>
            <div key={`${active}-${propertyId}`}>
                {active === "deposit" && <ToDepositPanel embedded propertyId={propertyId} />}
                {active === "due" && <DueChequesPanel overdueOnly={false} propertyId={propertyId} />}
                {active === "overdue" && <DueChequesPanel overdueOnly propertyId={propertyId} />}
                {active === "returned" && <ReturnReplacePanel embedded propertyId={propertyId} />}
                {active === "post-dated" && <PostDatedPanel embedded propertyId={propertyId} />}
                {active === "penalties" && <PenaltiesPanel embedded />}
                {active === "all" && <ChequeRegisterPanel embedded />}
            </div>
        </div>
    );
}
```
The filter form is a plain `GET` to `/${locale}/dashboard/collections`, so the locale is kept without a redirect hop, and focusing the search box switches the hidden `tab` to `all` (search is a register filter — `filtersFromQuery` reads `search`). `useNameLookup("properties")` returns `{ name, options, loading }`; `options` is already locale-resolved and sorted.

Links now pointing at the old URLs (each would still work through the 308, but should not need it):
- `src/app/[locale]/dashboard/page.tsx:389` Overdue KPI → `/dashboard/collections?tab=overdue`; `overdue-card-link.test.tsx` expectation likewise.
- `ChequesToDepositWidget.tsx:31` → `/dashboard/collections?tab=deposit`; `OverduePaymentsWidget.tsx:42` → `/dashboard/collections?tab=overdue` (and their tests' expectations).
- `GlobalSearch.tsx:134` → `` `/${locale}/dashboard/collections?tab=all&search=${encoded}` `` (and `GlobalSearch.test.tsx`).
- `TopHeader.tsx:115` and `notifications/page.tsx:166` `PAYMENT:` → `/dashboard/collections?tab=all`.
- `src/lib/help.ts`: `'/dashboard/finance/cheques'` key → `'/dashboard/collections'`.
Confirm none remain: `grep -rn '"/dashboard/finance/cheques\|/dashboard/finance/penalties' src --include='*.tsx' --include='*.ts' | grep -v __tests__ | grep -v routeMap.ts` → only the five wrapper page files' doc comments.

- [ ] **Step 5: Run** — `npx vitest run src/lib/nav "src/app/[locale]/dashboard/collections" src/components/dashboard "src/app/[locale]/dashboard/__tests__" src/components/ui` → all pass. The RBAC parity test still passes unchanged (the legacy cheque URLs canonicalise to the hub tabs; Due/Overdue are in `NEW_DESTINATIONS`).

- [ ] **Step 6: Commit** (`-- src/lib/nav "src/app/[locale]/dashboard/collections" src/components/collections/CollectionPills.tsx "src/app/[locale]/dashboard/page.tsx" "src/app/[locale]/dashboard/__tests__/overdue-card-link.test.tsx" src/components/dashboard src/components/ui/GlobalSearch.tsx src/components/ui/__tests__/GlobalSearch.test.tsx src/components/ui/TopHeader.tsx "src/app/[locale]/dashboard/notifications/page.tsx" src/lib/help.ts`, message `feat(web): Cheque / Cash Collection hub with status pills; old cheque URLs redirect`).

### Task 20: General Ledger and Tenant Ledger in the PACT report layout; Chart of Accounts columns

Spec "Terminology — Ledger report layout". Frontend only, from what the endpoints already return: `GET /finance/ledger` and `GET /finance/ledger/renter/{id}` both answer `AccountLedger[]` — one entry per account (`accountCode`, `accountName`, `accountNameAr`, `openingBalance`, `rows`, `totalDebit`, `totalCredit`, `closingBalance`, `truncated`), each row carrying `entryDate`, `entryNumber`, `particular`, `narration`, `debit`, `credit`, `balance`, `propertyId`, `unitId`, `renterId`. Unit, Tower and Tenant names are resolved client-side with the existing `useNameLookup("units" | "properties" | "renters")`, so **no column is omitted**. `LedgerTable` already draws the account band, the Tenant Name band, Sub Total and Report Total; this task moves the arithmetic into a tested pure module, adds the Tower column in PACT order (Unit · Tower · Tenant) and keeps Narration last (it is shown today — not removed).

**Files:** Create `src/lib/finance/ledgerReport.ts`, `src/lib/finance/__tests__/ledgerReport.test.ts`. Modify `src/components/finance/LedgerTable.tsx`, `src/components/finance/__tests__/LedgerTable.test.tsx`, `src/app/[locale]/dashboard/finance/accounts/page.tsx`, `src/app/[locale]/dashboard/finance/accounts/__tests__/page.test.tsx`.

**Interfaces**
- Consumes: `AccountLedger`, `LedgerRow`, `fmtBalance`, `fmtAmount` (`src/lib/api/ledger.ts`).
- Produces:
  - `interface LedgerTotals { debit: number; credit: number; balance: number }`
  - `interface LedgerGroup { accountId: string; code: string; ledger: AccountLedger; opening: number; rows: LedgerRow[]; subTotal: LedgerTotals; truncated: boolean }`
  - `function buildLedgerReport(ledgers: AccountLedger[]): { groups: LedgerGroup[]; total: LedgerTotals }`
  - `function drCr(n: number): string` (= `fmtBalance`, re-exported under the PACT name)

- [ ] **Step 1: Failing test**

```ts
// src/lib/finance/__tests__/ledgerReport.test.ts
import { describe, expect, it } from "vitest";
import type { AccountLedger, LedgerRow } from "@/lib/api/ledger";
import { buildLedgerReport, drCr } from "../ledgerReport";

const row = (n: string, debit: number, credit: number, balance: number): LedgerRow => ({
    entryId: `e${n}`, entryNumber: `JV-${n}`, entryDate: "2026-09-01", docType: "JV", particular: "Rent", narration: "",
    debit, credit, balance, propertyId: "p1", unitId: "u1", leaseId: "l1", renterId: "r1", chequeId: null,
});
const ledger = (id: string, code: string, opening: number, rows: LedgerRow[], extra: Partial<AccountLedger> = {}): AccountLedger => ({
    accountId: id, accountCode: code, accountName: `Account ${code}`, accountType: "ASSET", openingBalance: opening, rows,
    totalDebit: rows.reduce((s, r) => s + r.debit, 0), totalCredit: rows.reduce((s, r) => s + r.credit, 0),
    closingBalance: opening + rows.reduce((s, r) => s + r.debit - r.credit, 0), truncated: false, ...extra,
});

describe("buildLedgerReport", () => {
    it("keeps one group per account, in account-code order", () => {
        const r = buildLedgerReport([ledger("b", "1200", 0, []), ledger("a", "1100", 0, [])]);
        expect(r.groups.map(g => g.code)).toEqual(["1100", "1200"]);
    });

    it("sub-totals each account from its own rows: Σdebit, Σcredit, opening + Σdebit − Σcredit", () => {
        const g = buildLedgerReport([ledger("a", "1100", 100, [row("1", 500, 0, 600), row("2", 0, 200, 400)])]).groups[0];
        expect(g.subTotal).toEqual({ debit: 500, credit: 200, balance: 400 });
    });

    it("falls back to the server totals when the rows were truncated", () => {
        const l = ledger("a", "1100", 0, [row("1", 10, 0, 10)], { truncated: true, totalDebit: 999, totalCredit: 1, closingBalance: 998 });
        expect(buildLedgerReport([l]).groups[0].subTotal).toEqual({ debit: 999, credit: 1, balance: 998 });
    });

    it("totals the report across accounts", () => {
        const r = buildLedgerReport([
            ledger("a", "1100", 0, [row("1", 500, 0, 500)]),
            ledger("b", "2100", 0, [row("2", 0, 300, -300)]),
        ]);
        expect(r.total).toEqual({ debit: 500, credit: 300, balance: 200 });
    });

    it("rounds sums to fils so floating error never shows", () => {
        const r = buildLedgerReport([ledger("a", "1100", 0, [row("1", 0.1, 0, 0.1), row("2", 0.2, 0, 0.3)])]);
        expect(r.groups[0].subTotal.debit).toBe(0.3);
    });
});

describe("drCr", () => {
    it("suffixes a debit balance Dr, a credit balance Cr, and zero plain", () => {
        expect(drCr(1234.5)).toBe("1,234.50 Dr");
        expect(drCr(-10)).toBe("10.00 Cr");
        expect(drCr(0)).toBe("0.00");
        expect(drCr(0.004)).toBe("0.00");
    });
});
```

- [ ] **Step 2: Run — fails** (unresolved import).

- [ ] **Step 3: Implement**

```ts
// src/lib/finance/ledgerReport.ts
import { fmtBalance, type AccountLedger, type LedgerRow } from "@/lib/api/ledger";

export interface LedgerTotals { debit: number; credit: number; balance: number }
export interface LedgerGroup { accountId: string; code: string; ledger: AccountLedger; opening: number; rows: LedgerRow[]; subTotal: LedgerTotals; truncated: boolean }

const fils = (n: number) => Math.round(n * 100) / 100;

/**
 * PACT's ledger report (General Ledger, Tenant Ledger): one band per account,
 * its rows, a Sub Total, then a REPORT TOTAL. Balances are debit-positive, so
 * a Sub Total's balance is opening + Σdebit − Σcredit. When the endpoint cut
 * the rows short (`truncated`) the rows no longer add up to the account, so
 * the server's own totals are used for that account.
 */
export function buildLedgerReport(ledgers: AccountLedger[]): { groups: LedgerGroup[]; total: LedgerTotals } {
    const groups = [...ledgers]
        .sort((a, b) => a.accountCode.localeCompare(b.accountCode, "en", { numeric: true }))
        .map(l => {
            const debit = l.truncated ? l.totalDebit : fils(l.rows.reduce((s, r) => s + r.debit, 0));
            const credit = l.truncated ? l.totalCredit : fils(l.rows.reduce((s, r) => s + r.credit, 0));
            const balance = l.truncated ? l.closingBalance : fils(l.openingBalance + debit - credit);
            return { accountId: l.accountId, code: l.accountCode, ledger: l, opening: l.openingBalance, rows: l.rows, subTotal: { debit, credit, balance }, truncated: l.truncated };
        });
    const total = groups.reduce<LedgerTotals>((t, g) => ({
        debit: fils(t.debit + g.subTotal.debit), credit: fils(t.credit + g.subTotal.credit), balance: fils(t.balance + g.subTotal.balance),
    }), { debit: 0, credit: 0, balance: 0 });
    return { groups, total };
}

/** A running balance with PACT's Dr/Cr suffix. */
export const drCr = (n: number): string => fmtBalance(n);
```

`src/components/finance/LedgerTable.tsx`:
- `const report = buildLedgerReport(ledgers);` replaces the `grand` reduce; the REPORT TOTAL row reads `report.total`; its label cell gets `uppercase`.
- `ledgers.map(l => <LedgerBlock … />)` → `report.groups.map(g => <LedgerBlock key={g.accountId} group={g} />)`; inside, `l` = `g.ledger`, Sub Total cells read `g.subTotal.debit|credit|balance` via `fmtAmount`/`drCr`; row balances use `drCr(r.balance)`.
- Add `const towers = useNameLookup("properties", showTenantColumns);`, `const cols = showTenantColumns ? 10 : 7;`, and between the Unit and Tenant header cells `<th className={th}>{t("tower")}</th>` (EN "Tower" / AR present — `Ledger.tower` exists), and in the row `<td className={td}>{towers.name(r.propertyId)}</td>` between unit and tenant cells.

Add to `LedgerTable.test.tsx`, changing its `useNameLookup` mock to `useNameLookup: (kind: string) => ({ name: (id: string | null) => (kind === "properties" && id ? "Tower A" : id ?? ""), options: [], loading: false })` (the fixture's rows must carry a `propertyId`; set one if it is null):

```tsx
it("orders the tenant columns Unit · Tower · Tenant and sums sub-totals client-side", () => {
    render(<NextIntlClientProvider locale="en" messages={en}><LedgerTable ledgers={ledgers} /></NextIntlClientProvider>); // the file's `ledgers` fixture
    const headers = screen.getAllByRole("columnheader").map(h => h.textContent);
    expect(headers.slice(6, 9)).toEqual([en.Ledger.unit, en.Ledger.tower, en.Ledger.tenant]);
    expect(screen.getByText("Tower A")).toBeInTheDocument();
});
```

Chart of Accounts (`accounts/page.tsx` ~line 943, tree view header): the first header cell `{t("code")}` → `{tl("accountCodeLabel")}` ("Account Code"); add `<span className="text-[11px] font-semibold text-muted uppercase tracking-wider hidden sm:inline">{t("accountType")}</span>` as the first span of the right-hand header group; in `renderTreeRow`, before the row's sub-type span, add `<span data-testid="coa-row-type" className="hidden sm:inline text-[11px] text-muted">{typeLabel(account.accountType)}</span>`. Test in `accounts/__tests__/page.test.tsx`: `expect(await screen.findByText(en.Ledger.accountCodeLabel)).toBeInTheDocument(); expect(screen.getByText(en.Finance.accountType)).toBeInTheDocument(); expect(screen.getAllByTestId("coa-row-type")[0]).toHaveTextContent(/Asset|Liability|Equity|Income|Expense/);`.

- [ ] **Step 4: Run** — `npx vitest run src/lib/finance src/components/finance "src/app/[locale]/dashboard/finance/accounts" "src/app/[locale]/dashboard/finance/general-ledger" "src/app/[locale]/dashboard/finance/tenant-ledger" src/components/leases/__tests__` → all pass (`LeaseJournalsTab` also renders `LedgerTable`).

- [ ] **Step 5: Commit** (`-- src/lib/finance src/components/finance/LedgerTable.tsx src/components/finance/__tests__/LedgerTable.test.tsx "src/app/[locale]/dashboard/finance/accounts"`, message `feat(web): PACT ledger layout — Unit · Tower · Tenant, client-side sub-totals; account code/name/type columns`).

### Task 21: Catalog v2, help/tours/e2e for Collection, PR 2 gates

**Files:** Modify `src/lib/ui/actionCatalog.ts`, `src/lib/ui/__tests__/action-coverage.test.tsx`, `src/components/tour/tours/finance-overview.ts`, `src/content/help/leases--payment-schedules.md` + `src/lib/helpArticles.ts`, `e2e/finance/cheques.spec.ts`, `e2e/finance/accounting-v2.spec.ts`, `e2e-prod/tests/{02-cheque-lifecycle,13a-cheques-and-penalties,13-finance-and-settings}.spec.ts`, `walkthrough/accounting-v2-plan2.spec.ts`, `walkthrough/ui-sweep.spec.ts` (nothing to change — it reads the registry; re-run only).

- [ ] **Step 1: Catalog rows** — extend `ActionLocation` with `"collections.deposit" | "collections.due" | "collections.overdue" | "collections.returned" | "collections.post-dated" | "collections.penalties" | "collections.all" | "ledger.general"` and add:

```ts
    { id: "cheques.depositBatch", was: "/finance/cheques/collection", location: "collections.deposit", probe: "panel-deposit" },
    { id: "cheques.due", was: "(new view of /cheques/due)", location: "collections.due", probe: "panel-due" },
    { id: "cheques.overdue", was: "Home overdue widget", location: "collections.overdue", probe: "panel-overdue" },
    { id: "cheques.replace", was: "/finance/cheques/return-replace", location: "collections.returned", probe: "panel-returned" },
    { id: "cheques.postDated", was: "/finance/cheques/post-dated", location: "collections.post-dated", probe: "panel-post-dated" },
    { id: "penalties.queue", was: "/finance/penalties", location: "collections.penalties", probe: "panel-penalties" },
    { id: "cheques.register", was: "/finance/cheques (deposit, receive, details, cancel, clear, bounce, replace, receipt, cash receipt, clear batch)", location: "collections.all", probe: "panel-all" },
    { id: "ledger.tower", was: "(PACT column)", location: "ledger.general", probe: "ledger-col-tower" },
```
In the coverage test, mock the five panels + `DueChequesPanel` to `panel-*` probes exactly as the hub test does, render `@/app/[locale]/dashboard/collections/page` with `query.current = \`tab=${loc.split(".")[1]}\``, and for `ledger.general` render `LedgerTable` with one ledger fixture (give the Tower `<th>` `data-testid="ledger-col-tower"` in Task 20's markup).

- [ ] **Step 2: Tours, help, e2e** — `finance-overview.ts` step `cheques-link` target stays `[data-tour="sidebar-cheques-register"]` (the Registers › Cheque registers panel item keeps that id) with text `'Cheque registers open Cheque / Cash Collection: deposit, clear, bounce and replace tenants\' cheques.'`. Help `leases--payment-schedules`: "Go to **Cheque / Cash Collection** on the left rail and pick a status pill (To deposit, Due, Overdue, Returned / replace, Post-dated, Penalties) or open the **Cheque register**." e2e/e2e-prod/walkthrough: `grep -rln "finance/cheques\|finance/penalties" e2e e2e-prod/tests walkthrough/*.spec.ts` — `page.goto` of old URLs keeps working (308) and needs no change; replace assertions on the old page `h1` with `page.getByRole('heading', { name: en.Cheques.register })` → it is now an `h2` inside the hub, which `getByRole('heading', { name })` still finds; replace clicks on the removed cross-links (`getByRole('link', { name: en.Cheques.collection })`, `…returnReplace`, `…postDated`) with the pills: `page.getByTestId('collections-tab-deposit')`.

- [ ] **Step 3: Gates** — the Task 16 Step 1 commands (vitest, tsc, parity/terminology, eslint on `src/components/collections src/lib/finance "src/app/[locale]/dashboard/collections"`, `npm run build`), the Task 15 sweep (the registry now includes `/dashboard/collections`; the redirects test now also walks the five cheque moves) → `15 passed`, and no backend diff.

- [ ] **Step 4: Commit, push, PR** — commit the Step 1–2 files (`-- src/lib/ui src/components/tour/tours src/content/help src/lib/helpArticles.ts e2e e2e-prod/tests walkthrough/accounting-v2-plan2.spec.ts`, message `test(web): collection hub in the action catalog; help, tour and e2e follow it`). Push `feat/admin-ui-pr2-collection-accounting`; `gh pr create --title "feat(web): admin UI simplification 2/3 — Cheque / Cash Collection hub, PACT ledger layout"` with a body listing: hub with pills + counts + property filter + search; five old URLs 308 to their tab (query kept); Due/Overdue views over `/cheques/due`; ledger report Unit · Tower · Tenant with client-side sub-totals; chart of accounts code/name/type; guards run; "Frontend only — no backend change."; ending with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.

- [ ] **Step 5: Review loop, merge, deploy, production check** — as Task 16 Step 3, plus open `/en/dashboard/finance/cheques?status=BOUNCED` and `/ar/dashboard/finance/penalties` on prod and confirm the landing tab and filters.

---

# PR 3 — Tenancy contract detail, Home (pipeline + Needs you now), list pages

### Task 22: Branch; lease action availability as a pure model (the "only path" guard)

- [ ] **Step 0: Branch** — after PR 2 is merged: clean-tree check, `git fetch origin && git checkout -b feat/admin-ui-pr3-contract-home-lists origin/main`.

Today the 15 header actions are JSX conditions in `src/app/[locale]/dashboard/leases/[id]/page.tsx` (lines ~567–720). They move, verbatim, into one function the page and the tests share; a matrix test pins the old conditions so the "More actions" split can never drop an action a role had.

**Files:** Create `src/lib/leases/leaseActions.ts`, `src/lib/leases/__tests__/leaseActions.test.ts`.

**Interfaces**
- Consumes: `hasPermission`, `hasRole`, `UserRole`; `LeaseStatus`.
- Produces:
  - `type LeaseActionId = "edit" | "post" | "recordPayment" | "renew" | "settlement" | "extend" | "amend" | "addCharge" | "transfer" | "assignment" | "reduce" | "raisePenalty" | "giveNotice" | "terminate" | "writeOff" | "downloadContract" | "ledger" | "delete"`
  - `interface LeaseActionFacts { status: LeaseStatus; posted: boolean; hasContract: boolean; transferredOut: boolean }`
  - `interface LeasePerms { canDraft: boolean; canPost: boolean; canRenew: boolean; canExtend: boolean; canCheques: boolean; canRaisePenalty: boolean; canGiveNotice: boolean; canPreviewTermination: boolean; canViewSettlement: boolean; canSeeBadDebts: boolean }`
  - `function leasePermsFor(role: UserRole | undefined): LeasePerms`
  - `function availableLeaseActions(f: LeaseActionFacts, p: LeasePerms): LeaseActionId[]`
  - `const PRIMARY_BY_STATUS: Record<LeaseStatus, LeaseActionId[]>`
  - `const MENU_ORDER: LeaseActionId[]`
  - `function splitLeaseActions(available: LeaseActionId[], status: LeaseStatus): { primary: LeaseActionId[]; menu: LeaseActionId[] }`

- [ ] **Step 1: Failing test**

```ts
// src/lib/leases/__tests__/leaseActions.test.ts
import { describe, expect, it } from "vitest";
import type { LeaseStatus } from "@/lib/api/leasing";
import type { UserRole } from "@/lib/rbac";
import { availableLeaseActions, leasePermsFor, splitLeaseActions, type LeaseActionFacts, type LeaseActionId, type LeasePerms } from "../leaseActions";

const STATUSES: LeaseStatus[] = ["DRAFT", "PENDING_SIGNATURE", "ACTIVE", "NOTICE_GIVEN", "TERMINATED", "RENEWED", "EXPIRED", "CLOSED"];
const ROLES: UserRole[] = ["SUPER_ADMIN", "TENANT_ADMIN", "ACCOUNTANT", "PROPERTY_MANAGER", "TENANT_USER", "RENTER", "SECURITY_GUARD"];

/**
 * The header's conditions exactly as page.tsx wrote them on 2026-09-25 (lines
 * 567–720), copied here as the reference the new model must keep satisfying.
 * Do not "fix" this function — it is the before-picture.
 */
function legacyHeader(f: LeaseActionFacts, p: LeasePerms): Set<LeaseActionId> {
    const drafting = f.status === "DRAFT" || f.status === "PENDING_SIGNATURE";
    const out = new Set<LeaseActionId>();
    if (drafting && p.canPost) out.add("post");
    if (f.status === "ACTIVE" && p.canPost) out.add("amend");
    if (["ACTIVE", "EXPIRED", "NOTICE_GIVEN"].includes(f.status) && p.canRenew) out.add("renew");
    if (f.status === "ACTIVE" && p.canExtend) { out.add("extend"); out.add("addCharge"); }
    if ((f.status === "ACTIVE" || f.status === "NOTICE_GIVEN") && f.posted && p.canRenew && !f.transferredOut) out.add("transfer");
    if ((f.status === "ACTIVE" || f.status === "NOTICE_GIVEN") && f.posted && (p.canExtend || p.canRenew)) out.add("reduce");
    if (f.posted) out.add("ledger");
    if (f.hasContract) out.add("downloadContract");
    if (["ACTIVE", "NOTICE_GIVEN", "EXPIRED", "RENEWED"].includes(f.status) && p.canRaisePenalty) out.add("raisePenalty");
    if (f.status === "ACTIVE" && p.canGiveNotice) out.add("giveNotice");
    if ((f.status === "ACTIVE" || f.status === "NOTICE_GIVEN") && p.canPreviewTermination) out.add("terminate");
    if (["TERMINATED", "EXPIRED", "RENEWED", "CLOSED"].includes(f.status) && p.canViewSettlement) out.add("settlement");
    if (drafting && p.canDraft) out.add("delete");
    return out;
}

function* matrix() {
    for (const role of ROLES) for (const status of STATUSES) for (const posted of [true, false])
        for (const hasContract of [true, false]) for (const transferredOut of [true, false])
            yield { role, f: { status, posted, hasContract, transferredOut } as LeaseActionFacts };
}

describe("lease actions", () => {
    it("every legacy header action stays reachable (primary ∪ menu) for every role × status × posted × contract × transferred", () => {
        const lost: string[] = [];
        for (const { role, f } of matrix()) {
            const p = leasePermsFor(role);
            const { primary, menu } = splitLeaseActions(availableLeaseActions(f, p), f.status);
            const reachable = new Set([...primary, ...menu]);
            for (const a of legacyHeader(f, p)) if (!reachable.has(a)) lost.push(`${role} ${JSON.stringify(f)} lost ${a}`);
        }
        expect(lost).toEqual([]);
    });

    it("adds no legacy action where the old header did not show it", () => {
        const extra: string[] = [];
        const NEW: LeaseActionId[] = ["edit", "recordPayment", "assignment", "writeOff"];
        for (const { role, f } of matrix()) {
            const p = leasePermsFor(role);
            const legacy = legacyHeader(f, p);
            for (const a of availableLeaseActions(f, p)) if (!NEW.includes(a) && !legacy.has(a)) extra.push(`${role} ${f.status} ${a}`);
        }
        expect(extra).toEqual([]);
    });

    it("never shows more than three primary buttons", () => {
        for (const { role, f } of matrix()) {
            expect(splitLeaseActions(availableLeaseActions(f, leasePermsFor(role)), f.status).primary.length).toBeLessThanOrEqual(3);
        }
    });

    it("never lists an action twice", () => {
        for (const { role, f } of matrix()) {
            const { primary, menu } = splitLeaseActions(availableLeaseActions(f, leasePermsFor(role)), f.status);
            expect(new Set([...primary, ...menu]).size).toBe(primary.length + menu.length);
        }
    });

    it.each([
        ["DRAFT", ["edit", "post"]],
        ["ACTIVE", ["recordPayment", "renew"]],
        ["NOTICE_GIVEN", ["recordPayment", "renew"]],
        ["EXPIRED", ["renew", "settlement"]],
        ["TERMINATED", ["settlement"]],
    ] as [LeaseStatus, LeaseActionId[]][])("a tenant admin's %s contract leads with %j", (status, want) => {
        const f = { status, posted: status !== "DRAFT", hasContract: true, transferredOut: false };
        expect(splitLeaseActions(availableLeaseActions(f, leasePermsFor("TENANT_ADMIN")), status).primary).toEqual(want);
    });

    it("keeps Renew primary for a property manager on an active contract (their only renewal path)", () => {
        const f = { status: "ACTIVE" as LeaseStatus, posted: true, hasContract: false, transferredOut: false };
        const { primary, menu } = splitLeaseActions(availableLeaseActions(f, leasePermsFor("PROPERTY_MANAGER")), "ACTIVE");
        expect(primary).toContain("renew");
        expect(menu).toEqual(expect.arrayContaining(["giveNotice", "terminate", "transfer", "reduce", "ledger"]));
    });

    it("puts Delete in the menu on a draft, never as a primary button", () => {
        const f = { status: "DRAFT" as LeaseStatus, posted: false, hasContract: false, transferredOut: false };
        const { primary, menu } = splitLeaseActions(availableLeaseActions(f, leasePermsFor("TENANT_ADMIN")), "DRAFT");
        expect(primary).not.toContain("delete");
        expect(menu).toContain("delete");
    });
});
```

- [ ] **Step 2: Run — fails.**

- [ ] **Step 3: Implement**

```ts
// src/lib/leases/leaseActions.ts
import type { LeaseStatus } from "../api/leasing";
import { hasPermission, type UserRole } from "../rbac";

export type LeaseActionId =
    | "edit" | "post" | "recordPayment" | "renew" | "settlement" | "extend" | "amend" | "addCharge" | "transfer"
    | "assignment" | "reduce" | "raisePenalty" | "giveNotice" | "terminate" | "writeOff" | "downloadContract" | "ledger" | "delete";
export interface LeaseActionFacts { status: LeaseStatus; posted: boolean; hasContract: boolean; transferredOut: boolean }
export interface LeasePerms {
    canDraft: boolean; canPost: boolean; canRenew: boolean; canExtend: boolean; canCheques: boolean;
    canRaisePenalty: boolean; canGiveNotice: boolean; canPreviewTermination: boolean; canViewSettlement: boolean; canSeeBadDebts: boolean;
}

const DRAFTING: LeaseStatus[] = ["DRAFT", "PENDING_SIGNATURE"];
const LIVE: LeaseStatus[] = ["ACTIVE", "NOTICE_GIVEN"];
const RENEWABLE: LeaseStatus[] = ["ACTIVE", "EXPIRED", "NOTICE_GIVEN"];
const PENALTY_CHARGEABLE: LeaseStatus[] = ["ACTIVE", "NOTICE_GIVEN", "EXPIRED", "RENEWED"];
const HAS_SETTLEMENT: LeaseStatus[] = ["TERMINATED", "EXPIRED", "RENEWED", "CLOSED"];

/** The page's own permission reads (page.tsx 175–195), in one place. */
export function leasePermsFor(role: UserRole | undefined): LeasePerms {
    return {
        canDraft: hasPermission(role, "canManageLeases"),
        canPost: hasPermission(role, "canPostLeases"),
        canRenew: hasPermission(role, "canRenewLeases"),
        canExtend: hasPermission(role, "canExtendLeases"),
        canCheques: hasPermission(role, "canManageCheques"),
        canRaisePenalty: hasPermission(role, "canApprovePenalties"),
        canGiveNotice: hasPermission(role, "canGiveNotice"),
        canPreviewTermination: hasPermission(role, "canPreviewTermination"),
        canViewSettlement: hasPermission(role, "canViewSettlement"),
        canSeeBadDebts: hasPermission(role, "canAccessFinance"),
    };
}

/**
 * Every action the contract page offers for these facts, with the gates the
 * header used before (see the legacy reference in leaseActions.test.ts).
 * New entries: Edit (jumps to the draft editor already on the page), Record
 * payment (opens the cash receipt already on the cheque register), and
 * Assignment / Write off (the two cards, now opened from the menu) — each
 * behind the gate its target already had.
 */
export function availableLeaseActions(f: LeaseActionFacts, p: LeasePerms): LeaseActionId[] {
    const drafting = DRAFTING.includes(f.status);
    const live = LIVE.includes(f.status);
    const on: Record<LeaseActionId, boolean> = {
        edit: drafting && p.canDraft,
        post: drafting && p.canPost,
        recordPayment: live && f.posted && p.canCheques,
        renew: RENEWABLE.includes(f.status) && p.canRenew,
        settlement: HAS_SETTLEMENT.includes(f.status) && p.canViewSettlement,
        extend: f.status === "ACTIVE" && p.canExtend,
        amend: f.status === "ACTIVE" && p.canPost,
        addCharge: f.status === "ACTIVE" && p.canExtend,
        transfer: live && f.posted && p.canRenew && !f.transferredOut,
        // LeaseAssignmentCard renders whenever the lease is assignable or has posted assignments;
        // assignments only ever exist on a posted contract, so a posted contract always offers the entry.
        assignment: f.posted,
        reduce: live && f.posted && (p.canExtend || p.canRenew),
        raisePenalty: PENALTY_CHARGEABLE.includes(f.status) && p.canRaisePenalty,
        giveNotice: f.status === "ACTIVE" && p.canGiveNotice,
        terminate: live && p.canPreviewTermination,
        writeOff: p.canSeeBadDebts && f.status !== "DRAFT",
        downloadContract: f.hasContract,
        ledger: f.posted,
        delete: drafting && p.canDraft,
    };
    return MENU_ORDER.filter(id => on[id]);
}

export const PRIMARY_BY_STATUS: Record<LeaseStatus, LeaseActionId[]> = {
    DRAFT: ["edit", "post"], PENDING_SIGNATURE: ["edit", "post"],
    ACTIVE: ["recordPayment", "renew"], NOTICE_GIVEN: ["recordPayment", "renew"],
    EXPIRED: ["renew", "settlement"], TERMINATED: ["settlement"], RENEWED: ["settlement"], CLOSED: ["settlement"],
};

/** Spec §5 order, then the actions that are primary in some other status — every LeaseActionId exactly once. */
export const MENU_ORDER: LeaseActionId[] = [
    "extend", "amend", "addCharge", "transfer", "assignment", "reduce", "raisePenalty", "giveNotice", "terminate",
    "writeOff", "downloadContract", "ledger", "post", "renew", "settlement", "recordPayment", "edit", "delete",
];

export function splitLeaseActions(available: LeaseActionId[], status: LeaseStatus): { primary: LeaseActionId[]; menu: LeaseActionId[] } {
    const primary = PRIMARY_BY_STATUS[status].filter(a => available.includes(a)).slice(0, 3);
    const menu = MENU_ORDER.filter(a => available.includes(a) && !primary.includes(a));
    return { primary, menu };
}
```

- [ ] **Step 4: Run — passes** (`npx vitest run src/lib/leases` → all 12 pass). Mutation check: change `giveNotice` to `f.status === "ACTIVE" && p.canPost` → the first test fails listing `PROPERTY_MANAGER … lost giveNotice`; revert.

- [ ] **Step 5: Commit** (`-- src/lib/leases`, message `feat(web): lease action availability model with the legacy-header parity matrix`).

### Task 23: `ActionsMenu` and `SideDrawer`

Menu items are **always mounted** and the panel is `hidden` while closed: the existing lease tests that `fireEvent.click(screen.getByTestId("lease-give-notice"))` keep working unchanged, hidden items stay out of the accessibility tree, and the coverage test can see them.

**Files:** Create `src/components/ui/ActionsMenu.tsx`, `src/components/ui/SideDrawer.tsx`, `src/components/ui/__tests__/ActionsMenu.test.tsx`; messages (`ListActions`, `LeaseActions`).

**Interfaces** — Produces:
- `interface ActionsMenuItem { id: string; label: string; testId: string; onSelect?: () => void; href?: string; destructive?: boolean; icon?: React.ElementType }`
- `ActionsMenu({ label, items, testId, triggerTestId, variant }: { label: string; items: ActionsMenuItem[]; testId: string; triggerTestId: string; variant?: "button" | "icon" })` — renders nothing when `items` is empty.
- `SideDrawer({ open, onClose, title, testId, children }: { open: boolean; onClose: () => void; title: string; testId: string; children: React.ReactNode })`

- [ ] **Step 1: Messages** — `"ListActions": { "actions": "Actions", "rowActions": "Actions for {name}", "filters": "Filters", "removeFilter": "Remove filter: {name}", "more": "More", "allProperties": "All properties", "search": "Search" }` / AR `{ "actions": "الإجراءات", "rowActions": "إجراءات {name}", "filters": "عوامل التصفية", "removeFilter": "إزالة عامل التصفية: {name}", "more": "المزيد", "allProperties": "كل العقارات", "search": "بحث" }`. `"LeaseActions": { "moreActions": "More actions", "recordPayment": "Record payment", "edit": "Edit", "assignment": "Assignment", "writeOff": "Write off", "close": "Close", "tabOverview": "General", "tabPayments": "Cheques", "tabDocuments": "Attachments", "tabActivity": "Activities", "sectionCheques": "Cheques", "sectionPenalties": "Penalties", "sectionJournals": "Journal Vouchers", "sectionRecognition": "Revenue recognition", "sectionVat": "Vat", "sectionContract": "Contract", "sectionAttachments": "Attachments", "sectionAddenda": "Addenda & Ejari", "sectionInteractions": "Notes", "sectionMaintenance": "Maintenance" }` / AR `{ "moreActions": "إجراءات أخرى", "recordPayment": "تسجيل دفعة", "edit": "تعديل", "assignment": "التنازل", "writeOff": "شطب", "close": "إغلاق", "tabOverview": "عام", "tabPayments": "الشيكات", "tabDocuments": "المرفقات", "tabActivity": "الأنشطة", "sectionCheques": "الشيكات", "sectionPenalties": "الغرامات", "sectionJournals": "سندات القيد", "sectionRecognition": "الاعتراف بالإيراد", "sectionVat": "ضريبة القيمة المضافة", "sectionContract": "العقد", "sectionAttachments": "المرفقات", "sectionAddenda": "الملاحق وإيجاري", "sectionInteractions": "الملاحظات", "sectionMaintenance": "الصيانة" }`. (Tab labels follow the PACT contract tabs General · Cheques · Attachments · Activities; the tab ids stay `overview|payments|documents|activity`.)

- [ ] **Step 2: Failing test**

```tsx
// src/components/ui/__tests__/ActionsMenu.test.tsx
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
vi.mock("@/i18n/routing", () => ({ Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a> }));
import ActionsMenu from "../ActionsMenu";

const items = (onA = vi.fn()) => [
    { id: "a", label: "Extend Contract", testId: "item-a", onSelect: onA },
    { id: "b", label: "Ledger", testId: "item-b", href: "/dashboard/finance/tenant-ledger" },
];
afterEach(cleanup);

describe("ActionsMenu", () => {
    it("keeps items mounted but hidden until opened", () => {
        render(<ActionsMenu label="More actions" items={items()} testId="m" triggerTestId="t" />);
        expect(screen.getByTestId("m")).not.toBeVisible();
        expect(screen.getByTestId("item-a")).toBeInTheDocument();
        fireEvent.click(screen.getByTestId("t"));
        expect(screen.getByTestId("m")).toBeVisible();
        expect(screen.getByTestId("t")).toHaveAttribute("aria-expanded", "true");
    });

    it("runs the item and closes", () => {
        const onA = vi.fn();
        render(<ActionsMenu label="More actions" items={items(onA)} testId="m" triggerTestId="t" />);
        fireEvent.click(screen.getByTestId("t"));
        fireEvent.click(screen.getByTestId("item-a"));
        expect(onA).toHaveBeenCalled();
        expect(screen.getByTestId("m")).not.toBeVisible();
    });

    it("closes on Escape and moves focus with the arrow keys", () => {
        render(<ActionsMenu label="More actions" items={items()} testId="m" triggerTestId="t" />);
        fireEvent.click(screen.getByTestId("t"));
        fireEvent.keyDown(screen.getByTestId("m"), { key: "ArrowDown" });
        expect(document.activeElement).toBe(screen.getByTestId("item-a"));
        fireEvent.keyDown(screen.getByTestId("m"), { key: "ArrowDown" });
        expect(document.activeElement).toBe(screen.getByTestId("item-b"));
        fireEvent.keyDown(screen.getByTestId("m"), { key: "Escape" });
        expect(screen.getByTestId("m")).not.toBeVisible();
    });

    it("anchors the menu to the inline end (RTL-safe) and renders nothing without items", () => {
        const { container } = render(<ActionsMenu label="x" items={items()} testId="m" triggerTestId="t" />);
        expect(screen.getByTestId("m").className).toMatch(/\bend-0\b/);
        expect(screen.getByTestId("m").className).not.toMatch(/\b(left|right)-/);
        cleanup();
        const empty = render(<ActionsMenu label="x" items={[]} testId="m" triggerTestId="t" />);
        expect(empty.container).toBeEmptyDOMElement();
        void container;
    });
});
```

- [ ] **Step 3: Implement**

```tsx
// src/components/ui/ActionsMenu.tsx
"use client";
import { useEffect, useRef, useState } from "react";
import { ChevronDown, MoreHorizontal } from "lucide-react";
import { Link } from "@/i18n/routing";
import { cn } from "@/lib/utils";

export interface ActionsMenuItem { id: string; label: string; testId: string; onSelect?: () => void; href?: string; destructive?: boolean; icon?: React.ElementType }

export default function ActionsMenu({ label, items, testId, triggerTestId, variant = "button" }:
    { label: string; items: ActionsMenuItem[]; testId: string; triggerTestId: string; variant?: "button" | "icon" }) {
    const [open, setOpen] = useState(false);
    const root = useRef<HTMLDivElement>(null);
    const panel = useRef<HTMLDivElement>(null);

    useEffect(() => {
        if (!open) return;
        const onDown = (e: MouseEvent) => { if (root.current && !root.current.contains(e.target as Node)) setOpen(false); };
        document.addEventListener("mousedown", onDown);
        return () => document.removeEventListener("mousedown", onDown);
    }, [open]);

    if (items.length === 0) return null;

    const focusables = () => Array.from(panel.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? []);
    const onKeyDown = (e: React.KeyboardEvent) => {
        if (e.key === "Escape") { setOpen(false); return; }
        if (e.key !== "ArrowDown" && e.key !== "ArrowUp") return;
        e.preventDefault();
        const list = focusables();
        const i = list.indexOf(document.activeElement as HTMLElement);
        const next = e.key === "ArrowDown" ? (i + 1) % list.length : (i - 1 + list.length) % list.length;
        list[next]?.focus();
    };
    const itemClass = (d?: boolean) => cn("flex w-full items-center gap-2 px-3 py-2 text-start text-xs font-medium hover:bg-[var(--sand-100)] focus:bg-[var(--sand-100)] focus:outline-none cursor-pointer",
        d ? "text-error" : "text-foreground");

    return (
        <div ref={root} className="relative inline-block">
            <button type="button" data-testid={triggerTestId} aria-haspopup="menu" aria-expanded={open} aria-label={variant === "icon" ? label : undefined}
                onClick={() => setOpen(o => !o)}
                className={variant === "icon"
                    ? "p-1.5 rounded-md text-[var(--ink-600)] hover:bg-[var(--sand-100)] cursor-pointer"
                    : "flex items-center gap-1.5 bg-input text-foreground border border-border px-4 py-2 rounded-lg text-xs font-semibold hover:bg-border cursor-pointer"}>
                {variant === "icon" ? <MoreHorizontal size={16} /> : <>{label}<ChevronDown size={14} /></>}
            </button>
            <div ref={panel} role="menu" aria-label={label} data-testid={testId} hidden={!open} onKeyDown={onKeyDown}
                className="absolute end-0 top-full z-50 mt-1 min-w-[200px] max-w-[calc(100vw-2rem)] overflow-hidden rounded-lg border border-border bg-surface py-1 shadow-lg">
                {items.map(item => {
                    const Icon = item.icon;
                    const body = <>{Icon && <Icon size={14} />}{item.label}</>;
                    return item.href ? (
                        <Link key={item.id} href={item.href} role="menuitem" data-testid={item.testId} className={itemClass(item.destructive)} onClick={() => setOpen(false)}>{body}</Link>
                    ) : (
                        <button key={item.id} type="button" role="menuitem" data-testid={item.testId} className={itemClass(item.destructive)}
                            onClick={() => { setOpen(false); item.onSelect?.(); }}>{body}</button>
                    );
                })}
            </div>
        </div>
    );
}
```

```tsx
// src/components/ui/SideDrawer.tsx
"use client";
import { useEffect } from "react";
import { X } from "lucide-react";

/** A panel that slides in from the inline end; Assignment and Write off open in it. */
export default function SideDrawer({ open, onClose, title, testId, closeLabel, children }:
    { open: boolean; onClose: () => void; title: string; testId: string; closeLabel: string; children: React.ReactNode }) {
    useEffect(() => {
        if (!open) return;
        const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
        window.addEventListener("keydown", onKey);
        return () => window.removeEventListener("keydown", onKey);
    }, [open, onClose]);
    if (!open) return null;
    return (
        <div className="fixed inset-0 z-50">
            <button type="button" aria-label={closeLabel} onClick={onClose} className="absolute inset-0 bg-black/30" />
            <aside role="dialog" aria-modal="true" aria-label={title} data-testid={testId}
                className="absolute inset-y-0 end-0 flex w-full max-w-xl flex-col bg-surface shadow-xl">
                <header className="flex items-center justify-between border-b border-border px-5 py-4">
                    <h2 className="text-sm font-bold text-foreground">{title}</h2>
                    <button type="button" onClick={onClose} aria-label={closeLabel} className="p-1 cursor-pointer"><X size={16} /></button>
                </header>
                <div className="flex-1 overflow-y-auto p-5">{children}</div>
            </aside>
        </div>
    );
}
```
(The `SideDrawer` interface above gains `closeLabel: string` — callers pass `t("close")`.)

- [ ] **Step 4: Run — passes; commit** (`-- src/components/ui/ActionsMenu.tsx src/components/ui/SideDrawer.tsx src/components/ui/__tests__/ActionsMenu.test.tsx messages/en.json messages/ar.json`, message `feat(web): actions menu and side drawer`).

### Task 24: Contract header — ≤ 3 primary buttons + "More actions"; Assignment and Write off in drawers

**Files:** Modify `src/app/[locale]/dashboard/leases/[id]/page.tsx`; create `src/app/[locale]/dashboard/leases/[id]/__tests__/header-actions.test.tsx`.

**Interfaces** — Consumes: `availableLeaseActions`, `splitLeaseActions`, `leasePermsFor`, `LeaseActionId` (Task 22); `ActionsMenu`, `SideDrawer` (Task 23). Produces: DOM contract — `data-testid="lease-actions"` (kept) containing `lease-actions-primary` and the menu `lease-more-actions-menu` (trigger `lease-more-actions`); every action keeps its old test id (`lease-post`, `lease-amend`, `lease-renew`, `lease-extend`, `lease-add-charge`, `lease-transfer`, `lease-reduce`, `lease-ledger`, `lease-raise-penalty`, `lease-give-notice`, `lease-terminate`, `lease-settle`, `lease-delete`) plus `lease-download-contract`, `lease-edit`, `lease-record-payment`, `lease-assignment`, `lease-write-off`; drawers `lease-assignment-drawer`, `lease-write-off-drawer`.

- [ ] **Step 1: Failing test** — copy the mock block and `lease()` fixture from `lifecycle-actions.test.tsx` (same directory) verbatim, then:

```tsx
const primaryIds = () => Array.from(screen.getByTestId("lease-actions-primary").querySelectorAll("[data-testid]")).map(e => e.getAttribute("data-testid"));
const menuIds = () => Array.from(screen.getByTestId("lease-more-actions-menu").querySelectorAll('[role="menuitem"]')).map(e => e.getAttribute("data-testid"));

describe("contract header", () => {
    it("leads an active contract with Record payment and Renew; the rest is in More actions", async () => {
        role = "TENANT_ADMIN";
        onLease("ACTIVE");
        renderPage();
        await screen.findByTestId("lease-actions");
        expect(primaryIds()).toEqual(["lease-record-payment", "lease-renew"]);
        expect(menuIds()).toEqual(expect.arrayContaining(["lease-extend", "lease-amend", "lease-add-charge", "lease-transfer",
            "lease-assignment", "lease-reduce", "lease-raise-penalty", "lease-give-notice", "lease-terminate", "lease-write-off", "lease-ledger"]));
        expect(screen.getByTestId("lease-record-payment")).toHaveAttribute("href", "/dashboard/collections?tab=all&leaseId=lease-1&receive=1");
    });

    it("keeps Renew primary for a property manager and Terminate in the menu", async () => {
        role = "PROPERTY_MANAGER";
        onLease("ACTIVE");
        renderPage();
        await screen.findByTestId("lease-actions");
        expect(primaryIds()).toContain("lease-renew");
        expect(menuIds()).toContain("lease-terminate");
    });

    it("opens the assignment card in a drawer instead of always showing it", async () => {
        role = "TENANT_ADMIN";
        onLease("ACTIVE");
        renderPage();
        await screen.findByTestId("lease-actions");
        expect(screen.queryByTestId("lease-assignment-drawer")).toBeNull();
        fireEvent.click(screen.getByTestId("lease-assignment"));
        expect(await screen.findByTestId("lease-assignment-drawer")).toBeInTheDocument();
    });

    it("keeps Settlement primary on a terminated contract", async () => {
        role = "ACCOUNTANT";
        onLease("TERMINATED");
        renderPage();
        await screen.findByTestId("lease-actions");
        expect(primaryIds()).toEqual(["lease-settle"]);
    });
});
```
Add `vi.mock("@/components/leases/LeaseAssignmentCard", () => ({ default: () => <div data-testid="assignment-card" /> }))` and `vi.mock("@/components/leases/BadDebtCard", () => ({ default: () => <div data-testid="bad-debt-card" /> }))`.

- [ ] **Step 2: Run — fails** (no `lease-actions-primary`).

- [ ] **Step 3: Implement in `page.tsx`**
1. Imports: `ActionsMenu`, `SideDrawer`, `{ availableLeaseActions, leasePermsFor, splitLeaseActions, type LeaseActionId }` from `@/lib/leases/leaseActions`; `const tA = useTranslations("LeaseActions");`.
2. State: `const [drawer, setDrawer] = useState<"assignment" | "writeOff" | null>(null);`.
3. After `const posted = !!lease.postedAt;` (line ~491):

```tsx
const facts = { status: lease.status, posted, hasContract: !!lease.hasContract, transferredOut: !!lease.transferredToLeaseId };
const { primary, menu } = splitLeaseActions(availableLeaseActions(facts, leasePermsFor(userRole)), lease.status);
type Spec = { label: string; testId: string; onSelect?: () => void; href?: string; destructive?: boolean };
const SPEC: Record<LeaseActionId, Spec> = {
    edit: { label: tA("edit"), testId: "lease-edit", onSelect: () => { setTab("overview"); requestAnimationFrame(() => document.getElementById("lease-metadata-editor")?.scrollIntoView({ behavior: "smooth" })); } },
    post: { label: t("postLease"), testId: "lease-post", onSelect: () => setPostOpen(true) },
    recordPayment: { label: tA("recordPayment"), testId: "lease-record-payment", href: `/dashboard/collections?tab=all&leaseId=${lease.id}&receive=1` },
    renew: { label: t("renew"), testId: "lease-renew", onSelect: () => setRenewOpen(true) },
    settlement: { label: tSettlement("title"), testId: "lease-settle", href: `/dashboard/leases/${leaseId}/settlement` },
    extend: { label: t("extend"), testId: "lease-extend", onSelect: () => setExtendOpen(true) },
    amend: { label: t("amendLines"), testId: "lease-amend", onSelect: () => setAmendOpen(true) },
    addCharge: { label: t("addCharge"), testId: "lease-add-charge", onSelect: () => setAddChargeOpen(true) },
    transfer: { label: t("transfer.open"), testId: "lease-transfer", onSelect: () => setTransferOpen(true) },
    assignment: { label: tA("assignment"), testId: "lease-assignment", onSelect: () => setDrawer("assignment") },
    reduce: { label: t("reduction.open"), testId: "lease-reduce", onSelect: () => setReduceOpen(true) },
    raisePenalty: { label: t("raisePenalty"), testId: "lease-raise-penalty", onSelect: () => setPenaltyOpen(true) },
    giveNotice: { label: t("giveNotice"), testId: "lease-give-notice", onSelect: () => { setNoticeError(null); setNoticeOpen(true); } },
    terminate: { label: t("terminate"), testId: "lease-terminate", href: `/dashboard/leases/${leaseId}/terminate`, destructive: true },
    writeOff: { label: tA("writeOff"), testId: "lease-write-off", onSelect: () => setDrawer("writeOff") },
    downloadContract: { label: tMaster("downloadContract"), testId: "lease-download-contract", onSelect: () => downloadBlob(`/api/proxy/v1/leases/${leaseId}/documents`, `contract-${leaseId.slice(0, 8)}.pdf`) },
    ledger: { label: t("ledger"), testId: "lease-ledger", href: `/dashboard/finance/tenant-ledger?renterId=${lease.renterId}&leaseId=${lease.id}` },
    delete: { label: t("deleteDraft"), testId: "lease-delete", onSelect: () => setDeleteOpen(true), destructive: true },
};
```
Every `label` is the exact expression the old button rendered (`t` = `Leasing`, `tMaster` = `MasterData`, `tSettlement` — all already declared in the page), so no visible text changes except through Task 3's values; icons stay as they were (pass each old `<Icon>` as `icon` if desired — optional).
4. Replace the whole `<div className="flex items-center gap-2 flex-wrap" data-testid="lease-actions">…</div>` block with:

```tsx
<div className="flex items-center gap-2 flex-wrap" data-testid="lease-actions">
    <div className="flex items-center gap-2 flex-wrap" data-testid="lease-actions-primary">
        {primary.map((id, i) => {
            const s = SPEC[id];
            const cls = i === 0
                ? "flex items-center gap-2 bg-primary text-primary-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:bg-primary/90 cursor-pointer"
                : "flex items-center gap-2 bg-input text-foreground border border-border px-4 py-2 rounded-lg text-xs font-semibold hover:bg-border cursor-pointer";
            return s.href
                ? <Link key={id} href={s.href} data-testid={s.testId} className={cls}>{s.label}</Link>
                : <button key={id} type="button" data-testid={s.testId} onClick={s.onSelect} className={cls}>{s.label}</button>;
        })}
    </div>
    {drafting && !canPost && (
        <span className="text-[11px] text-muted" data-testid="lease-needs-accountant">{t("savedAsDraftNeedsAccountant")}</span>
    )}
    <ActionsMenu label={tA("moreActions")} testId="lease-more-actions-menu" triggerTestId="lease-more-actions"
        items={menu.map(id => ({ id, ...SPEC[id] }))} />
</div>
```
5. Overview: delete `<LeaseAssignmentCard …/>` and the `BadDebtCard` block from the overview tab; after the tabs' content add:

```tsx
<SideDrawer open={drawer === "assignment"} onClose={() => setDrawer(null)} title={tA("assignment")} closeLabel={tA("close")} testId="lease-assignment-drawer">
    <LeaseAssignmentCard lease={lease} canDraft={canRenew} canPost={canPost} onChanged={loadLease} />
</SideDrawer>
<SideDrawer open={drawer === "writeOff"} onClose={() => setDrawer(null)} title={tA("writeOff")} closeLabel={tA("close")} testId="lease-write-off-drawer">
    <BadDebtCard leaseId={lease.id} canApprove={hasPermission(userRole, "canAccessFinanceOps")} />
</SideDrawer>
```
6. Wrap `<LeaseMetadataEditor …/>` in `<div id="lease-metadata-editor">…</div>`.

- [ ] **Step 4: Run** — `npx vitest run "src/app/[locale]/dashboard/leases/[id]"` → the new file and every existing file (`lifecycle-actions`, `post-gate`, `raise-penalty`, `save-cheques-gate` (after Task 25), `renter-accepted-badge`, `invalid-id-not-found`) pass: items are mounted-but-hidden, so `getByTestId("lease-give-notice")` + `fireEvent.click` still reach them. `components/leases/__tests__/LeaseAssignmentCard.test.tsx` and `BadDebtCard.test.tsx` are untouched.

- [ ] **Step 5: Commit** (`-- "src/app/[locale]/dashboard/leases/[id]/page.tsx" "src/app/[locale]/dashboard/leases/[id]/__tests__/header-actions.test.tsx"`, message `feat(web): contract header — primary actions by status, the rest under More actions`).

### Task 25: Contract tabs 9 → 4 (General · Cheques · Attachments · Activities) with sections; `?tab=` deep links

Tab ids stay `overview | payments | documents | activity` (labels General · Cheques · Attachments · Activities per PACT). Journals / Recognition / Vat become collapsed sections of the Cheques tab — not removed. Layout:
- **General** (`overview`): draft metadata editor, contract & tenant cards, Particulars grid, rent-free periods.
- **Cheques** (`payments`): sections `cheques` (open — the cheque grid, save, bulk upload), `penalties` (open), `journals`, `recognition`, `vat` (only when the contract charges VAT) — collapsed.
- **Attachments** (`documents`): sections `contract` (contract + generate preview), `attachments` (attach/download/delete), `addenda` (Addenda & Ejari panel, moved from General).
- **Activities** (`activity`): sections `interactions` (labelled "Notes"), `maintenance`.

**Files:** Create `src/components/leases/LeaseSection.tsx`, `src/app/[locale]/dashboard/leases/[id]/__tests__/lease-deep-links.test.tsx`. Modify `src/lib/nav/routeMap.ts`, `src/lib/nav/__tests__/routeMap.test.ts`, `src/app/[locale]/dashboard/leases/[id]/page.tsx`, `src/app/[locale]/dashboard/leases/[id]/__tests__/save-cheques-gate.test.tsx`, `e2e/leases/lease-lifecycle.spec.ts`, `e2e/finance/cheques.spec.ts`, `e2e/finance/accounting-v2.spec.ts`, `walkthrough/accounting-v2-plan2.spec.ts`, `walkthrough/accounting-v2-plan3.spec.ts`.

**Interfaces**
- Produces (routeMap.ts):
  - `type LeaseTab = "overview" | "payments" | "documents" | "activity"`
  - `type LeaseSectionId = "cheques" | "penalties" | "journals" | "recognition" | "vat" | "contract" | "attachments" | "addenda" | "interactions" | "maintenance"`
  - `const LEGACY_LEASE_TABS: Record<string, { tab: LeaseTab; section: LeaseSectionId | null }>`
  - `function resolveLeaseTab(tab: string | null | undefined, section: string | null | undefined): { tab: LeaseTab; section: LeaseSectionId | null }`
- Produces: `LeaseSection({ id, title, defaultOpen, forceOpen, children }: { id: LeaseSectionId; title: string; defaultOpen: boolean; forceOpen?: boolean; children: React.ReactNode })` — `<details data-testid="lease-section-{id}">` with `<summary data-testid="lease-section-toggle-{id}">`, scrolled into view when `forceOpen`.

- [ ] **Step 1: Failing tests** — in `routeMap.test.ts`:

```ts
import { LEGACY_LEASE_TABS, resolveLeaseTab } from "../routeMap";

describe("lease ?tab= deep links", () => {
    it.each([
        ["journals", "payments", "journals"], ["recognition", "payments", "recognition"], ["vat", "payments", "vat"],
        ["penalties", "payments", "penalties"], ["contract", "documents", "contract"], ["documents", "documents", "attachments"],
        ["maintenance", "activity", "maintenance"], ["interactions", "activity", "interactions"], ["overview", "overview", null],
        ["payments", "payments", null], ["activity", "activity", null], [null, "overview", null], ["nonsense", "overview", null],
    ])("?tab=%s → %s / %s", (tab, want, section) => {
        expect(resolveLeaseTab(tab, null)).toEqual({ tab: want, section });
    });
    it("lets ?section= pick the section inside a new tab", () => {
        expect(resolveLeaseTab("payments", "vat")).toEqual({ tab: "payments", section: "vat" });
        expect(resolveLeaseTab("payments", "bogus")).toEqual({ tab: "payments", section: null });
    });
    it("covers every one of the nine old tabs", () => {
        for (const old of ["overview", "journals", "recognition", "vat", "penalties", "contract", "maintenance", "documents", "interactions"]) {
            expect(LEGACY_LEASE_TABS[old], old).toBeTruthy();
        }
    });
});
```

```tsx
// src/app/[locale]/dashboard/leases/[id]/__tests__/lease-deep-links.test.tsx
// Mocks and fixture: copy the block from lifecycle-actions.test.tsx, but make
// useSearchParams read `search.current` so each case sets its own query:
//   const search = { current: "" };
//   vi.mock("next/navigation", () => ({ useParams: () => ({ id: "lease-1" }), useSearchParams: () => new URLSearchParams(search.current) }));
// and stub the section bodies:
vi.mock("@/components/leases/LeaseJournalsTab", () => ({ default: () => <div data-testid="probe-journals" /> }));
vi.mock("@/components/leases/RecognitionScheduleTab", () => ({ default: () => <div data-testid="probe-recognition" /> }));
vi.mock("@/components/leases/LeasePenaltiesTab", () => ({ default: () => <div data-testid="probe-penalties" /> }));

describe("contract deep links", () => {
    it.each([
        ["tab=journals", "payments", "journals"],
        ["tab=recognition", "payments", "recognition"],
        ["tab=penalties", "payments", "penalties"],
        ["tab=contract", "documents", "contract"],
        ["tab=documents", "documents", "attachments"],
        ["tab=maintenance", "activity", "maintenance"],
        ["tab=interactions", "activity", "interactions"],
    ])("maps every legacy ?%s to its new tab and opens the section", async (q, tab, section) => {
        search.current = q;
        renderPage();
        expect(await screen.findByTestId(`lease-tab-${tab}`)).toHaveAttribute("aria-selected", "true");
        expect(screen.getByTestId(`lease-section-${section}`)).toHaveAttribute("open");
    });

    it("shows exactly four tabs", async () => {
        search.current = "";
        renderPage();
        await screen.findByTestId("lease-tab-overview");
        expect(screen.getAllByRole("tab").map(t => t.getAttribute("data-testid"))).toEqual(
            ["lease-tab-overview", "lease-tab-payments", "lease-tab-documents", "lease-tab-activity"]);
    });

    it("keeps Journals, Recognition collapsed in Cheques until asked for", async () => {
        search.current = "tab=payments";
        renderPage();
        await screen.findByTestId("lease-section-cheques");
        expect(screen.getByTestId("lease-section-cheques")).toHaveAttribute("open");
        expect(screen.getByTestId("lease-section-journals")).not.toHaveAttribute("open");
        expect(screen.getByTestId("lease-section-recognition")).not.toHaveAttribute("open");
    });
});
```
(The Vat section only renders when `totalsOf(lines, chargeTypes).vat > 0`, which needs a VAT-bearing charge-type catalogue in the fixture; `?tab=vat` is therefore pinned by the `resolveLeaseTab` unit test, and the section's `forceOpen` path is the same code the seven cases above exercise.)

- [ ] **Step 2: Run — fails.**

- [ ] **Step 3: Implement**

```ts
// src/lib/nav/routeMap.ts — append
export type LeaseTab = "overview" | "payments" | "documents" | "activity";
export type LeaseSectionId = "cheques" | "penalties" | "journals" | "recognition" | "vat" | "contract" | "attachments" | "addenda" | "interactions" | "maintenance";

/** The contract page's nine old tabs (and the four new ones) → new tab + section to open. */
export const LEGACY_LEASE_TABS: Record<string, { tab: LeaseTab; section: LeaseSectionId | null }> = {
    overview: { tab: "overview", section: null },
    payments: { tab: "payments", section: null },
    documents: { tab: "documents", section: "attachments" },
    activity: { tab: "activity", section: null },
    journals: { tab: "payments", section: "journals" },
    recognition: { tab: "payments", section: "recognition" },
    vat: { tab: "payments", section: "vat" },
    penalties: { tab: "payments", section: "penalties" },
    contract: { tab: "documents", section: "contract" },
    maintenance: { tab: "activity", section: "maintenance" },
    interactions: { tab: "activity", section: "interactions" },
};
const SECTIONS_BY_TAB: Record<LeaseTab, LeaseSectionId[]> = {
    overview: [], payments: ["cheques", "penalties", "journals", "recognition", "vat"],
    documents: ["contract", "attachments", "addenda"], activity: ["interactions", "maintenance"],
};

export function resolveLeaseTab(tab: string | null | undefined, section: string | null | undefined): { tab: LeaseTab; section: LeaseSectionId | null } {
    const base = (tab && LEGACY_LEASE_TABS[tab]) || LEGACY_LEASE_TABS.overview;
    const explicit = SECTIONS_BY_TAB[base.tab].find(s => s === section) ?? null;
    return { tab: base.tab, section: explicit ?? (tab === "documents" && !section ? "attachments" : base.section) };
}
```
Check against the test: `resolveLeaseTab("documents", null)` → `{documents, attachments}` ✓; `("payments", "vat")` → vat ✓; `("payments","bogus")` → `base.section` = null ✓.

```tsx
// src/components/leases/LeaseSection.tsx
"use client";
import { useEffect, useRef, useState } from "react";
import type { LeaseSectionId } from "@/lib/nav/routeMap";

export default function LeaseSection({ id, title, defaultOpen, forceOpen = false, children }:
    { id: LeaseSectionId; title: string; defaultOpen: boolean; forceOpen?: boolean; children: React.ReactNode }) {
    const [open, setOpen] = useState(defaultOpen || forceOpen);
    const ref = useRef<HTMLDetailsElement>(null);
    useEffect(() => {
        if (forceOpen) { setOpen(true); ref.current?.scrollIntoView?.({ block: "start" }); }
    }, [forceOpen]);
    return (
        <details ref={ref} open={open} data-testid={`lease-section-${id}`} id={`lease-section-${id}`}
            onToggle={e => setOpen((e.currentTarget as HTMLDetailsElement).open)}
            className="bg-surface border border-border rounded-xl">
            <summary data-testid={`lease-section-toggle-${id}`} className="cursor-pointer select-none px-5 py-3 text-sm font-semibold text-foreground">
                {title}
            </summary>
            <div className="border-t border-border p-5">{open && children}</div>
        </details>
    );
}
```
(Children mount only while open, so a collapsed Journals/Recognition section makes no request — same as an unvisited tab today.)

`page.tsx`:
1. `const TABS = ["overview", "payments", "documents", "activity"] as const;` and `type Tab = LeaseTab;` (import `resolveLeaseTab`, `type LeaseTab`, `type LeaseSectionId` from `@/lib/nav/routeMap`).
2. `const initial = resolveLeaseTab(searchParams?.get("tab"), searchParams?.get("section"));` `const [tab, setTab] = useState<Tab>(initial.tab);` `const deepSection: LeaseSectionId | null = initial.section;`
3. Tab bar: the `.filter(key => key !== "vat" || totals.vat > 0)` goes away; the container gets `role="tablist"`, each button `role="tab"` `aria-selected={tab === key}` and keeps `data-testid={\`lease-tab-${key}\`}`; `tabLabel(key)` becomes `tA({ overview: "tabOverview", payments: "tabPayments", documents: "tabDocuments", activity: "tabActivity" }[key])`.
4. Regroup the nine `{tab === "…" && (…)}` blocks without editing their contents:

```tsx
{tab === "overview" && (/* the old overview block minus: the cheque-grid card, LeaseAddendaPanel (moved), LeaseAssignmentCard/BadDebtCard (Task 24) */)}
{tab === "payments" && (
    <div className="space-y-4">
        <LeaseSection id="cheques" title={tA("sectionCheques")} defaultOpen forceOpen={deepSection === "cheques"}>{/* the old cheque-grid card, verbatim */}</LeaseSection>
        <LeaseSection id="penalties" title={tA("sectionPenalties")} defaultOpen forceOpen={deepSection === "penalties"}>{/* old tab === "penalties" body */}</LeaseSection>
        <LeaseSection id="journals" title={tA("sectionJournals")} defaultOpen={false} forceOpen={deepSection === "journals"}>{/* old journals body */}</LeaseSection>
        <LeaseSection id="recognition" title={tA("sectionRecognition")} defaultOpen={false} forceOpen={deepSection === "recognition"}>{/* old recognition body */}</LeaseSection>
        {totals.vat > 0 && <LeaseSection id="vat" title={tA("sectionVat")} defaultOpen={false} forceOpen={deepSection === "vat"}>{/* old vat body */}</LeaseSection>}
    </div>
)}
{tab === "documents" && (
    <div className="space-y-4">
        <LeaseSection id="contract" title={tA("sectionContract")} defaultOpen forceOpen={deepSection === "contract"}>{/* old contract body */}</LeaseSection>
        <LeaseSection id="attachments" title={tA("sectionAttachments")} defaultOpen forceOpen={deepSection === "attachments"}>{/* old documents body */}</LeaseSection>
        {(addenda.length > 0 || canExtend) && (
            <LeaseSection id="addenda" title={tA("sectionAddenda")} defaultOpen forceOpen={deepSection === "addenda"}>
                <LeaseAddendaPanel leaseId={lease.id} addenda={addenda} canRecordEjari={canExtend} onChanged={loadLease} />
            </LeaseSection>
        )}
    </div>
)}
{tab === "activity" && (
    <div className="space-y-4">
        <LeaseSection id="interactions" title={tA("sectionInteractions")} defaultOpen forceOpen={deepSection === "interactions"}><LeaseInteractionsPanel leaseId={leaseId} /></LeaseSection>
        <LeaseSection id="maintenance" title={tA("sectionMaintenance")} defaultOpen forceOpen={deepSection === "maintenance"}>{/* old maintenance body */}</LeaseSection>
    </div>
)}
```
5. `save-cheques-gate.test.tsx`: after `renderPage()` add `fireEvent.click(await screen.findByTestId("lease-tab-payments"));` before looking for `lease-save-cheques` (the grid now lives in the Cheques tab).
6. e2e / walkthrough test ids (a mechanical edit; review the diff):

```bash
cd /Users/kunalsharma/datagami/rentaxis/web && for f in e2e/leases/lease-lifecycle.spec.ts e2e/finance/cheques.spec.ts e2e/finance/accounting-v2.spec.ts walkthrough/accounting-v2-plan2.spec.ts walkthrough/accounting-v2-plan3.spec.ts; do
  perl -0pi -e "s/await page\.getByTestId\('lease-tab-(journals|recognition|penalties|vat)'\)\.click\(\);/await page.getByTestId('lease-tab-payments').click();\n        await page.getByTestId('lease-section-toggle-\1').click();/g; s/await page\.getByTestId\('lease-tab-contract'\)\.click\(\);/await page.getByTestId('lease-tab-documents').click();/g" "$f"; done && git diff --stat e2e walkthrough
```
Penalties is open by default, so a spec that toggles it closes it — for `penalties` drop the toggle line by hand after the substitution. Anything asserting cheque-grid content right after page load now clicks `lease-tab-payments` first.

- [ ] **Step 4: Run** — `npx vitest run src/lib/nav "src/app/[locale]/dashboard/leases/[id]" src/components/leases` → all pass; `npx tsc --noEmit` → no output.

- [ ] **Step 5: Commit** (`-- src/lib/nav/routeMap.ts src/lib/nav/__tests__/routeMap.test.ts src/components/leases/LeaseSection.tsx "src/app/[locale]/dashboard/leases/[id]" e2e/leases e2e/finance walkthrough/accounting-v2-plan2.spec.ts walkthrough/accounting-v2-plan3.spec.ts`, message `feat(web): contract tabs General · Cheques · Attachments · Activities with sections; legacy ?tab= links land on the right section`).

### Task 26: Home — Contract pipeline strip, "Needs you now", Unit Status, one chart

Spec §1a + §6. Order on the page: header (**New Contract** stays the primary action) → **Contract pipeline** (Draft → Upcoming → Active → Expiring ≤ 60 d → Notice → Settlement due; each with its count and oldest item) → **Needs you now** (the Today list) → four KPI cards, the third being **Unit Status** (Occupied / Expiring / Vacant) → one chart (collections vs expected) → Recent activity (kept).

All counts come from endpoints that exist: `GET /leases/paged` (status filter; its `Pageable` also accepts `sort`, which `leaseApi.paged` starts passing — a frontend-only change), `GET /dashboard/summary`, `GET /cheques/to-deposit`, `GET /cheques/summary`, `GET /penalties?status=PROPOSED`, `GET /finance/recognition/status`, `GET /renewals/follow-ups`, `GET /tickets`. Dropped for want of an endpoint: **write-offs awaiting approval** (`/finance/bad-debts` answers per lease only). "Settlement due" = contracts in TERMINATED or EXPIRED (a finalised settlement moves a contract to CLOSED — `LeaseClosureService`).

**Files:** Create `src/lib/dashboard/pipeline.ts`, `src/lib/dashboard/__tests__/pipeline.test.ts`, `src/components/dashboard/ContractPipeline.tsx`, `src/components/dashboard/TodayList.tsx`, `src/components/dashboard/__tests__/TodayList.test.tsx`. Modify `src/lib/api/leasing.ts` (`paged` gains `sort`), `src/app/[locale]/dashboard/page.tsx`, dashboard tests. Delete `src/components/dashboard/{OverduePaymentsWidget,ChequesToDepositWidget,RecognitionBehindWidget}.tsx` and their three tests (their lists now live on Collection › Overdue / To deposit and Accounting › Recognition; their counts are Needs-you-now rows).

**Interfaces**
- `leaseApi.paged(q: { search?: string; status?: LeaseStatus; propertyId?: string; page?: number; size?: number; sort?: string })`
- `type StageId = "draft" | "upcoming" | "active" | "expiring" | "notice" | "settlement"`
- `interface PipelineStage { id: StageId; count: number; capped: boolean; oldest: { leaseId: string; label: string; date: string } | null; href: string }`
- `interface PipelineInput { draft: Page<LeaseDetail>; activeByStart: Page<LeaseDetail>; activeByEnd: Page<LeaseDetail>; notice: Page<LeaseDetail>; terminated: Page<LeaseDetail>; expired: Page<LeaseDetail> }`
- `function buildPipeline(input: PipelineInput, today: string): PipelineStage[]`
- `type TodayRowId = "deposit" | "overdue" | "ending" | "drafts" | "tickets" | "penalties" | "recognition" | "followUps"`
- `interface TodayRowDef { id: TodayRowId; href: string | null; testId: string }`
- `function todayRowDefs(role: UserRole | undefined): TodayRowDef[]`
- `TodayList({ role, pipeline }: { role: UserRole | undefined; pipeline: PipelineStage[] | null })`
- `ContractPipeline({ stages }: { stages: PipelineStage[] })`

- [ ] **Step 1: Messages** — `"Today": { "title": "Needs you now", "deposit": "Cheques to deposit", "overdue": "Overdue payments", "ending": "Contracts ending within 60 days", "drafts": "Draft contracts to post", "tickets": "Open tickets", "penalties": "Penalties awaiting approval", "recognition": "Recognition periods behind", "followUps": "Renewal follow-ups", "allClear": "Nothing needs you right now.", "pipelineTitle": "Contract pipeline", "stageDraft": "Draft", "stageUpcoming": "Upcoming", "stageActive": "Active", "stageExpiring": "Expiring ≤ 60 days", "stageNotice": "Notice", "stageSettlement": "Settlement due", "oldest": "Oldest: {label}", "capped": "{count}+", "unitStatus": "Unit Status", "unitStatusLine": "{occupied} occupied · {expiring} expiring · {vacant} vacant", "portfolioNote": "{properties} properties · {active} active contracts" }` / AR `{ "title": "بحاجة إليك الآن", "deposit": "شيكات للإيداع", "overdue": "مدفوعات متأخرة", "ending": "عقود تنتهي خلال 60 يوماً", "drafts": "مسودات عقود للترحيل", "tickets": "تذاكر مفتوحة", "penalties": "غرامات بانتظار الموافقة", "recognition": "فترات اعتراف متأخرة", "followUps": "متابعات التجديد", "allClear": "لا يوجد ما يحتاجك الآن.", "pipelineTitle": "مسار العقود", "stageDraft": "مسودة", "stageUpcoming": "قادمة", "stageActive": "نشطة", "stageExpiring": "تنتهي خلال 60 يوماً", "stageNotice": "إشعار", "stageSettlement": "تسوية مستحقة", "oldest": "الأقدم: {label}", "capped": "+{count}", "unitStatus": "حالة الوحدات", "unitStatusLine": "{occupied} مشغولة · {expiring} تنتهي قريباً · {vacant} شاغرة", "portfolioNote": "{properties} عقار · {active} عقد نشط" }`.

- [ ] **Step 2: Failing tests**

```ts
// src/lib/dashboard/__tests__/pipeline.test.ts
import { describe, expect, it } from "vitest";
import type { LeaseDetail } from "@/lib/api/leasing";
import type { Page } from "@/lib/api/ledger";
import { buildPipeline, type PipelineInput } from "../pipeline";

const L = (id: string, startDate: string, endDate: string) => ({ id, startDate, endDate, unitIdentifier: `U-${id}`, renterName: `T ${id}` }) as LeaseDetail;
const page = (content: LeaseDetail[], totalElements = content.length, size = 100): Page<LeaseDetail> => ({ content, totalElements, totalPages: 1, number: 0, size });
const TODAY = "2026-09-25";
const input = (over: Partial<PipelineInput> = {}): PipelineInput => ({
    draft: page([L("d1", "2026-10-01", "2027-09-30")], 4),
    activeByStart: page([L("f1", "2026-11-01", "2027-10-31"), L("a1", "2026-01-01", "2026-12-31")], 20, 50),
    activeByEnd: page([L("e1", "2025-11-01", "2026-10-10"), L("e2", "2025-12-01", "2026-11-20"), L("a2", "2026-01-01", "2027-06-30")], 20),
    notice: page([L("n1", "2025-01-01", "2026-10-31")], 2),
    terminated: page([L("t1", "2025-01-01", "2026-08-31")], 3),
    expired: page([], 1),
    ...over,
});

describe("buildPipeline", () => {
    it("counts each stage from the existing paged endpoint", () => {
        const byId = Object.fromEntries(buildPipeline(input(), TODAY).map(s => [s.id, s.count]));
        expect(byId).toEqual({ draft: 4, upcoming: 1, active: 19, expiring: 2, notice: 2, settlement: 4 });
    });

    it("names the oldest item of each stage", () => {
        const s = Object.fromEntries(buildPipeline(input(), TODAY).map(x => [x.id, x.oldest]));
        expect(s.draft).toEqual({ leaseId: "d1", label: "U-d1 · T d1", date: "2026-10-01" });
        expect(s.expiring?.leaseId).toBe("e1");
        expect(s.settlement?.leaseId).toBe("t1");
    });

    it("marks a bounded count as capped when every fetched row qualified", () => {
        const all = Array.from({ length: 100 }, (_, i) => L(`x${i}`, "2025-01-01", "2026-10-01"));
        const exp = buildPipeline(input({ activeByEnd: page(all, 300) }), TODAY).find(s => s.id === "expiring")!;
        expect(exp).toMatchObject({ count: 100, capped: true });
    });

    it("links each stage to the contract list", () => {
        expect(buildPipeline(input(), TODAY).map(s => s.href)).toEqual([
            "/dashboard/leases?status=DRAFT", "/dashboard/leases?status=ACTIVE", "/dashboard/leases?status=ACTIVE",
            "/dashboard/leases?view=expiring", "/dashboard/leases?status=NOTICE_GIVEN", "/dashboard/leases?view=ended",
        ]);
    });
});
```

```tsx
// src/components/dashboard/__tests__/TodayList.test.tsx
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("@/i18n/routing", () => ({ Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a> }));
vi.mock("@/components/dashboard/FollowUpsWidget", () => ({ default: () => <div data-testid="follow-ups-widget" /> }));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m,
        chequeApi: { ...m.chequeApi, toDeposit: vi.fn().mockResolvedValue({ totalElements: 3 }), summary: vi.fn().mockResolvedValue({ overdueCount: 2 }) },
        penaltyApi: { ...m.penaltyApi, list: vi.fn().mockResolvedValue({ totalElements: 1 }) },
        recognitionApi: { ...m.recognitionApi, status: vi.fn().mockResolvedValue({ behind: 0 }) } };
});
import TodayList, { todayRowDefs } from "../TodayList";

beforeEach(() => {
    global.fetch = vi.fn(async (u: RequestInfo | URL) => ({ ok: true, status: 200,
        json: async () => String(u).includes("tickets") ? [{ status: "OPEN" }, { status: "CLOSED" }, { status: "REOPENED" }] : [{}, {}] })) as unknown as typeof fetch;
});
afterEach(cleanup);

describe("Needs you now", () => {
    it("gates rows by the pages they open", () => {
        expect(todayRowDefs("TENANT_ADMIN").map(r => r.id)).toEqual(["deposit", "overdue", "ending", "drafts", "tickets", "penalties", "recognition", "followUps"]);
        expect(todayRowDefs("PROPERTY_MANAGER").map(r => r.id)).toEqual(["deposit", "overdue", "ending", "drafts", "tickets", "followUps"]);
        expect(todayRowDefs("ACCOUNTANT").map(r => r.id)).toEqual(["deposit", "overdue", "ending", "drafts", "penalties", "recognition", "followUps"]);
        expect(todayRowDefs("TENANT_USER")).toEqual([]);
    });

    it("links the cheque rows at the Collection tabs and hides zero rows", async () => {
        const pipeline = [{ id: "expiring", count: 5, capped: false, oldest: null, href: "" }, { id: "draft", count: 0, capped: false, oldest: null, href: "" }] as never;
        render(<TodayList role="TENANT_ADMIN" pipeline={pipeline} />);
        expect(await screen.findByTestId("today-deposit")).toHaveAttribute("href", "/dashboard/collections?tab=deposit");
        expect(screen.getByTestId("today-overdue")).toHaveAttribute("href", "/dashboard/collections?tab=overdue");
        expect(screen.getByTestId("today-ending")).toHaveTextContent("5");
        expect(screen.getByTestId("today-tickets")).toHaveTextContent("2");
        expect(screen.queryByTestId("today-drafts")).toBeNull();
        expect(screen.queryByTestId("today-recognition")).toBeNull();
    });
});
```

- [ ] **Step 3: Run — fails.**

- [ ] **Step 4: Implement**

`src/lib/api/leasing.ts` line 1352:

```ts
  paged: (q: { search?: string; status?: LeaseStatus; propertyId?: string; page?: number; size?: number; sort?: string } = {}) =>
    get<Page<LeaseDetail>>(
      `/leases/paged${qs({ search: q.search, status: q.status, propertyId: q.propertyId, page: q.page ?? 0, size: q.size ?? 25, sort: q.sort })}`,
    ),
```
(Add to `src/lib/api/__tests__/leasing.test.ts`, which stubs `fetch` globally: `await leaseApi.paged({ status: "ACTIVE", size: 1, sort: "endDate,asc" }); expect(fetch).toHaveBeenLastCalledWith(expect.stringMatching(/\/leases\/paged\?.*sort=endDate(%2C|,)asc/), expect.anything());` — the regex accepts either encoding of the comma.)

```ts
// src/lib/dashboard/pipeline.ts
import type { LeaseDetail } from "@/lib/api/leasing";
import type { Page } from "@/lib/api/ledger";

export type StageId = "draft" | "upcoming" | "active" | "expiring" | "notice" | "settlement";
export interface PipelineStage { id: StageId; count: number; capped: boolean; oldest: { leaseId: string; label: string; date: string } | null; href: string }
export interface PipelineInput {
    draft: Page<LeaseDetail>;          // status=DRAFT, sort=startDate,asc, size=1
    activeByStart: Page<LeaseDetail>;  // status=ACTIVE, sort=startDate,desc, size=50
    activeByEnd: Page<LeaseDetail>;    // status=ACTIVE, sort=endDate,asc, size=100
    notice: Page<LeaseDetail>;         // status=NOTICE_GIVEN, sort=endDate,asc, size=1
    terminated: Page<LeaseDetail>;     // status=TERMINATED, sort=endDate,asc, size=1
    expired: Page<LeaseDetail>;        // status=EXPIRED, sort=endDate,asc, size=1
}

export const plusDays = (iso: string, n: number): string => {
    const [y, m, d] = iso.split("-").map(Number);
    const dt = new Date(Date.UTC(y, m - 1, d + n));
    return dt.toISOString().slice(0, 10);
};
const oldest = (l: LeaseDetail | undefined, date: (l: LeaseDetail) => string) =>
    l ? { leaseId: l.id, label: `${l.unitIdentifier ?? "—"} · ${l.renterName ?? "—"}`, date: date(l) } : null;

/**
 * Draft → Upcoming → Active → Expiring ≤ 60 d → Notice → Settlement due.
 * Status totals come straight from /leases/paged; Upcoming (posted, not yet
 * started) and Expiring are read from one bounded, sorted page each, and a
 * stage whose whole page qualified is marked `capped` (shown as "N+").
 */
export function buildPipeline(i: PipelineInput, today: string): PipelineStage[] {
    const horizon = plusDays(today, 60);
    const upcoming = i.activeByStart.content.filter(l => l.startDate > today);
    const upcomingCapped = upcoming.length === i.activeByStart.content.length && i.activeByStart.totalElements > i.activeByStart.content.length;
    const expiring = i.activeByEnd.content.filter(l => l.startDate <= today && l.endDate <= horizon);
    const expiringCapped = expiring.length === i.activeByEnd.content.length && i.activeByEnd.totalElements > i.activeByEnd.content.length;
    const byStartAsc = [...upcoming].sort((a, b) => a.startDate.localeCompare(b.startDate));
    const settlementFirst = [i.terminated.content[0], i.expired.content[0]].filter(Boolean).sort((a, b) => a!.endDate.localeCompare(b!.endDate))[0];
    return [
        { id: "draft", count: i.draft.totalElements, capped: false, oldest: oldest(i.draft.content[0], l => l.startDate), href: "/dashboard/leases?status=DRAFT" },
        { id: "upcoming", count: upcoming.length, capped: upcomingCapped, oldest: oldest(byStartAsc[0], l => l.startDate), href: "/dashboard/leases?status=ACTIVE" },
        { id: "active", count: i.activeByStart.totalElements - upcoming.length, capped: false, oldest: null, href: "/dashboard/leases?status=ACTIVE" },
        { id: "expiring", count: expiring.length, capped: expiringCapped, oldest: oldest(expiring[0], l => l.endDate), href: "/dashboard/leases?view=expiring" },
        { id: "notice", count: i.notice.totalElements, capped: false, oldest: oldest(i.notice.content[0], l => l.endDate), href: "/dashboard/leases?status=NOTICE_GIVEN" },
        { id: "settlement", count: i.terminated.totalElements + i.expired.totalElements, capped: false, oldest: oldest(settlementFirst, l => l.endDate), href: "/dashboard/leases?view=ended" },
    ];
}
```
(Check the fixture: activeByStart has one future row (f1) of 2 fetched, total 20 → upcoming 1, active 19 ✓; activeByEnd e1 (2026-10-10) and e2 (2026-11-20) ≤ 2026-11-24 ✓, a2 not → 2 ✓; settlement 3 + 1 = 4 ✓.)

```tsx
// src/components/dashboard/ContractPipeline.tsx
"use client";
import { useTranslations } from "next-intl";
import { Link } from "@/i18n/routing";
import type { PipelineStage, StageId } from "@/lib/dashboard/pipeline";

const KEY: Record<StageId, string> = { draft: "stageDraft", upcoming: "stageUpcoming", active: "stageActive", expiring: "stageExpiring", notice: "stageNotice", settlement: "stageSettlement" };

export default function ContractPipeline({ stages }: { stages: PipelineStage[] }) {
    const t = useTranslations("Today");
    return (
        <section aria-label={t("pipelineTitle")} data-testid="contract-pipeline" className="bg-surface border border-border rounded-[var(--radius-lg)] p-4">
            <h2 className="text-[13px] text-[var(--ink-500)] mb-3">{t("pipelineTitle")}</h2>
            <ol className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-6 gap-2">
                {stages.map(s => (
                    <li key={s.id} data-testid={`pipeline-${s.id}`} className="rounded-[var(--radius)] border border-border p-3 min-w-0">
                        <Link href={s.href} className="block">
                            <div className="text-[11px] font-semibold uppercase tracking-wider text-[var(--ink-500)]">{t(KEY[s.id])}</div>
                            <div className="font-serif text-[22px] font-semibold tabular-nums">{s.capped ? t("capped", { count: s.count }) : s.count}</div>
                        </Link>
                        {s.oldest && (
                            <Link href={`/dashboard/leases/${s.oldest.leaseId}`} className="block truncate text-[11px] text-primary hover:underline">
                                {t("oldest", { label: s.oldest.label })}
                            </Link>
                        )}
                    </li>
                ))}
            </ol>
        </section>
    );
}
```

```tsx
// src/components/dashboard/TodayList.tsx
"use client";
import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { Link } from "@/i18n/routing";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { chequeApi, penaltyApi, recognitionApi } from "@/lib/api/leasing";
import type { PipelineStage } from "@/lib/dashboard/pipeline";
import FollowUpsWidget from "./FollowUpsWidget";

export type TodayRowId = "deposit" | "overdue" | "ending" | "drafts" | "tickets" | "penalties" | "recognition" | "followUps";
export interface TodayRowDef { id: TodayRowId; href: string | null; testId: string }
const OPEN_TICKETS = new Set(["OPEN", "ASSIGNED", "IN_PROGRESS", "REOPENED"]);

export function todayRowDefs(role: UserRole | undefined): TodayRowDef[] {
    const rows: TodayRowDef[] = [];
    const add = (id: TodayRowId, href: string | null) => rows.push({ id, href, testId: `today-${id === "followUps" ? "follow-ups" : id}` });
    if (hasPermission(role, "canManageCheques")) { add("deposit", "/dashboard/collections?tab=deposit"); add("overdue", "/dashboard/collections?tab=overdue"); }
    if (hasPermission(role, "canViewLeases")) { add("ending", "/dashboard/leases?view=expiring"); add("drafts", "/dashboard/leases?status=DRAFT"); }
    if (hasPermission(role, "canResolveIssues")) add("tickets", "/dashboard/tickets");
    if (hasPermission(role, "canApprovePenalties")) add("penalties", "/dashboard/collections?tab=penalties");
    if (hasPermission(role, "canRunRecognition")) add("recognition", "/dashboard/finance/recognition");
    if (hasPermission(role, "canViewLeases")) add("followUps", null);
    return rows;
}

export default function TodayList({ role, pipeline }: { role: UserRole | undefined; pipeline: PipelineStage[] | null }) {
    const t = useTranslations("Today");
    const [counts, setCounts] = useState<Partial<Record<TodayRowId, number>>>({});
    const defs = todayRowDefs(role);

    useEffect(() => {
        const set = (id: TodayRowId, n: number) => setCounts(c => ({ ...c, [id]: n }));
        const ids = new Set(todayRowDefs(role).map(d => d.id));
        if (ids.has("deposit")) {
            chequeApi.toDeposit({ page: 0, size: 1 }).then(p => set("deposit", p.totalElements)).catch(() => {});
            chequeApi.summary().then(s => set("overdue", s.overdueCount)).catch(() => {});
        }
        if (ids.has("tickets")) fetch("/api/proxy/v1/tickets").then(r => (r.ok ? r.json() : [])).then((l: { status: string }[]) => set("tickets", l.filter(x => OPEN_TICKETS.has(x.status)).length)).catch(() => {});
        if (ids.has("penalties")) penaltyApi.list({ status: "PROPOSED", page: 0, size: 1 }).then(p => set("penalties", p.totalElements)).catch(() => {});
        if (ids.has("recognition")) recognitionApi.status().then(s => set("recognition", s.behind)).catch(() => {});
        if (ids.has("followUps")) fetch("/api/proxy/v1/renewals/follow-ups").then(r => (r.ok ? r.json() : [])).then((l: unknown[]) => set("followUps", Array.isArray(l) ? l.length : 0)).catch(() => {});
    }, [role]);

    const stage = (id: string) => pipeline?.find(s => s.id === id)?.count;
    const value = (id: TodayRowId) => id === "ending" ? stage("expiring") : id === "drafts" ? stage("draft") : counts[id];
    const shown = defs.filter(d => (value(d.id) ?? 0) > 0);

    return (
        <section aria-label={t("title")} data-testid="today-list" className="bg-surface border border-border rounded-[var(--radius-lg)] p-5">
            <h2 className="font-serif text-[20px] font-semibold text-foreground mb-3">{t("title")}</h2>
            {shown.length === 0 ? <p className="text-[13px] text-[var(--ink-500)]">{t("allClear")}</p> : (
                <ul className="divide-y divide-border">
                    {shown.map(d => d.href ? (
                        <li key={d.id}>
                            <Link href={d.href} data-testid={d.testId} className="flex items-center justify-between py-2.5 text-[13.5px] hover:text-primary">
                                <span>{t(d.id)}</span><span className="font-semibold tabular-nums">{value(d.id)}</span>
                            </Link>
                        </li>
                    ) : (
                        <li key={d.id}>
                            <details data-testid={d.testId}>
                                <summary className="flex items-center justify-between py-2.5 text-[13.5px] cursor-pointer">
                                    <span>{t(d.id)}</span><span className="font-semibold tabular-nums">{value(d.id)}</span>
                                </summary>
                                <FollowUpsWidget />
                            </details>
                        </li>
                    ))}
                </ul>
            )}
        </section>
    );
}
```

`src/app/[locale]/dashboard/page.tsx`:
1. Pipeline loading, next to the existing summary fetch, only when `hasPermission(role, "canViewLeases")`:

```ts
const [pipeline, setPipeline] = useState<PipelineStage[] | null>(null);
useEffect(() => {
    if (!hasPermission(userRole, "canViewLeases")) return;
    const today = businessTodayIso();
    Promise.all([
        leaseApi.paged({ status: "DRAFT", sort: "startDate,asc", size: 1 }),
        leaseApi.paged({ status: "ACTIVE", sort: "startDate,desc", size: 50 }),
        leaseApi.paged({ status: "ACTIVE", sort: "endDate,asc", size: 100 }),
        leaseApi.paged({ status: "NOTICE_GIVEN", sort: "endDate,asc", size: 1 }),
        leaseApi.paged({ status: "TERMINATED", sort: "endDate,asc", size: 1 }),
        leaseApi.paged({ status: "EXPIRED", sort: "endDate,asc", size: 1 }),
    ]).then(([draft, activeByStart, activeByEnd, notice, terminated, expired]) =>
        setPipeline(buildPipeline({ draft, activeByStart, activeByEnd, notice, terminated, expired }, today)))
      .catch(() => setPipeline(null));
}, [userRole]);
```
(`businessTodayIso` from `src/lib/businessDate.ts` is the repo's Dubai business date. Imports to add: `businessTodayIso`, `leaseApi`, `buildPipeline`, `type PipelineStage`, `ContractPipeline`, `TodayList`, and `const tToday = useTranslations("Today");` next to the page's `t`.)
2. Render order: header → `{pipeline && <ContractPipeline stages={pipeline} />}` → `<TodayList role={userRole} pipeline={pipeline} />` → KPI grid → `<CollectionChart data={monthly} />` (full width; delete the grid with `gridTemplateColumns: "1.6fr 1fr"` and the portfolio-snapshot card with `OccupancyDonut`) → recent activity. Delete the four widget renders (lines ~425–431) and the `OccupancyDonut` function.
3. The occupancy KPI (inside `<div id="unit-status">` from Task 8) becomes Unit Status:

```tsx
<div id="unit-status">
  <StatCard
    label={tToday("unitStatus")}
    value={summary.occupancyRate.toFixed(1)}
    unit="%"
    sub={tToday("unitStatusLine", { occupied: summary.occupiedUnits, expiring: pipeline?.find(s => s.id === "expiring")?.count ?? summary.expiringLeases, vacant: summary.vacantUnits })}
    note={[reservedUnits > 0 ? t("reservedSubline", { count: reservedUnits }) : null,
           tToday("portfolioNote", { properties: summary.totalProperties, active: summary.activeLeases })].filter(Boolean).join(" · ")}
  />
</div>
```
Every figure the removed snapshot card showed (properties, active, drafts, reserved, vacant) is still on the page: properties/active/reserved/vacant on Unit Status, drafts on the pipeline.
4. Dashboard tests: `charts-real-data.test.tsx` asserted the donut — change it to assert the single collections chart and `screen.getByTestId("contract-pipeline")` (mock `leaseApi.paged` → empty pages); `dashboard-reserved-units.test.tsx` → the reserved note now appears inside Unit Status; remove the `vi.mock` lines for the three deleted widgets from `overdue-card-link.test.tsx` and mock `@/components/dashboard/TodayList` to `() => null` instead.

- [ ] **Step 5: Run** — `npx vitest run src/lib/dashboard src/components/dashboard "src/app/[locale]/dashboard/__tests__" src/lib/api/__tests__/leasing.test.ts` → all pass.

- [ ] **Step 6: Commit** (`-- src/lib/dashboard src/components/dashboard src/lib/api/leasing.ts src/lib/api/__tests__/leasing.test.ts "src/app/[locale]/dashboard/page.tsx" "src/app/[locale]/dashboard/__tests__" messages/en.json messages/ar.json`, message `feat(web): Home — contract pipeline, needs-you-now list, unit status`).

### Task 27: List pages — Contracts pills with counts, property filter, search, Filters; one ⋯ menu per row; Properties "More"

Spec §7 + §1a. **Contracts** (`/dashboard/leases`): pills **All · Draft · Active · Expiring · Notice · Ended** with counts, then a property filter and the search box; the old all-status `<select>` (it also reaches PENDING_SIGNATURE and each ended status) moves behind **Filters**, and an active filter shows as a removable chip. Row and card actions collapse into one ⋯ menu; the table/card/board toggle, bulk post and **New Contract** stay. **Properties**: **Add property** stays primary; Add project, Import property, Import portfolio move under **More**. **Tenants** (renters) rows already carry a single View link — nothing to collapse; the test pins that.

Counts, frontend only: `leaseApi.paged({ status, size: 1 }).totalElements` for DRAFT, ACTIVE, NOTICE_GIVEN and the four ended statuses (TERMINATED, EXPIRED, RENEWED, CLOSED — summed); Expiring uses the same bounded read as the pipeline (`ACTIVE`, `sort=endDate,asc`, `size=100`, filtered to ≤ 60 days, "100+" when capped); All = `paged({ size: 1 })`. The endpoint filters one status at a time, so Ended lists one ended status at a time (default TERMINATED; the Filters select switches it) and Expiring lists that single bounded page (no pagination).

**Files:** Create `src/lib/leases/contractListView.ts`, `src/lib/leases/__tests__/contractListView.test.ts`, `src/components/ui/FiltersButton.tsx`, `src/app/[locale]/dashboard/leases/__tests__/list-pills.test.tsx`, `src/app/[locale]/dashboard/properties/__tests__/header-menu.test.tsx`, `src/app/[locale]/dashboard/renters/__tests__/row-actions.test.tsx`. Modify `src/app/[locale]/dashboard/leases/page.tsx`, `src/app/[locale]/dashboard/properties/page.tsx`, `src/lib/nav/navModel.ts` (+ its test: Leasing saved views), messages.

**Interfaces**
- `type ContractView = "all" | "draft" | "active" | "expiring" | "notice" | "ended"`
- `const ENDED: LeaseStatus[]` = `["TERMINATED", "EXPIRED", "RENEWED", "CLOSED"]`
- `function parseContractView(sp: URLSearchParams): { view: ContractView; status: LeaseStatus | ""; propertyId: string; search: string }`
- `function contractViewQuery(view: ContractView, status: LeaseStatus | ""): { status?: LeaseStatus; sort?: string; size?: number; bounded: boolean }`
- `function expiringHorizon(today: string): string`
- `FiltersButton({ label, activeCount, children }: { label: string; activeCount: number; children: React.ReactNode })`, `FilterChip({ label, onRemove, removeLabel, testId }: { label: string; onRemove: () => void; removeLabel: string; testId: string })`

- [ ] **Step 1: Messages** — `"ContractList": { "pillAll": "All", "pillDraft": "Draft", "pillActive": "Active", "pillExpiring": "Expiring", "pillNotice": "Notice", "pillEnded": "Ended", "pillsLabel": "Contract status", "savedExpiring": "Expiring in 60 days", "savedDrafts": "Drafts to post" }` / AR `{ "pillAll": "الكل", "pillDraft": "مسودة", "pillActive": "نشطة", "pillExpiring": "تنتهي قريباً", "pillNotice": "إشعار", "pillEnded": "منتهية", "pillsLabel": "حالة العقد", "savedExpiring": "تنتهي خلال 60 يوماً", "savedDrafts": "مسودات للترحيل" }`.

- [ ] **Step 2: Failing tests**

```ts
// src/lib/leases/__tests__/contractListView.test.ts
import { describe, expect, it } from "vitest";
import { contractViewQuery, parseContractView } from "../contractListView";

const p = (q: string) => parseContractView(new URLSearchParams(q));
describe("contract list view", () => {
    it.each([
        ["", "all", ""], ["status=DRAFT", "draft", "DRAFT"], ["status=ACTIVE", "active", "ACTIVE"],
        ["status=NOTICE_GIVEN", "notice", "NOTICE_GIVEN"], ["status=EXPIRED", "ended", "EXPIRED"],
        ["view=ended", "ended", "TERMINATED"], ["view=expiring", "expiring", ""],
        ["status=PENDING_SIGNATURE", "all", "PENDING_SIGNATURE"], ["status=BOGUS", "all", ""],
    ])("?%s → %s / %s", (q, view, status) => {
        expect(p(q)).toMatchObject({ view, status });
    });
    it("keeps property and search", () => {
        expect(p("propertyId=p1&search=olv")).toMatchObject({ propertyId: "p1", search: "olv" });
    });
    it("reads Expiring as one bounded page sorted by end date", () => {
        expect(contractViewQuery("expiring", "")).toEqual({ status: "ACTIVE", sort: "endDate,asc", size: 100, bounded: true });
        expect(contractViewQuery("ended", "CLOSED")).toEqual({ status: "CLOSED", bounded: false });
        expect(contractViewQuery("all", "PENDING_SIGNATURE")).toEqual({ status: "PENDING_SIGNATURE", bounded: false });
    });
});
```

`list-pills.test.tsx` — reuse the mocks of `leases/__tests__/terminate-gate.test.tsx` (same directory; it renders the list page) and mock `leaseApi.paged` with `vi.fn(async (q) => ({ content: q.size === 1 ? [] : [draftLease, activeLease], totalElements: ({ DRAFT: 2, ACTIVE: 5, NOTICE_GIVEN: 1, TERMINATED: 1, EXPIRED: 1, RENEWED: 0, CLOSED: 3 } as Record<string, number>)[q.status ?? ""] ?? 13, totalPages: 1, number: 0, size: q.size ?? 25 }))`:

```tsx
it("shows the six pills with counts", async () => {
    renderList();
    expect(await within(await screen.findByTestId("contract-pill-draft")).findByText("2")).toBeInTheDocument();
    expect(within(screen.getByTestId("contract-pill-active")).getByText("5")).toBeInTheDocument();
    expect(within(screen.getByTestId("contract-pill-ended")).getByText("5")).toBeInTheDocument();
    expect(within(screen.getByTestId("contract-pill-all")).getByText("13")).toBeInTheDocument();
});
it("collapses row actions into one menu, keeping every action's test id", async () => {
    renderList();
    const row = await screen.findByTestId(`lease-row-${activeLease.id}`);
    expect(within(row).getByTestId(`lease-row-menu-${activeLease.id}`)).toBeInTheDocument();
    expect(within(row).getByTestId(`lease-list-terminate-${activeLease.id}`)).not.toBeVisible();
});
it("moves the all-status select behind Filters and shows a chip when used", async () => {
    renderList();
    expect(screen.getByTestId("lease-status-filter")).not.toBeVisible();
    fireEvent.change(screen.getByTestId("lease-status-filter"), { target: { value: "PENDING_SIGNATURE" } });
    expect(await screen.findByTestId("lease-filter-chip-status")).toBeInTheDocument();
});
```
(`terminate-gate.test.tsx`'s existing `fireEvent.click(getByTestId("lease-list-terminate-…"))` keeps passing: the item is mounted, hidden.)

`header-menu.test.tsx` (properties; copy mocks from the nearest properties list test or build them: `next/navigation`, `@/i18n/routing` `Link`, `next-auth/react` role TENANT_ADMIN, `fetch` → `[]`):

```tsx
it("keeps Add property primary and moves the other create actions under More", async () => {
    render(<NextIntlClientProvider locale="en" messages={en}><PropertiesPage /></NextIntlClientProvider>);
    expect(await screen.findByRole("button", { name: en.MasterData.addProperty })).toBeVisible();
    const menu = screen.getByTestId("properties-more-menu");
    for (const label of [en.MasterData.addProject, en.MasterData.importProperty, en.MasterData.importPortfolio]) {
        expect(within(menu).getByText(label)).toBeInTheDocument();
    }
});
```

`row-actions.test.tsx` (renters): render the list with one renter, assert `within(row).getAllByRole("link").length === 1` (the name) plus at most one action link — pins "nothing to collapse".

- [ ] **Step 3: Run — fails.**

- [ ] **Step 4: Implement**

```ts
// src/lib/leases/contractListView.ts
import type { LeaseStatus } from "../api/leasing";
import { plusDays } from "../dashboard/pipeline";

export type ContractView = "all" | "draft" | "active" | "expiring" | "notice" | "ended";
export const ENDED: LeaseStatus[] = ["TERMINATED", "EXPIRED", "RENEWED", "CLOSED"];
const ALL_STATUSES: LeaseStatus[] = ["DRAFT", "PENDING_SIGNATURE", "ACTIVE", "NOTICE_GIVEN", ...ENDED];

export function parseContractView(sp: URLSearchParams) {
    const raw = (sp.get("status") ?? "").toUpperCase();
    const status = (ALL_STATUSES as string[]).includes(raw) ? (raw as LeaseStatus) : "";
    const v = sp.get("view");
    const view: ContractView =
        v === "expiring" ? "expiring"
        : v === "ended" || ENDED.includes(status as LeaseStatus) ? "ended"
        : status === "DRAFT" ? "draft" : status === "ACTIVE" ? "active" : status === "NOTICE_GIVEN" ? "notice" : "all";
    return {
        view,
        status: (view === "ended" && !status ? "TERMINATED" : view === "expiring" ? "" : status) as LeaseStatus | "",
        propertyId: sp.get("propertyId") ?? "",
        search: sp.get("search") ?? "",
    };
}

export function expiringHorizon(today: string): string {
    return plusDays(today, 60);
}

export function contractViewQuery(view: ContractView, status: LeaseStatus | ""): { status?: LeaseStatus; sort?: string; size?: number; bounded: boolean } {
    if (view === "expiring") return { status: "ACTIVE", sort: "endDate,asc", size: 100, bounded: true };
    return { ...(status ? { status } : {}), bounded: false };
}
```

```tsx
// src/components/ui/FiltersButton.tsx
"use client";
import { useEffect, useRef, useState } from "react";
import { SlidersHorizontal, X } from "lucide-react";

export function FiltersButton({ label, activeCount, children }: { label: string; activeCount: number; children: React.ReactNode }) {
    const [open, setOpen] = useState(false);
    const root = useRef<HTMLDivElement>(null);
    useEffect(() => {
        if (!open) return;
        const onDown = (e: MouseEvent) => { if (root.current && !root.current.contains(e.target as Node)) setOpen(false); };
        const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") setOpen(false); };
        document.addEventListener("mousedown", onDown); window.addEventListener("keydown", onKey);
        return () => { document.removeEventListener("mousedown", onDown); window.removeEventListener("keydown", onKey); };
    }, [open]);
    return (
        <div ref={root} className="relative">
            <button type="button" aria-expanded={open} data-testid="filters-button" onClick={() => setOpen(o => !o)}
                className="flex items-center gap-1.5 bg-surface border border-border rounded-lg px-3 py-2 text-xs cursor-pointer">
                <SlidersHorizontal size={13} />{label}{activeCount > 0 && <span className="rounded-full bg-[var(--ink-900)] px-1.5 text-[10px] text-white">{activeCount}</span>}
            </button>
            <div hidden={!open} data-testid="filters-panel" className="absolute end-0 top-full z-40 mt-1 w-64 max-w-[calc(100vw-2rem)] rounded-lg border border-border bg-surface p-3 shadow-lg space-y-3">
                {children}
            </div>
        </div>
    );
}

export function FilterChip({ label, onRemove, removeLabel, testId }: { label: string; onRemove: () => void; removeLabel: string; testId: string }) {
    return (
        <span data-testid={testId} className="inline-flex items-center gap-1 rounded-full border border-border bg-[var(--sand-100)] ps-2.5 pe-1 py-0.5 text-[11.5px]">
            {label}
            <button type="button" aria-label={removeLabel} onClick={onRemove} className="rounded-full p-0.5 hover:bg-border cursor-pointer"><X size={11} /></button>
        </span>
    );
}
```

`leases/page.tsx` (edits, no logic removed):
1. State from the URL: `const initial = parseContractView(new URLSearchParams(typeof window === "undefined" ? "" : window.location.search));` → `const [view, setView] = useState<ContractView>(initial.view); const [statusFilter, setStatusFilter] = useState<LeaseStatus | "">(initial.status); const [propertyFilter, setPropertyFilter] = useState(initial.propertyId);` and `searchQuery` initialised from `initial.search`. Selecting a pill sets `view` and the matching status (`draft→DRAFT`, `active→ACTIVE`, `notice→NOTICE_GIVEN`, `ended→TERMINATED`, `all`/`expiring→""`) and writes `?status=`/`?view=`/`?propertyId=` with `window.history.replaceState` (the pattern the `?new=1` effect already uses).
2. `fetchLeases` becomes:

```ts
const fetchLeases = async () => {
    setLoading(true);
    try {
        const q = contractViewQuery(view, statusFilter);
        const data = await leaseApi.paged({
            search: debouncedSearchQuery || undefined,
            propertyId: propertyFilter || undefined,
            status: q.status,
            sort: q.sort,
            page: q.bounded ? 0 : Math.max(currentPage - 1, 0),
            size: q.size ?? itemsPerPage,
        });
        const rows = data.content ?? [];
        if (view === "expiring") {
            const today = businessTodayIso();
            const horizon = expiringHorizon(today);
            const within60 = rows.filter(l => l.startDate <= today && l.endDate <= horizon);
            setLeases(within60);
            setTotalItems(within60.length);
        } else {
            setLeases(rows);
            setTotalItems(data.totalElements ?? 0);
        }
    } catch (err) {
        console.error(err);
    } finally {
        setLoading(false);
    }
};
```
with `export function expiringHorizon(today: string): string` added to `contractListView.ts` (today + 60 days, the same UTC date arithmetic as `plusDays` in `src/lib/dashboard/pipeline.ts` — export `plusDays` from there and use `plusDays(today, 60)`), plus a test row `expect(expiringHorizon("2026-09-25")).toBe("2026-11-24")`. The fetch effect's deps gain `view` and `propertyFilter`; the `<Pagination>` renders only when `!contractViewQuery(view, statusFilter).bounded`.
3. Pill counts: a `useEffect` on `[propertyFilter]` that runs the `paged({ status, propertyId, size: 1 })` calls listed above plus the bounded expiring read, into `const [pillCounts, setPillCounts] = useState<Partial<Record<ContractView, number | string>>>({})` (expiring shows `"100+"` when capped).
4. Header row: `<nav aria-label={tc("pillsLabel")}>` of six pill buttons (`data-testid={\`contract-pill-${v}\`}`, `aria-pressed`, same classes as `CollectionPills`), then a property `<select>` fed by `useNameLookup("properties").options` (`data-testid="lease-property-filter"`), the existing search input, `<FiltersButton label={tList("filters")} activeCount={statusFilter && !["DRAFT","ACTIVE","NOTICE_GIVEN"].includes(statusFilter) && view !== "ended" ? 1 : 0}>` wrapping the existing `lease-status-filter` `<select>` unchanged (its `onChange` also sets `view` via `parseContractView`), and below the row `{statusFilter && view === "all" && <FilterChip testId="lease-filter-chip-status" label={tl(\`leaseStatus.${statusFilter}\`)} removeLabel={tList("removeFilter", { name: tl(\`leaseStatus.${statusFilter}\`) })} onRemove={() => setStatusFilter("")} />}`. The table/card/board toggle, bulk post and New Contract buttons stay where they are. Physical classes on the touched search input (`left-3`, `pl-9 pr-4`) become `start-3`, `ps-9 pe-4`.
5. Rows (table ~line 893) and cards (~line 605): replace each action-button cluster with one `ActionsMenu variant="icon" label={tList("rowActions", { name: lease.unitIdentifier ?? lease.id })} testId={\`lease-row-menu-panel-${lease.id}\`} triggerTestId={\`lease-row-menu-${lease.id}\`}` (cards: `lease-card-menu-…`) whose `items` are built from the same conditions and handlers, keeping every existing test id on its item (`lease-card-edit-{id}`, `lease-list-terminate-{id}`, `lease-card-terminate-{id}`) and adding `lease-row-{action}-{id}` for the ones that had none (edit, delete, generate, post, pdf, docs). `View` stays as the row click and the menu's first item.

`properties/page.tsx` (lines ~501–545): keep the "Add property" `<button>` (primary styling, `data-tour="add-property-btn"`); replace the other three buttons with `<ActionsMenu label={tList("more")} testId="properties-more-menu" triggerTestId="properties-more" items={[{ id: "project", label: t("addProject"), testId: "properties-add-project", onSelect: () => { setProjectFormError(null); setShowProjectForm(true); } }, { id: "import", label: t("importProperty"), testId: "properties-import", onSelect: () => setShowImportForm(true) }, { id: "portfolio", label: t("importPortfolio"), testId: "properties-import-portfolio", onSelect: () => setShowPortfolioImport(true) }]} />` inside the same `canCreate &&` guard.

`navModel.ts` Leasing section: `savedViews: can("canViewLeases") ? [pi("saved-expiring", "/dashboard/leases?view=expiring", { ns: "ContractList", key: "savedExpiring" }, "saved-expiring"), pi("saved-drafts", "/dashboard/leases?status=DRAFT", { ns: "ContractList", key: "savedDrafts" }, "saved-drafts")] : []`; `navModel.test.ts` gains `expect(buildNav(ctx("TENANT_ADMIN")).find(s => s.id === "leasing")!.savedViews.map(v => v.id)).toEqual(["saved-expiring", "saved-drafts"])`. RBAC parity stays green (saved views are filters on `/dashboard/leases`, which every such role already reached).

- [ ] **Step 5: Run** — `npx vitest run src/lib/leases "src/app/[locale]/dashboard/leases" "src/app/[locale]/dashboard/properties" "src/app/[locale]/dashboard/renters" src/lib/nav src/components/ui` → all pass (existing `bulk-post`, `card-click`, `terminate-gate`, `leases-list-localization` included).

- [ ] **Step 6: Commit** (`-- src/lib/leases/contractListView.ts src/lib/leases/__tests__/contractListView.test.ts src/components/ui/FiltersButton.tsx "src/app/[locale]/dashboard/leases/page.tsx" "src/app/[locale]/dashboard/leases/__tests__" "src/app/[locale]/dashboard/properties/page.tsx" "src/app/[locale]/dashboard/properties/__tests__" "src/app/[locale]/dashboard/renters/__tests__" src/lib/nav messages/en.json messages/ar.json`, message `feat(web): contract list status pills with counts, filters behind a button, one row menu; properties More menu`).

### Task 28: Catalog v3 (contract page, lists, Home), help/tours/walkthrough, PR 3 gates

**Files:** Modify `src/lib/ui/actionCatalog.ts`, `src/lib/ui/__tests__/action-coverage.test.tsx`, `src/content/help/{leases--lease-lifecycle,leases--creating-a-lease,getting-started--welcome}.md` + `src/lib/helpArticles.ts`, `src/components/tour/tours/property-workflow.ts` (if it targets a removed lease tab or row button), `e2e-prod/tests/*.spec.ts` (lease tab ids).

- [ ] **Step 1: Catalog rows** — extend `ActionLocation` with `"lease.primary" | "lease.menu" | "lease.drawer.assignment" | "lease.drawer.writeOff" | "lease.section.cheques" | "lease.section.penalties" | "lease.section.journals" | "lease.section.recognition" | "lease.section.contract" | "lease.section.attachments" | "lease.section.addenda" | "lease.section.interactions" | "lease.section.maintenance" | "leases.row" | "leases.header" | "properties.header" | "properties.more" | "home"` and add one row per inventoried action:

```ts
    // Contract header (inventory §3: 15 actions) — primary or menu depends on status; the renderer checks both.
    ...(["post", "amend", "renew", "extend", "add-charge", "transfer", "reduce", "ledger", "download-contract", "raise-penalty",
        "give-notice", "terminate", "settle", "delete"] as const).map(a => ({ id: `lease.${a}`, was: "contract header", location: "lease.menu" as const, probe: `lease-${a}` })),
    { id: "lease.needsAccountant", was: "contract header notice", location: "lease.primary", probe: "lease-needs-accountant" },
    { id: "lease.assignment", was: "Overview › Assignment card", location: "lease.drawer.assignment", probe: "assignment-card" },
    { id: "lease.badDebt", was: "Overview › Bad debt card", location: "lease.drawer.writeOff", probe: "bad-debt-card" },
    { id: "lease.chequeGrid", was: "Overview › cheque grid (deposit, receive, details, cancel, clear, bounce, replace, receipt; save; bulk upload)", location: "lease.section.cheques", probe: "cheque-grid" },
    { id: "lease.penaltiesTab", was: "Penalties tab", location: "lease.section.penalties", probe: "probe-penalties" },
    { id: "lease.journalsTab", was: "Journals tab", location: "lease.section.journals", probe: "probe-journals" },
    { id: "lease.recognitionTab", was: "Recognition tab", location: "lease.section.recognition", probe: "probe-recognition" },
    { id: "lease.contractTab", was: "Contract tab (generate preview)", location: "lease.section.contract", probe: "lease-section-contract" },
    { id: "lease.documentsTab", was: "Documents tab (attach, download, delete)", location: "lease.section.attachments", probe: "lease-section-attachments" },
    { id: "lease.addenda", was: "Overview › Addenda / Ejari panel", location: "lease.section.addenda", probe: "probe-addenda" },
    { id: "lease.interactions", was: "Interactions tab", location: "lease.section.interactions", probe: "probe-interactions" },
    { id: "lease.maintenance", was: "Maintenance tab", location: "lease.section.maintenance", probe: "lease-section-maintenance" },
    // Contract list rows (inventory: edit, delete, generate, post, pdf, terminate, docs, view)
    ...(["edit", "delete", "generate", "post", "pdf", "docs"] as const).map(a => ({ id: `leases.row.${a}`, was: "contract list row", location: "leases.row" as const, probe: `lease-row-${a}-ROW` })),
    { id: "leases.row.terminate", was: "contract list row", location: "leases.row", probe: "lease-list-terminate-ROW" },
    { id: "leases.bulkPost", was: "contract list header", location: "leases.header", probe: "bulk-post" },
    { id: "leases.statusFilter", was: "contract list status select", location: "leases.header", probe: "lease-status-filter" },
    { id: "properties.addProperty", was: "properties header", location: "properties.header", probe: "properties-add-property" },
    { id: "properties.addProject", was: "properties header", location: "properties.more", probe: "properties-add-project" },
    { id: "properties.import", was: "properties header", location: "properties.more", probe: "properties-import" },
    { id: "properties.importPortfolio", was: "properties header", location: "properties.more", probe: "properties-import-portfolio" },
    { id: "home.newContract", was: "Home New lease", location: "home", probe: "home-new-contract" },
    { id: "home.overdue", was: "Home overdue widget + KPI link", location: "home", probe: "kpi-overdue" },
    { id: "home.recentActivity", was: "Home recent activity", location: "home", probe: "recent-activity" },
```
Give the Home "New Contract" link `data-testid="home-new-contract"`, the Overdue KPI `<Link>` `data-testid="kpi-overdue"`, the recent-activity card `data-testid="recent-activity"`, the properties "Add property" button `data-testid="properties-add-property"`, and the `LeaseAddendaPanel`/`LeaseInteractionsPanel` mocks in the coverage test `probe-addenda`/`probe-interactions`. The `-ROW` suffix is replaced with the fixture row's id by the renderer.

Renderer additions in `action-coverage.test.tsx` (mocks copied from `header-actions.test.tsx` / `list-pills.test.tsx`):
- `lease.*`: render the contract page three times — `TENANT_ADMIN` on a DRAFT with `hasContract: true`, on an ACTIVE posted lease with `hasContract: true`, and on a TERMINATED lease — concatenating the three containers; for `lease.primary` and `lease.menu` a probe counts if it is inside `[data-testid="lease-actions"]` (primary or menu); for `lease.drawer.*` click `lease-assignment` / `lease-write-off` first; for `lease.section.*` render with `?tab=<section>` so the section is open.
- `leases.*`: render the list with one DRAFT, one PENDING_SIGNATURE and one ACTIVE lease.
- `properties.*`, `home`: render the page as TENANT_ADMIN.

- [ ] **Step 2: Help, tours, e2e-prod** — help prose: contract tabs "Overview / Journals / Recognition / …" → "**General**, **Cheques** (with Journal Vouchers, Revenue recognition and Vat sections), **Attachments**, **Activities**"; actions: "The buttons at the top of a contract show the next step for its status (Edit and Post for a draft; Record payment and Renew for an active contract; Settlement once it has ended). Everything else — Extend Contract, Amend lines, Add charge, Transfer, Assignment, Reduce, Raise penalty, Give notice, Terminate, Write off, Download contract, Ledger — is under **More actions**." Home article: "Home shows the **Contract pipeline**, **Needs you now**, and **Unit Status**." `grep -rln "lease-tab-\(journals\|recognition\|vat\|penalties\|contract\|maintenance\|documents\|interactions\)" e2e-prod/tests` → apply the Task 25 Step 3 perl substitution to each hit.

- [ ] **Step 3: Gates** — Task 16 Step 1 commands (vitest, tsc, EN/AR parity + terminology, eslint on `src/lib/leases src/lib/dashboard src/components/ui/ActionsMenu.tsx src/components/ui/SideDrawer.tsx src/components/ui/FiltersButton.tsx src/components/dashboard src/components/leases/LeaseSection.tsx "src/app/[locale]/dashboard/leases" "src/app/[locale]/dashboard/page.tsx"`, `npm run build`), the Task 15 sweep → `15 passed` (the AR 390 px runs are where a menu opening off-screen or a pill row forcing sideways scroll shows up), `npx playwright test e2e/leases e2e/finance` against the local stack (the specs Task 25 edited) → pass, no backend diff.

- [ ] **Step 4: Commit, push, PR** — commit Steps 1–2 (`-- src/lib/ui src/content/help src/lib/helpArticles.ts src/components/tour/tours e2e-prod/tests "src/app/[locale]/dashboard/page.tsx" "src/app/[locale]/dashboard/properties/page.tsx"`, message `test(web): contract, list and home actions in the coverage catalog; help follows`), push `feat/admin-ui-pr3-contract-home-lists`, `gh pr create --title "feat(web): admin UI simplification 3/3 — contract page, Home pipeline, list pages"` with a body covering: header ≤ 3 primary actions by status + More actions (legacy-header parity matrix); tabs General · Cheques · Attachments · Activities with sections and legacy `?tab=` mapping; Assignment / Write off drawers; Home pipeline + Needs you now + Unit Status; Contracts pills with counts, Filters + chips, one row menu; Properties More; "Frontend only — no backend change."; ending with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.

- [ ] **Step 5: Review loop, merge, deploy, production check** — the full ops-manager day on the prod test org in EN and AR (create contract → post → deposit → overdue → renew → settle) using only primary buttons and More actions; the accountant path through Accounting; open three old contract deep links (`?tab=journals`, `?tab=contract`, `?tab=interactions`) and confirm the right tab and open section.
