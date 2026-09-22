import { describe, expect, it } from "vitest";
import {
    COLLECTABLE_LEASE_STATUSES,
    POSTED_LEASE_STATUSES,
    leaseIsCollectable,
    leaseTakesNewRows,
    registerActionsFor,
} from "../registerActions";
import type { LeaseStatus } from "@/lib/api/leasing";

/**
 * The row-action table is the client's one mirror of `ChequeService`'s status
 * sets, and this pins both halves of it: the cheque's own state machine (which
 * was already here) and the LEASE's, which the register and the grid used to
 * ignore entirely.
 *
 * `requireCollectable` (ChequeService.java:1232) runs on every transition —
 * deposit, clear, receive, bounce, replace, cancel, return, release-online — so
 * a screen that offers one on a lease outside `COLLECTABLE` (:145-147) offers a
 * button that always 400s.
 */

const EVERY_STATUS: LeaseStatus[] = [
    "DRAFT", "PENDING_SIGNATURE", "ACTIVE", "NOTICE_GIVEN", "RENEWED", "TERMINATED", "EXPIRED", "CLOSED",
];

describe("the server's two lease-status sets", () => {
    it("COLLECTABLE is ChequeService's, CLOSED deliberately absent", () => {
        expect([...COLLECTABLE_LEASE_STATUSES].sort()).toEqual(
            ["ACTIVE", "EXPIRED", "NOTICE_GIVEN", "RENEWED", "TERMINATED"],
        );
        expect(leaseIsCollectable("CLOSED")).toBe(false);
        expect(leaseIsCollectable("DRAFT")).toBe(false);
    });

    it("POSTED is the narrower set that may take a NEW row — EXPIRED withdrawn", () => {
        expect([...POSTED_LEASE_STATUSES].sort()).toEqual(["ACTIVE", "NOTICE_GIVEN", "RENEWED"]);
        expect(leaseTakesNewRows("EXPIRED")).toBe(false);
        expect(leaseTakesNewRows("TERMINATED")).toBe(false);
        expect(leaseTakesNewRows("CLOSED")).toBe(false);
        expect(leaseTakesNewRows("ACTIVE")).toBe(true);
    });

    it("an unknown lease status is neither — a screen that does not know must not guess", () => {
        expect(leaseIsCollectable(null)).toBe(false);
        expect(leaseTakesNewRows(undefined)).toBe(false);
    });
});

describe("registerActionsFor without a lease (the caller that cannot know)", () => {
    it("still answers the cheque's own state machine", () => {
        expect(registerActionsFor("REGISTERED", "PDC", true)).toEqual(["deposit", "details", "cancel"]);
        expect(registerActionsFor("REGISTERED", "CASH", false)).toEqual(["receive", "details"]);
        expect(registerActionsFor("DEPOSITED", "PDC", true)).toEqual(["clear", "bounce"]);
        expect(registerActionsFor("CLEARED", "PDC", true)).toEqual(["bounce", "receipt"]);
        expect(registerActionsFor("CLEARED", "CASH", true)).toEqual(["receipt"]);
        expect(registerActionsFor("BOUNCED", "PDC", true)).toEqual(["replace"]);
        expect(registerActionsFor("ONLINE_PENDING", "ONLINE", true)).toEqual(["releaseOnline"]);
        expect(registerActionsFor("RETURNED", "PDC", true)).toEqual([]);
    });
});

describe("registerActionsFor on a CLOSED contract", () => {
    /**
     * The twelve-cases defect: a closed contract whose settlement is finished
     * still showed Bounce on a cleared PDC, and Receive / Cancel on the
     * REGISTERED balance-due row a settlement had raised.
     */
    it("offers nothing at all, whatever the row says", () => {
        for (const status of ["REGISTERED", "DEPOSITED", "CLEARED", "BOUNCED", "ONLINE_PENDING"] as const) {
            expect(registerActionsFor(status, "PDC", true, { status: "CLOSED" })).toEqual([]);
            expect(registerActionsFor(status, "CASH", true, { status: "CLOSED" })).toEqual([]);
        }
    });

    it("offers nothing on a DRAFT or PENDING_SIGNATURE contract either", () => {
        expect(registerActionsFor("REGISTERED", "PDC", true, { status: "DRAFT" })).toEqual([]);
        expect(registerActionsFor("REGISTERED", "PDC", true, { status: "PENDING_SIGNATURE" })).toEqual([]);
    });

    it("leaves every COLLECTABLE status exactly as it was", () => {
        for (const leaseStatus of COLLECTABLE_LEASE_STATUSES) {
            expect(registerActionsFor("DEPOSITED", "PDC", true, { status: leaseStatus })).toEqual(["clear", "bounce"]);
        }
        const refused = EVERY_STATUS.filter(s => !COLLECTABLE_LEASE_STATUSES.includes(s));
        expect(refused.sort()).toEqual(["CLOSED", "DRAFT", "PENDING_SIGNATURE"]);
    });
});

describe("registerActionsFor once the lease's settlement is FINALIZED", () => {
    /**
     * `requireSettlementUndisturbed` (ChequeService.java:1093): cancelling or
     * handing back a row the statement counted on re-debits a receivable the
     * settlement flattened. Replace is the server's own suggested alternative
     * ("replace it instead") and stays.
     */
    it("withholds cancel — the one reversal verb this table offers", () => {
        expect(registerActionsFor("REGISTERED", "PDC", true, { status: "TERMINATED", settlementFinalized: true }))
            .toEqual(["deposit", "details"]);
        expect(registerActionsFor("REGISTERED", "CASH", true, { status: "TERMINATED", settlementFinalized: true }))
            .toEqual(["receive", "details"]);
    });

    it("keeps replace on a BOUNCED row — a bounce AFTER finalise is a real debt", () => {
        expect(registerActionsFor("BOUNCED", "PDC", true, { status: "TERMINATED", settlementFinalized: true }))
            .toEqual(["replace"]);
    });

    it("leaves cancel alone while the settlement is still a draft", () => {
        expect(registerActionsFor("REGISTERED", "PDC", true, { status: "TERMINATED", settlementFinalized: false }))
            .toEqual(["deposit", "details", "cancel"]);
    });
});
