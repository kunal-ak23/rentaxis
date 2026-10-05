import { describe, expect, it, vi } from "vitest";

/** PR #398 R1-P3-3: the pickable list is per user and organisation; a change of either refetches. */
const pickable = vi.fn();
vi.mock("@/lib/api/ledger", async orig => {
    const m = await orig<typeof import("@/lib/api/ledger")>();
    return { ...m, ledgerApi: { ...m.ledgerApi, accounts: { ...m.ledgerApi.accounts, pickable: () => pickable() } } };
});

import { loadAccounts, setAccountsScope } from "../AccountPicker";
import { ACTIVE_ORG_COOKIE } from "@/lib/session/orgSync";

describe("loadAccounts scope", () => {
    it("shares one list within a scope and refetches for another user or organisation", async () => {
        pickable.mockResolvedValueOnce([{ id: "pm-list" }]).mockResolvedValueOnce([{ id: "admin-list" }])
            .mockResolvedValueOnce([{ id: "other-org" }]);
        setAccountsScope("u1|PROPERTY_MANAGER|t1");
        expect(await loadAccounts()).toEqual([{ id: "pm-list" }]);
        expect(await loadAccounts()).toEqual([{ id: "pm-list" }]);
        expect(pickable).toHaveBeenCalledTimes(1);

        setAccountsScope("u2|TENANT_ADMIN|t1");
        expect(await loadAccounts()).toEqual([{ id: "admin-list" }]);

        // A super admin switching organisation changes the active-org cookie, not the session.
        document.cookie = `${ACTIVE_ORG_COOKIE}=t9; path=/`;
        expect(await loadAccounts()).toEqual([{ id: "other-org" }]);
        expect(pickable).toHaveBeenCalledTimes(3);
    });
});
