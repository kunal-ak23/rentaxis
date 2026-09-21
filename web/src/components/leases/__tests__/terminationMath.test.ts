import { describe, expect, it } from "vitest";
import type { Cheque, TerminationPreview } from "@/lib/api/leasing";
import {
    defaultDecisions,
    receivableAfterForSplit,
    splitIds,
    unclearedRows,
} from "../terminationMath";

/**
 * `TerminationPreviewDTO.receivableAfter` is computed by the server for the
 * DEFAULT return/keep split (`LeaseTerminationService.receivableAfter`
 * :384-392 — `current + Σ returned − unearned`). The screen lets finance flip a
 * row before confirming, and a figure that stayed at the server's default while
 * the table said something else is the sort of thing an accountant signs.
 *
 * So the arithmetic lives here, once, mirrored from that method: returning a
 * row ADDS its amount to the receivable (the PDR is reversed, the money is owed
 * again), keeping it removes it. The POST re-validates — this is what the
 * reader sees while deciding, not the truth of record.
 */

function cheque(over: Partial<Cheque> & { id: string; seqNo: number; amount: number }): Cheque {
    return {
        leaseId: "lease-1", propertyId: "p1", unitId: "u1", renterId: "r1",
        propertyName: "L'Olivier", unitIdentifier: "204", renterName: "Prabhjot Singh",
        postingDate: "2026-01-01", chequeNumber: "000101", chequeDate: "2026-06-01",
        payeeBank: "ENBD", payerName: "Prabhjot Singh", debitAccountId: null, debitAccountName: null,
        narration: null, mode: "PDC", status: "REGISTERED", failureReason: null,
        replacesId: null, replacedById: null, imageUrl: null, depositedAt: null, clearedAt: null,
        bouncedAt: null, returnedAt: null, pdrJournalId: null, crtJournalId: null, cbrJournalId: null,
        penaltyAssessmentId: null, due: false, overdue: false, daysOverdue: 0,
        ...over,
    };
}

/**
 * Three uncleared rows. The server's default hands back #3 and #4 (dated after
 * T) and keeps #2 (dated on or before it), so its `receivableAfter` of 7,000
 * already carries 3,000 + 4,000 of returns.
 */
function preview(over: Partial<TerminationPreview> = {}): TerminationPreview {
    return {
        terminationDate: "2026-05-31",
        earnedRentThroughDate: 20000,
        recognisedSoFar: 18000,
        unearnedRent: 12000,
        chequesToKeep: [cheque({ id: "c2", seqNo: 2, amount: 2000, chequeDate: "2026-05-01" })],
        chequesToReturn: [
            cheque({ id: "c3", seqNo: 3, amount: 3000, chequeDate: "2026-06-01" }),
            cheque({ id: "c4", seqNo: 4, amount: 4000, chequeDate: "2026-09-01" }),
        ],
        bouncedOutstanding: [cheque({ id: "c1", seqNo: 1, amount: 1000, status: "BOUNCED" })],
        receivableAfter: 7000,
        ...over,
    };
}

describe("defaultDecisions", () => {
    it("takes the server's split as the starting point and leaves bounced rows out", () => {
        expect(defaultDecisions(preview())).toEqual({ c2: "KEEP", c3: "RETURN", c4: "RETURN" });
    });
});

describe("unclearedRows", () => {
    it("merges both lists in seqNo order — the order the register is read in", () => {
        expect(unclearedRows(preview()).map(c => c.id)).toEqual(["c2", "c3", "c4"]);
    });
});

describe("receivableAfterForSplit", () => {
    it("returns the server's own figure for the server's own split", () => {
        const p = preview();
        expect(receivableAfterForSplit(p, defaultDecisions(p))).toBe(7000);
    });

    it("removes a row's amount from the receivable when it is flipped to Keep", () => {
        const p = preview();
        const decisions = { ...defaultDecisions(p), c4: "KEEP" as const };
        // 7,000 − 4,000: the cheque is banked instead of handed back, so the
        // money stops being owed on the receivable.
        expect(receivableAfterForSplit(p, decisions)).toBe(3000);
    });

    it("adds a row's amount when it is flipped to Return", () => {
        const p = preview();
        const decisions = { ...defaultDecisions(p), c2: "RETURN" as const };
        expect(receivableAfterForSplit(p, decisions)).toBe(9000);
    });

    it("goes negative — the landlord owing the renter — when everything is returned", () => {
        const p = preview({ receivableAfter: -1000 });
        const decisions = { c2: "RETURN" as const, c3: "RETURN" as const, c4: "RETURN" as const };
        expect(receivableAfterForSplit(p, decisions)).toBe(1000);
    });

    it("never counts a bounced row, whatever the decisions map says", () => {
        const p = preview();
        const decisions = { ...defaultDecisions(p), c1: "RETURN" as const };
        expect(receivableAfterForSplit(p, decisions)).toBe(7000);
    });

    it("rounds to the fils rather than trailing a float", () => {
        const p = preview({
            chequesToKeep: [],
            chequesToReturn: [cheque({ id: "c9", seqNo: 9, amount: 1041.67 })],
            bouncedOutstanding: [],
            receivableAfter: 0.1,
        });
        expect(receivableAfterForSplit(p, { c9: "KEEP" })).toBe(-1041.57);
    });
});

describe("splitIds", () => {
    it("sends both lists in full — every uncleared row in exactly one of them", () => {
        const p = preview();
        const decisions = { ...defaultDecisions(p), c4: "KEEP" as const };
        expect(splitIds(p, decisions)).toEqual({
            returnChequeIds: ["c3"],
            keepChequeIds: ["c2", "c4"],
        });
    });

    it("omits bounced rows, which the server refuses in either list", () => {
        const p = preview();
        const ids = splitIds(p, { ...defaultDecisions(p), c1: "RETURN" });
        expect(ids.returnChequeIds).not.toContain("c1");
        expect(ids.keepChequeIds).not.toContain("c1");
    });
});
