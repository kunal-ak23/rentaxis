import { describe, expect, it } from "vitest";
import { registerActionsFor } from "@/components/cheques/registerActions";

/**
 * The register's row-action set, per spec §7.4 and Task 15's ruling #2 —
 * shared with the lease page's `ChequeGrid`, which imports this same table.
 *
 * Exercised as a pure function against every (status × mode × role) the
 * register can show, so this does not need a rendered page or a mocked
 * session to prove the exact action set per row.
 */
describe("registerActionsFor", () => {
    describe("REGISTERED", () => {
        it("offers Deposit, Details and Cancel for a PDC row when the caller may cancel", () => {
            expect(registerActionsFor("REGISTERED", "PDC", true)).toEqual(["deposit", "details", "cancel"]);
        });

        it("offers Receive, Details and Cancel for a CASH row when the caller may cancel", () => {
            expect(registerActionsFor("REGISTERED", "CASH", true)).toEqual(["receive", "details", "cancel"]);
        });

        it("offers Receive for a TRANSFER row too — mode, not just PDC-vs-not, decides", () => {
            expect(registerActionsFor("REGISTERED", "TRANSFER", true)).toEqual(["receive", "details", "cancel"]);
        });

        it("drops Cancel for a property manager, who may not cancel", () => {
            expect(registerActionsFor("REGISTERED", "PDC", false)).toEqual(["deposit", "details"]);
            expect(registerActionsFor("REGISTERED", "CASH", false)).toEqual(["receive", "details"]);
        });
    });

    describe("DEPOSITED", () => {
        it("offers Clear and Bounce regardless of mode or role", () => {
            expect(registerActionsFor("DEPOSITED", "PDC", true)).toEqual(["clear", "bounce"]);
            expect(registerActionsFor("DEPOSITED", "PDC", false)).toEqual(["clear", "bounce"]);
        });
    });

    describe("CLEARED", () => {
        it("offers Bounce (late return) and Receipt for a PDC row", () => {
            expect(registerActionsFor("CLEARED", "PDC", true)).toEqual(["bounce", "receipt"]);
        });

        it("offers Receipt only for a CASH row — cash does not un-arrive", () => {
            expect(registerActionsFor("CLEARED", "CASH", true)).toEqual(["receipt"]);
        });

        it("offers Receipt only for a TRANSFER row — a settled transfer is reversed by the bank, not by this dialog", () => {
            expect(registerActionsFor("CLEARED", "TRANSFER", true)).toEqual(["receipt"]);
        });
    });

    describe("BOUNCED", () => {
        it("offers Replace only, for every mode and role", () => {
            expect(registerActionsFor("BOUNCED", "PDC", true)).toEqual(["replace"]);
            expect(registerActionsFor("BOUNCED", "CASH", false)).toEqual(["replace"]);
        });
    });

    describe("terminal / non-actionable statuses", () => {
        it("offers nothing for REPLACED, CANCELLED, RETURNED or ONLINE_PENDING", () => {
            for (const status of ["REPLACED", "CANCELLED", "RETURNED", "ONLINE_PENDING"] as const) {
                expect(registerActionsFor(status, "PDC", true)).toEqual([]);
            }
        });
    });
});
