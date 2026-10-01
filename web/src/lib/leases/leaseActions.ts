import type { LeaseStatus } from "../api/leasing";
import { hasPermission, type UserRole } from "../rbac";

export type LeaseActionId =
    | "edit" | "post" | "recordPayment" | "renew" | "settlement" | "extend" | "amend" | "addCharge" | "transfer"
    | "assignment" | "reduce" | "raisePenalty" | "giveNotice" | "terminate" | "writeOff" | "downloadContract" | "ledger" | "delete";
export interface LeaseActionFacts { status: LeaseStatus; posted: boolean; hasContract: boolean; transferredOut: boolean }
export interface LeasePerms {
    canDraft: boolean; canPost: boolean; canRenew: boolean; canExtend: boolean; canCheques: boolean;
    canRaisePenalty: boolean; canGiveNotice: boolean; canPreviewTermination: boolean; canViewSettlement: boolean; canSeeBadDebts: boolean;
}

const DRAFTING: LeaseStatus[] = ["DRAFT", "PENDING_SIGNATURE"];
const LIVE: LeaseStatus[] = ["ACTIVE", "NOTICE_GIVEN"];
const RENEWABLE: LeaseStatus[] = ["ACTIVE", "EXPIRED", "NOTICE_GIVEN"];
const PENALTY_CHARGEABLE: LeaseStatus[] = ["ACTIVE", "NOTICE_GIVEN", "EXPIRED", "RENEWED"];
const HAS_SETTLEMENT: LeaseStatus[] = ["TERMINATED", "EXPIRED", "RENEWED", "CLOSED"];

/**
 * Break-it R4 / tutorials/bugs/11: the contract page's buttons outside the action
 * menu ask the same model. Preview/Confirm & Save only where
 * ContractGenerationService generates ("DRAFT or PENDING_SIGNATURE leases").
 */
export function canGenerateContract(status: LeaseStatus): boolean {
    return DRAFTING.includes(status);
}

/**
 * A contract with a settlement to open: SettlementService.SETTLEABLE plus CLOSED,
 * whose finalised statement is what a closed contract is read for. The Settlement
 * action and the "View settlement" link both ask this.
 */
export function hasSettlement(status: LeaseStatus): boolean {
    return HAS_SETTLEMENT.includes(status);
}

/** The contract page's own permission reads, in one place. */
export function leasePermsFor(role: UserRole | undefined): LeasePerms {
    return {
        canDraft: hasPermission(role, "canManageLeases"),
        canPost: hasPermission(role, "canPostLeases"),
        canRenew: hasPermission(role, "canRenewLeases"),
        canExtend: hasPermission(role, "canExtendLeases"),
        canCheques: hasPermission(role, "canManageCheques"),
        // #12: Raise penalty is finance's (the roles that decide penalties). A
        // property manager still proposes from the Penalties section, which
        // PenaltyAssessmentController allows by design.
        canRaisePenalty: hasPermission(role, "canApprovePenalties"),
        // One role wider than terminating, and its own key: taking a notice
        // writes no journal (`LeaseController` :250-251).
        canGiveNotice: hasPermission(role, "canGiveNotice"),
        // Terminate opens the termination page, which prices the move-out before
        // anything is written — a property manager may do that on their own
        // buildings (`LeaseController#previewTermination`). That page hides the
        // button that posts the journals from them (`canTerminateLeases`).
        canPreviewTermination: hasPermission(role, "canPreviewTermination"),
        canViewSettlement: hasPermission(role, "canViewSettlement"),
        canSeeBadDebts: hasPermission(role, "canAccessFinance"),
    };
}

/**
 * Every action the contract page offers for these facts, with the gates the
 * header used before (see the legacy reference in leaseActions.test.ts).
 * New entries: Edit (jumps to the draft editor already on the page), Record
 * payment (opens the cash receipt already on the cheque register), and
 * Assignment / Write off (the two cards, now opened from the menu) — each
 * behind the gate its target already had.
 */
export function availableLeaseActions(f: LeaseActionFacts, p: LeasePerms): LeaseActionId[] {
    const drafting = DRAFTING.includes(f.status);
    const live = LIVE.includes(f.status);
    const on: Record<LeaseActionId, boolean> = {
        edit: drafting && p.canDraft,
        post: drafting && p.canPost,
        recordPayment: live && f.posted && p.canCheques,
        renew: RENEWABLE.includes(f.status) && p.canRenew,
        settlement: hasSettlement(f.status) && p.canViewSettlement,
        extend: f.status === "ACTIVE" && p.canExtend,
        amend: f.status === "ACTIVE" && p.canPost,
        addCharge: f.status === "ACTIVE" && p.canExtend,
        transfer: live && f.posted && p.canRenew && !f.transferredOut,
        // LeaseAssignmentCard renders whenever the lease is assignable or has posted
        // assignments; assignments only ever exist on a posted contract, so a posted
        // contract always offers the entry (the drawer says so when there is none).
        assignment: f.posted,
        reduce: live && f.posted && (p.canExtend || p.canRenew),
        raisePenalty: PENALTY_CHARGEABLE.includes(f.status) && p.canRaisePenalty,
        giveNotice: f.status === "ACTIVE" && p.canGiveNotice,
        terminate: live && p.canPreviewTermination,
        // BadDebtService refuses a contract still in drafting (break-it R4).
        writeOff: p.canSeeBadDebts && !drafting,
        downloadContract: f.hasContract,
        ledger: f.posted,
        // LeaseService: "Only DRAFT leases can be deleted" (break-it R4).
        delete: f.status === "DRAFT" && p.canDraft,
    };
    return MENU_ORDER.filter(id => on[id]);
}

export const PRIMARY_BY_STATUS: Record<LeaseStatus, LeaseActionId[]> = {
    DRAFT: ["edit", "post"], PENDING_SIGNATURE: ["edit", "post"],
    ACTIVE: ["recordPayment", "renew"], NOTICE_GIVEN: ["recordPayment", "renew"],
    EXPIRED: ["renew", "settlement"], TERMINATED: ["settlement"], RENEWED: ["settlement"], CLOSED: ["settlement"],
};

/** Spec §5 order, then the actions that are primary in some other status — every LeaseActionId exactly once. */
export const MENU_ORDER: LeaseActionId[] = [
    "extend", "amend", "addCharge", "transfer", "assignment", "reduce", "raisePenalty", "giveNotice", "terminate",
    "writeOff", "downloadContract", "ledger", "post", "renew", "settlement", "recordPayment", "edit", "delete",
];

export function splitLeaseActions(available: LeaseActionId[], status: LeaseStatus): { primary: LeaseActionId[]; menu: LeaseActionId[] } {
    const primary = (PRIMARY_BY_STATUS[status] ?? []).filter(a => available.includes(a)).slice(0, 3);
    const menu = MENU_ORDER.filter(a => available.includes(a) && !primary.includes(a));
    return { primary, menu };
}
