import { beforeEach, describe, expect, it, vi } from "vitest";
import { cutoverApi } from "@/lib/api/cutover";
import { canDownloadImportTemplate, canReverseBatch, isBatchFinal } from "@/lib/cutoverRules";
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
