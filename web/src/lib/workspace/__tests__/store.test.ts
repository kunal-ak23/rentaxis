import { describe, expect, it } from "vitest";
import {
    closeOthers, closeTab, EMPTY, identityOf, kindOf, MAX_TABS, navigateActive, neighbour, onFreshLoad,
    onUrlChange, openTab, reopenClosed, routeOf, setTitle, validate, type Workspace,
} from "../store";

let seq = 0;
const id = () => `t${++seq}`;
const open = (ws: Workspace, href: string, how: Parameters<typeof openTab>[2] = "record") => openTab(ws, href, how, id(), ++seq).ws;
const hrefs = (ws: Workspace) => ws.tabs.map(t => t.href);
const activeHref = (ws: Workspace) => ws.tabs.find(t => t.id === ws.activeId)?.href;

/** Workspace tabs (spec 2026-10-30 §3, §4, §7): the opening rules, the URL rules and the load-time checks. */
describe("workspace store", () => {
    it("maps concrete paths to registry routes, a literal beating a dynamic segment", () => {
        expect(routeOf("/dashboard/leases/abc")).toBe("/dashboard/leases/[id]");
        expect(routeOf("/dashboard/leases/renewals")).toBe("/dashboard/leases/renewals");
        expect(routeOf("/dashboard/nowhere/at/all")).toBeNull();
    });

    it("takes a tab's identity from the path plus the route's identity parameters only", () => {
        expect(identityOf("/dashboard/finance/vouchers/payment?id=V1&step=2")).toBe("/dashboard/finance/vouchers/payment?id=V1");
        expect(identityOf("/dashboard/finance/tenant-ledger?renterId=R1&view=open")).toBe("/dashboard/finance/tenant-ledger?renterId=R1");
        expect(identityOf("/dashboard/finance/general-ledger?accountIds=A")).toBe("/dashboard/finance/general-ledger");
        expect(kindOf("/dashboard/leases/abc")).toBe("record");
        expect(kindOf("/dashboard/leases?view=expiring")).toBe("screen");
        expect(kindOf("/dashboard/finance/tenant-ledger?renterId=R1")).toBe("record");
    });

    it("focuses the open tab for a menu item and keeps that tab's own filters", () => {
        let ws = open(EMPTY, "/dashboard/finance/general-ledger?accountIds=A", "menu");
        ws = open(ws, "/dashboard/leases", "menu");
        const r = openTab(ws, "/dashboard/finance/general-ledger", "menu", id(), ++seq);
        expect(r.outcome).toBe("focused");
        expect(r.tab?.href).toBe("/dashboard/finance/general-ledger?accountIds=A");
        expect(r.ws.tabs).toHaveLength(2);
    });

    it("opens a drill-down with a query in a new tab unless the exact href is open — a set-up ledger is never overwritten", () => {
        let ws = open(EMPTY, "/dashboard/finance/general-ledger?accountIds=A", "menu");
        ws = open(ws, "/dashboard/finance/general-ledger?accountIds=B", "query");
        expect(hrefs(ws)).toEqual(["/dashboard/finance/general-ledger?accountIds=A", "/dashboard/finance/general-ledger?accountIds=B"]);
        const again = openTab(ws, "/dashboard/finance/general-ledger?accountIds=A", "query", id(), ++seq);
        expect(again.outcome).toBe("focused");
    });

    it("opens a record next to the current tab and focuses it the second time", () => {
        let ws = open(EMPTY, "/dashboard/leases", "menu");
        ws = open(ws, "/dashboard/finance/journals", "menu");
        ws = { ...ws, activeId: ws.tabs[0].id };
        ws = open(ws, "/dashboard/leases/L1");
        expect(hrefs(ws)).toEqual(["/dashboard/leases", "/dashboard/leases/L1", "/dashboard/finance/journals"]);
        const r = openTab(ws, "/dashboard/leases/L1?tab=cheques", "record", id(), ++seq);
        expect(r.outcome).toBe("focused");
    });

    it("refuses a tab beyond the cap", () => {
        let ws = EMPTY;
        for (let i = 0; i < MAX_TABS; i++) ws = open(ws, `/dashboard/leases/L${i}`);
        const r = openTab(ws, "/dashboard/leases/extra", "record", id(), ++seq);
        expect(r.outcome).toBe("full");
        expect(r.ws).toBe(ws);
        expect(openTab(ws, "/dashboard/leases/L3", "record", id(), ++seq).outcome).toBe("focused");
    });

    it("follows the URL rules: an open tab activates, a closed one reopens, else the active tab navigates", () => {
        let ws = open(EMPTY, "/dashboard/leases", "menu");
        ws = open(ws, "/dashboard/leases/L1");
        ws = onUrlChange(ws, "/dashboard/leases", id(), ++seq);
        expect(activeHref(ws)).toBe("/dashboard/leases");
        ws = onUrlChange(ws, "/dashboard/leases?status=ACTIVE", id(), ++seq);
        expect(hrefs(ws)).toEqual(["/dashboard/leases?status=ACTIVE", "/dashboard/leases/L1"]);
        const l1 = ws.tabs[1].id;
        ws = closeTab({ ...ws, activeId: l1 }, l1, ++seq);
        ws = onUrlChange(ws, "/dashboard/leases/L1", id(), ++seq);
        expect(hrefs(ws)).toContain("/dashboard/leases/L1");
        expect(activeHref(ws)).toBe("/dashboard/leases/L1");
    });

    it("clears a record title when the tab moves to another record, keeps it within the same record", () => {
        let ws = open(EMPTY, "/dashboard/leases/L1");
        ws = setTitle(ws, ws.activeId!, "C-1");
        ws = navigateActive(ws, "/dashboard/leases/L1?tab=cheques", id(), ++seq);
        expect(ws.tabs[0].title).toBe("C-1");
        ws = navigateActive(ws, "/dashboard/leases/L2", id(), ++seq);
        expect(ws.tabs[0].title).toBeNull();
    });

    it("on a fresh load at the bare home page goes back to the stored active tab (org switch, sign-in)", () => {
        let ws = open(EMPTY, "/dashboard/leases", "menu");
        ws = open(ws, "/dashboard/leases/L1");
        const r = onFreshLoad(ws, "/dashboard", id(), ++seq);
        expect(r.replaceWith).toBe("/dashboard/leases/L1");
        const pasted = onFreshLoad(ws, "/dashboard/finance/journals", id(), ++seq);
        expect(pasted.replaceWith).toBeNull();
        expect(activeHref(pasted.ws)).toBe("/dashboard/finance/journals");
        expect(onFreshLoad(EMPTY, "/dashboard", id(), ++seq).ws.tabs).toHaveLength(1);
    });

    it("closes towards the end, remembers closed tabs and reopens them", () => {
        let ws = open(EMPTY, "/dashboard/leases", "menu");
        ws = open(ws, "/dashboard/leases/L1");
        ws = open(ws, "/dashboard/leases/L2");
        ws = { ...ws, activeId: ws.tabs[1].id };
        ws = closeTab(ws, ws.tabs[1].id, ++seq);
        expect(activeHref(ws)).toBe("/dashboard/leases/L2");
        ws = reopenClosed(ws, ++seq);
        expect(hrefs(ws)).toContain("/dashboard/leases/L1");
        expect(activeHref(ws)).toBe("/dashboard/leases/L1");
        const only = closeOthers(ws, ws.activeId!);
        expect(hrefs(only)).toEqual(["/dashboard/leases/L1"]);
        expect(only.closed).toHaveLength(2);
    });

    it("steps to the next and previous tab, wrapping", () => {
        let ws = open(EMPTY, "/dashboard/leases/A");
        ws = open(ws, "/dashboard/leases/B");
        ws = open(ws, "/dashboard/leases/C");
        // C, opened last, is active.
        expect(neighbour(ws, 1)).toBe(ws.tabs[0].id);
        expect(neighbour(ws, -1)).toBe(ws.tabs[1].id);
        expect(neighbour(EMPTY, 1)).toBeNull();
    });

    it("validates stored tabs: rewrites moved URLs, drops unknown routes, other roles' routes and junk", () => {
        const raw = {
            activeId: "x",
            tabs: [
                { id: "a", href: "/dashboard/settings/fines", title: { ns: "Navigation", key: "old" } },
                { id: "b", href: "/dashboard/nowhere" },
                { id: "c", href: "/dashboard/finance/journals" },
                { id: "d", href: "/superadmin/tenants" },
                { id: "c", href: "/dashboard/leases" },
                "junk",
            ],
            closed: "not a list",
        };
        const admin = validate(raw, "TENANT_ADMIN");
        expect(admin.tabs.map(t => t.href)).toEqual(["/dashboard/settings?section=rent", "/dashboard/finance/journals"]);
        // A moved URL's stored label no longer fits: re-derived from the new route.
        expect(admin.tabs[0].title).toBeNull();
        expect(admin.activeId).toBe("c");
        expect(admin.closed).toEqual([]);
        expect(validate(raw, "RENTER").tabs.map(t => t.href)).not.toContain("/dashboard/finance/journals");
        expect(validate(null, "ACCOUNTANT")).toEqual(EMPTY);
        expect(validate(raw, undefined).tabs).toEqual([]);
    });
});
