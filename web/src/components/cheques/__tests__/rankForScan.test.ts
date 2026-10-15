import { describe, expect, it, vi } from "vitest";

vi.mock("@/components/cheques/BulkChequeUploadFlow", () => ({ default: () => null }));
vi.mock("@/components/leases/LeaseDialog", () => ({ default: () => null }));

import { rankForScan } from "../ScanChequesLauncher";

// Tutorial 15: the DRAFT renewal listed above the active contract it renews.
describe("rankForScan", () => {
    it("puts contracts on the books before drafts, keeping the server's order within each", () => {
        const out = rankForScan([
            { id: "d1", status: "DRAFT" as const },
            { id: "a1", status: "ACTIVE" as const },
            { id: "d2", status: "DRAFT" as const },
            { id: "a2", status: "ACTIVE" as const },
        ]);
        expect(out.map(l => l.id)).toEqual(["a1", "a2", "d1", "d2"]);
    });
});
