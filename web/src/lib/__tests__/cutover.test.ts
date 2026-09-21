import { beforeEach, describe, expect, it, vi } from "vitest";
import { cutoverApi } from "@/lib/api/cutover";
import {
    SNAPSHOT_ACCEPT, SNAPSHOT_MAX_BYTES, canDownloadImportTemplate, canEditOpeningBalanceRow,
    canPostOpeningBalances, canReplaceOpeningBalances, gridDeclaresComputed, isBatchFinal,
    isReconciled, canReverseBatch, snapshotRefusal,
} from "@/lib/cutoverRules";
import type { OpeningBalanceGrid, OpeningBalanceRow } from "@/lib/api/cutover";
import { hasPermission } from "@/lib/rbac";

/**
 * The import-batches half of the cut-over client, typed from
 * `api/ImportBatchController.java` — three endpoints and no more. The brief's
 * `batches.post` / `BulkPostResult` do not exist in the Java and are not
 * invented here.
 */

describe("cutoverApi.batches", () => {
    beforeEach(() => {
        vi.stubGlobal(
            "fetch",
            vi.fn(
                async () =>
                    new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } }),
            ),
        );
    });

function row(over: Partial<OpeningBalanceRow> = {}): OpeningBalanceRow {
    return {
        accountId: "a1", code: "110100", name: "Cash", accountType: "ASSET", propertyId: null,
        derived: false, derivedRole: null, enteredDebit: 0, enteredCredit: 0,
        ...over,
    };
}

function grid(over: Partial<OpeningBalanceGrid> = {}): OpeningBalanceGrid {
    return {
        asOf: "2026-08-31", posted: false, journalId: null, journalNumber: null,
        rows: [row()], totalDebit: 0, totalCredit: 0, difference: 0, problems: [],
        ...over,
    };
}

describe("opening-balance rules", () => {
    /**
     * OpeningBalanceService.setRow:256-260 — an account mapped to a DERIVED_ROLES
     * role "is derived from the contract import, so it cannot be entered by hand".
     * The API 400s it, so the cell is read-only rather than editable-then-refused.
     */
    /** A confirmed lookup is what unlocks the ordinary rows. */
    const found = (accountId: string | null) => ({ kind: "lookup" as const, accountId });

    it("refuses to edit a derived row and allows a manual one", () => {
        expect(canEditOpeningBalanceRow(row({ derived: false }), found("diff-1"))).toBe(true);
        expect(
            canEditOpeningBalanceRow(row({ derived: true, derivedRole: "RENT_RECEIVABLE" }), found("diff-1")),
        ).toBe(false);
    });

    /**
     * `OpeningBalanceService.grid` filters `a.isGroup()` out of the rows, so a
     * group account never reaches this predicate — the refusal is upstream, not
     * here. Named for what it actually asserts.
     */
    it("does not re-check group accounts, which the server filters out upstream", () => {
        expect(canEditOpeningBalanceRow(row({ accountType: "GROUP" }), found("diff-1"))).toBe(true);
    });

    /**
     * postFresh:365 skips the OPENING_BALANCE_DIFFERENCE account —
     * "folded into the balancing line below" — so a figure typed there is
     * silently discarded at post time. An editable cell whose value is thrown
     * away is worse than one that refuses, so it is read-only.
     */
    it("refuses to edit the opening-balance difference account", () => {
        expect(canEditOpeningBalanceRow(row({ accountId: "diff-1" }), found("diff-1"))).toBe(false);
        expect(canEditOpeningBalanceRow(row({ accountId: "a1" }), found("diff-1"))).toBe(true);
    });

    /**
     * **Fails closed.** Until the difference account is positively identified,
     * nothing is editable. The guard exists precisely because `setRow` ACCEPTS a
     * figure for that account and `postFresh` then discards it — so a lookup that
     * failed, has not answered yet, or found no mapping at all must not leave the
     * one cell it protects wide open.
     */
    it("refuses every row while the difference account is unidentified", () => {
        for (const unresolved of [
            { kind: "lookup" as const, accountId: null },
            { kind: "pending" as const },
        ]) {
            expect(canEditOpeningBalanceRow(row({ accountId: "a1" }), unresolved)).toBe(false);
            expect(canEditOpeningBalanceRow(row({ accountId: "diff-1" }), unresolved)).toBe(false);
        }
    });

    /**
     * The backend is adding `computed` to OpeningBalanceRowDTO. When the rows say
     * so themselves there is nothing to look up, and the flag is authoritative.
     */
    it("trusts the row's own computed flag when the server sends one", () => {
        const src = { kind: "rows" as const };
        expect(canEditOpeningBalanceRow(row({ accountId: "diff-1", computed: true }), src)).toBe(false);
        expect(canEditOpeningBalanceRow(row({ accountId: "a1", computed: false }), src)).toBe(true);
        expect(canEditOpeningBalanceRow(row({ derived: true, computed: false }), src)).toBe(false);
    });

    it("knows when the rows carry the flag and the lookup can be skipped", () => {
        expect(gridDeclaresComputed([row({ accountId: "a1" })])).toBe(false);
        expect(gridDeclaresComputed([row({ accountId: "a1" }), row({ accountId: "d", computed: true })])).toBe(true);
        expect(gridDeclaresComputed([row({ accountId: "a1", computed: false })])).toBe(true);
    });

    /**
     * post:292-301 refuses a second post; repost:311-322 replaces. Two distinct
     * actions, and exactly one of them is ever available.
     */
    it("offers Post before the books are open and Replace afterwards, never both", () => {
        const fresh = grid({ posted: false });
        const live = grid({ posted: true, journalId: "j1", journalNumber: "OB/2026/0001" });

        expect(canPostOpeningBalances(fresh)).toBe(true);
        expect(canReplaceOpeningBalances(fresh)).toBe(false);

        expect(canPostOpeningBalances(live)).toBe(false);
        expect(canReplaceOpeningBalances(live)).toBe(true);
    });

    /**
     * OpeningBalanceController's javadoc: the CSV goes through the ordinary
     * multipart limit, `spring.servlet.multipart.max-file-size: 10MB`
     * (application.yml:6), and the global handler turns an oversize upload into a
     * 400 rather than a 500.
     */
    it("refuses an oversize or non-CSV snapshot before it travels", () => {
        expect(SNAPSHOT_MAX_BYTES).toBe(10 * 1024 * 1024);
        expect(SNAPSHOT_ACCEPT).toContain(".csv");

        const big = new File(["x"], "tb.csv", { type: "text/csv" });
        Object.defineProperty(big, "size", { value: SNAPSHOT_MAX_BYTES + 1 });
        expect(snapshotRefusal(big)).toBe("snapshotTooBig");

        expect(snapshotRefusal(new File(["x"], "tb.xlsx", {
            type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        }))).toBe("snapshotWrongType");

        // The parser reads a CSV; browsers label one text/csv, application/csv,
        // text/plain or nothing at all, so the extension is what decides.
        for (const type of ["text/csv", "application/csv", "text/plain", ""]) {
            expect(snapshotRefusal(new File(["x"], "tb.csv", { type }))).toBeNull();
        }
    });
});

