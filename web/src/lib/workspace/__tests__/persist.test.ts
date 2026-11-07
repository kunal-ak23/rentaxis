import { afterEach, describe, expect, it } from "vitest";
import { clearUser, loadSession, loadSnapshot, save, sessionKey, snapshotKey } from "../persist";
import type { Workspace } from "../store";

const ws: Workspace = {
    tabs: [{ id: "a", href: "/dashboard/leases", kind: "screen", title: null, openedAt: 1, usedAt: 1 }],
    activeId: "a",
    closed: [{ id: "b", href: "/dashboard/leases/L1", kind: "record", title: "C-1", openedAt: 1, usedAt: 1 }],
};

/** Workspace storage (spec §7, §8): per user and organisation; sign-out forgets only that user. */
describe("workspace persistence", () => {
    afterEach(() => { sessionStorage.clear(); localStorage.clear(); });

    it("keeps the live workspace in sessionStorage and a closed-tab-free snapshot in localStorage", () => {
        save("u1", "o1", ws);
        expect(loadSession("u1", "o1")).toEqual(ws);
        expect(loadSnapshot("u1", "o1")).toEqual({ tabs: ws.tabs, activeId: "a", closed: [] });
        expect(loadSession("u1", "o2")).toBeNull();
        expect(loadSession("u2", "o1")).toBeNull();
    });

    it("forgets one user's workspaces in every organisation on sign-out, and nobody else's", () => {
        save("u1", "o1", ws);
        save("u1", "o2", ws);
        save("u2", "o1", ws);
        clearUser("u1");
        expect(sessionStorage.getItem(sessionKey("u1", "o1"))).toBeNull();
        expect(localStorage.getItem(snapshotKey("u1", "o2"))).toBeNull();
        expect(loadSession("u2", "o1")).toEqual(ws);
    });

    it("reads corrupt storage as nothing", () => {
        sessionStorage.setItem(sessionKey("u1", "o1"), "{not json");
        expect(loadSession("u1", "o1")).toBeNull();
    });
});
