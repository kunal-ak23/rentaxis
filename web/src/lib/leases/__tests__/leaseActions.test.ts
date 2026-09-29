import { describe, expect, it } from "vitest";
import type { LeaseStatus } from "@/lib/api/leasing";
import type { UserRole } from "@/lib/rbac";
import { availableLeaseActions, canGenerateContract, hasSettlement, leasePermsFor, MENU_ORDER, splitLeaseActions, type LeaseActionFacts, type LeaseActionId, type LeasePerms } from "../leaseActions";

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

/** The two cards that sat on the Overview tab (page.tsx 897–902), now menu entries. */
function legacyCards(f: LeaseActionFacts, p: LeasePerms): Set<LeaseActionId> {
    const out = new Set<LeaseActionId>();
    if (p.canSeeBadDebts && f.status !== "DRAFT") out.add("writeOff");
    // LeaseAssignmentCard: assignable = live && posted; posted assignments only exist on a posted lease.
    if (f.posted) out.add("assignment");
    return out;
}

/**
 * Break-it R4 (tutorials/bugs/11, "seen alongside"): legacy entries the server
 * refuses, dropped on purpose — Delete on a pending-signature contract
 * ("Only DRAFT leases can be deleted", LeaseService.deleteDraftLease) and Write
 * off before the contract is posted (BadDebtService refuses DRAFT and PENDING_SIGNATURE).
 */
function backendRefuses(a: LeaseActionId, status: LeaseStatus): boolean {
    return (a === "delete" && status !== "DRAFT")
        || (a === "writeOff" && (status === "DRAFT" || status === "PENDING_SIGNATURE"));
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
            for (const a of [...legacyHeader(f, p), ...legacyCards(f, p)]) {
                if (backendRefuses(a, f.status)) continue;
                if (!reachable.has(a)) lost.push(`${role} ${JSON.stringify(f)} lost ${a}`);
            }
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

    it("gates the new entries behind what their targets already required", () => {
        for (const { role, f } of matrix()) {
            const p = leasePermsFor(role);
            const a = availableLeaseActions(f, p);
            if (a.includes("edit")) expect(p.canDraft && ["DRAFT", "PENDING_SIGNATURE"].includes(f.status)).toBe(true);
            if (a.includes("recordPayment")) expect(p.canCheques && f.posted && ["ACTIVE", "NOTICE_GIVEN"].includes(f.status)).toBe(true);
        }
    });

    it("never shows more than three primary buttons", () => {
        for (const { role, f } of matrix()) {
            expect(splitLeaseActions(availableLeaseActions(f, leasePermsFor(role)), f.status).primary.length).toBeLessThanOrEqual(3);
        }
    });

    it("never lists an action twice, and the menu order names every action once", () => {
        expect(new Set(MENU_ORDER).size).toBe(MENU_ORDER.length);
        expect(MENU_ORDER).toHaveLength(18);
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

    it("offers nothing to a renter or a guard", () => {
        const f = { status: "ACTIVE" as LeaseStatus, posted: true, hasContract: false, transferredOut: false };
        for (const role of ["RENTER", "SECURITY_GUARD", "TENANT_USER"] as UserRole[]) {
            const a = availableLeaseActions(f, leasePermsFor(role));
            expect(a.filter(x => x !== "assignment" && x !== "ledger")).toEqual([]);
        }
    });

    /**
     * Break-it R4 / tutorials/bugs/11: the contract page offered actions the server
     * refuses. Each is shown only where the backend allows it.
     */
    it("offers Delete only on a DRAFT (the server refuses a pending-signature one)", () => {
        const p = leasePermsFor("TENANT_ADMIN");
        for (const status of STATUSES) {
            const a = availableLeaseActions({ status, posted: false, hasContract: true, transferredOut: false }, p);
            expect(a.includes("delete")).toBe(status === "DRAFT");
        }
    });

    it("offers Write off only once the contract is out of drafting (BadDebtService refuses DRAFT and PENDING_SIGNATURE)", () => {
        const p = leasePermsFor("TENANT_ADMIN");
        for (const status of STATUSES) {
            const a = availableLeaseActions({ status, posted: status !== "DRAFT" && status !== "PENDING_SIGNATURE", hasContract: true, transferredOut: false }, p);
            expect(a.includes("writeOff")).toBe(status !== "DRAFT" && status !== "PENDING_SIGNATURE");
        }
    });

    it("previews and generates a contract only for DRAFT or PENDING_SIGNATURE (ContractGenerationService)", () => {
        for (const status of STATUSES) {
            expect(canGenerateContract(status)).toBe(status === "DRAFT" || status === "PENDING_SIGNATURE");
        }
    });

    it("has a settlement to view only once the tenancy has ended (same set as the Settlement action)", () => {
        for (const status of STATUSES) {
            expect(hasSettlement(status)).toBe(["TERMINATED", "EXPIRED", "RENEWED", "CLOSED"].includes(status));
        }
    });
});