describe("reconciliation rules", () => {
    /**
     * ReconciliationRowDTO's difference is `derivedBalance - pactBalance`, both
     * signed debit-positive. "Reconciled" has to mean zero to the fil, not
     * "small", and has to agree with the grid's own totals.
     */
    it("is reconciled only when every difference is zero to the fil", () => {
        expect(isReconciled([])).toBe(true);
        expect(isReconciled([{ difference: 0 } as never])).toBe(true);
        expect(isReconciled([{ difference: 0.01 } as never])).toBe(false);
        expect(isReconciled([{ difference: -0.01 } as never])).toBe(false);
        expect(isReconciled([{ difference: 0 } as never, { difference: 0.01 } as never])).toBe(false);
        // Sub-fil values cannot actually reach here — every figure the server
        // sends is a BigDecimal at scale 2 — but if one did it is zero, not a
        // difference, and must not light the report up red.
        expect(isReconciled([{ difference: 0.004 } as never])).toBe(true);
    });
});

    it("lists batches on the controller's own path", async () => {
        await cutoverApi.batches.list();
        expect(fetch).toHaveBeenCalledWith("/api/proxy/v1/finance/import-batches", expect.anything());
    });

    it("reads one batch by id", async () => {
        await cutoverApi.batches.get("b1");
        expect(fetch).toHaveBeenCalledWith("/api/proxy/v1/finance/import-batches/b1", expect.anything());
    });

    it("reverses a batch with the date and reason ReverseBatchDTO takes", async () => {
        await cutoverApi.batches.reverse("b1", { date: "2026-09-30", reason: "re-import" });
        const [url, init] = (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.at(-1)!;
        expect(url).toBe("/api/proxy/v1/finance/import-batches/b1/reverse");
        expect((init as RequestInit).method).toBe("POST");
        expect(JSON.parse((init as RequestInit).body as string)).toEqual({
            date: "2026-09-30",
            reason: "re-import",
        });
    });

    it("exposes nothing the controller does not", () => {
        // ImportBatchController has list, get and reverse. A bulk-post button wired
        // to an endpoint that does not exist is the 404 this project refuses.
        expect(Object.keys(cutoverApi.batches).sort()).toEqual(["get", "list", "reverse"]);
    });
});

describe("cutoverApi.openingBalances", () => {
    beforeEach(() => {
        vi.stubGlobal(
            "fetch",
            vi.fn(
                async () =>
                    new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } }),
            ),
        );
    });

    it("reads the grid", async () => {
        await cutoverApi.openingBalances.grid();
        expect(fetch).toHaveBeenCalledWith("/api/proxy/v1/finance/opening-balances", expect.anything());
    });

    it("writes one row on the account's own path", async () => {
        vi.stubGlobal("fetch", vi.fn(async () => new Response(null, { status: 204 })));
        await cutoverApi.openingBalances.setRow("acct-1", { debit: 5000, credit: null });
        const [url, init] = (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.at(-1)!;
        expect(url).toBe("/api/proxy/v1/finance/opening-balances/acct-1");
        expect((init as RequestInit).method).toBe("PUT");
        // ManualOpeningBalanceDTO boxes both fields: null means "nothing on that side".
        expect(JSON.parse((init as RequestInit).body as string)).toEqual({ debit: 5000, credit: null });
    });

    it("uploads the snapshot as multipart without a JSON content-type", async () => {
        const file = new File(["code,name,debit,credit"], "tb.csv", { type: "text/csv" });
        await cutoverApi.openingBalances.uploadSnapshot(file);
        const [url, init] = (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.at(-1)!;
        expect(url).toBe("/api/proxy/v1/finance/opening-balances/snapshot");
        expect((init as RequestInit).body).toBeInstanceOf(FormData);
        // The browser has to write the multipart boundary itself.
        expect((init as RequestInit).headers).toBeUndefined();
    });

    it("posts, reposts with a reason, and reverses", async () => {
        await cutoverApi.openingBalances.post();
        expect(fetch).toHaveBeenCalledWith(
            "/api/proxy/v1/finance/opening-balances/post",
            expect.objectContaining({ method: "POST" }),
        );

        await cutoverApi.openingBalances.repost({ reason: "corrected file" });
        let [url, init] = (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.at(-1)!;
        expect(url).toBe("/api/proxy/v1/finance/opening-balances/repost");
        expect(JSON.parse((init as RequestInit).body as string)).toEqual({ reason: "corrected file" });

        // No date: the server always dates the reversal to the live journal's
        // own date, so the caller no longer supplies one.
        await cutoverApi.openingBalances.reverse({ reason: "wrong file" });
        [url, init] = (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.at(-1)!;
        expect(url).toBe("/api/proxy/v1/finance/opening-balances/reverse");
        expect(JSON.parse((init as RequestInit).body as string)).toEqual({ reason: "wrong file" });
    });

    it("reads the reconciliation report", async () => {
        await cutoverApi.reconciliation();
        expect(fetch).toHaveBeenCalledWith("/api/proxy/v1/finance/reconciliation", expect.anything());
    });

    it("exposes nothing the controller does not", () => {
        expect(Object.keys(cutoverApi.openingBalances).sort()).toEqual([
            "grid", "post", "repost", "reverse", "setRow", "uploadSnapshot",
        ]);
        // No contract-import upload, cut-over template or bulk post: those routes
        // are being written now and are a later dispatch.
        expect(Object.keys(cutoverApi).sort()).toEqual(["batches", "openingBalances", "reconciliation"]);
    });
});

describe("cutover rules", () => {
    // ImportBatchController.java:44 — hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT')
    it("admits exactly the three roles the controller admits", () => {
        for (const role of ["SUPER_ADMIN", "TENANT_ADMIN", "ACCOUNTANT"] as const) {
            expect(hasPermission(role, "canManageImportBatches")).toBe(true);
        }
        for (const role of ["PROPERTY_MANAGER", "TENANT_USER", "RENTER", "SECURITY_GUARD"] as const) {
            expect(hasPermission(role, "canManageImportBatches")).toBe(false);
        }
    });

    // ImportBatchService.reverse:177-180 — "only a POSTED batch can be reversed"
    it("offers Reverse for a POSTED batch and for nothing else", () => {
        expect(canReverseBatch("POSTED")).toBe(true);
        expect(canReverseBatch("DRAFT")).toBe(false);
        expect(canReverseBatch("REVERSED")).toBe(false);
    });

    // ImportBatchStatus's own doc: REVERSED "is the end of the line".
    it("treats REVERSED as terminal", () => {
        expect(isBatchFinal("REVERSED")).toBe(true);
        expect(isBatchFinal("POSTED")).toBe(false);
        expect(isBatchFinal("DRAFT")).toBe(false);
    });

    // OpeningBalanceController.java:57 — the same three roles as the batches one.
    it("gates opening balances on the same key as the batches screen", () => {
        for (const role of ["SUPER_ADMIN", "TENANT_ADMIN", "ACCOUNTANT"] as const) {
            expect(hasPermission(role, "canManageOpeningBalances")).toBe(true);
        }
        for (const role of ["PROPERTY_MANAGER", "TENANT_USER", "RENTER"] as const) {
            expect(hasPermission(role, "canManageOpeningBalances")).toBe(false);
        }
    });

    /**
     * PortfolioImportController#template (:78-79) is
     * hasAnyRole('SUPER_ADMIN','TENANT_ADMIN') — it does NOT admit ACCOUNTANT,
     * unlike every other control on this page. Offering an accountant a download
     * that 403s is exactly the pattern this module exists to prevent.
     */
    it("offers the import template only to the roles that endpoint admits", () => {
        expect(canDownloadImportTemplate("SUPER_ADMIN")).toBe(true);
        expect(canDownloadImportTemplate("TENANT_ADMIN")).toBe(true);
        expect(canDownloadImportTemplate("ACCOUNTANT")).toBe(false);
        expect(canDownloadImportTemplate("PROPERTY_MANAGER")).toBe(false);
        expect(canDownloadImportTemplate(undefined)).toBe(false);
    });
});
