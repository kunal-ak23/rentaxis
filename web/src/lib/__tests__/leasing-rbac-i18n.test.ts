import { describe, expect, it } from "vitest";

import { PERMISSIONS, type UserRole } from "../rbac";
import en from "../../../messages/en.json";
import ar from "../../../messages/ar.json";

/**
 * Pins each accounting-v2 plan-2 permission's role list to the controller
 * annotation it was derived from (task-13-report.md has the full citation per
 * permission). RBAC here must never admit a role the backend refuses — plan 1
 * shipped a sidebar link an accountant's own API 403'd on, and this table is
 * the regression test for that class of bug.
 */
const EXPECTED: Record<string, UserRole[]> = {
    // LeaseController#postLease / #amendLeaseLines
    canPostLeases: ["SUPER_ADMIN", "TENANT_ADMIN", "ACCOUNTANT"],
    // LeaseController#renewLease
    canRenewLeases: ["SUPER_ADMIN", "TENANT_ADMIN", "ACCOUNTANT", "PROPERTY_MANAGER"],
    // LeaseController#extendLease
    canExtendLeases: ["SUPER_ADMIN", "TENANT_ADMIN", "ACCOUNTANT"],
    // ChequeController.STAFF (deposit/clear/receive/bounce/replace/details/
    // cash-receipt/deposit-batch) + LeaseController's cheque-grid endpoints
    canManageCheques: ["SUPER_ADMIN", "TENANT_ADMIN", "ACCOUNTANT", "PROPERTY_MANAGER"],
    // ChequeController.FINANCE on PUT /{id}/cancel
    canCancelCheques: ["SUPER_ADMIN", "TENANT_ADMIN", "ACCOUNTANT"],
    // PenaltyAssessmentController#list / #propose
    canProposePenalties: ["SUPER_ADMIN", "TENANT_ADMIN", "ACCOUNTANT", "PROPERTY_MANAGER"],
    // PenaltyAssessmentController#approve / #waive / #reverse
    canApprovePenalties: ["SUPER_ADMIN", "TENANT_ADMIN", "ACCOUNTANT"],
    // ChargeTypeController's method-level @PreAuthorize on POST / PUT
    canManageChargeTypes: ["SUPER_ADMIN", "TENANT_ADMIN", "ACCOUNTANT"],

    // ---- plan 3: recognition, termination, settlement ----
    //
    // canTerminateLeases used to read [SA, TA, PROPERTY_MANAGER], because
    // "Terminate" opened the settlement flow and all four settlement endpoints
    // admitted a manager. Plan 3 made termination its own act — it hands
    // cheques back, truncates recognition and posts a TCR — and
    // LeaseController#terminateLease is SA/TA/ACCOUNTANT. A manager keeps the
    // read half through canPreviewTermination and canViewSettlement.
    canTerminateLeases: ["SUPER_ADMIN", "TENANT_ADMIN", "ACCOUNTANT"],
    // LeaseController#previewTermination
    canPreviewTermination: ["SUPER_ADMIN", "TENANT_ADMIN", "ACCOUNTANT", "PROPERTY_MANAGER"],
    // LeaseController#getSettlementStatement / #getSettlement
    canViewSettlement: ["SUPER_ADMIN", "TENANT_ADMIN", "ACCOUNTANT", "PROPERTY_MANAGER"],
    // LeaseController#saveSettlementDraft / #finalizeSettlement — PM removed
    canSettleLeases: ["SUPER_ADMIN", "TENANT_ADMIN", "ACCOUNTANT"],
    // RecognitionController.FINANCE_ROLES on /finance/recognition/pending + /run
    canRunRecognition: ["SUPER_ADMIN", "TENANT_ADMIN", "ACCOUNTANT"],
    // RecognitionController#schedule on GET /leases/{id}/recognition
    canViewRecognitionSchedule: ["SUPER_ADMIN", "TENANT_ADMIN", "ACCOUNTANT", "PROPERTY_MANAGER"],
};

describe("accounting-v2 plan-2 permission roles", () => {
    for (const [permission, roles] of Object.entries(EXPECTED)) {
        it(`${permission} admits exactly ${roles.join(", ")}`, () => {
            const actual = PERMISSIONS[permission as keyof typeof PERMISSIONS] as readonly UserRole[];
            expect(new Set(actual)).toEqual(new Set(roles));
            expect(actual.length).toBe(roles.length);
        });
    }

    // canManageLeases is deliberately NOT widened: LeaseController's draft
    // create/update/delete stay SA/TA-only even though posting the same lease
    // (canPostLeases) admits ACCOUNTANT.
    it("canManageLeases stays SUPER_ADMIN/TENANT_ADMIN — draft create/update/delete refuse ACCOUNTANT", () => {
        expect(new Set(PERMISSIONS.canManageLeases)).toEqual(new Set(["SUPER_ADMIN", "TENANT_ADMIN"]));
    });
});

/**
 * The Leasing/Cheques i18n namespaces exist twice, like every other locale
 * file in this repo: a missing Arabic key silently falls back to the key
 * path, and a copy-pasted English value silently ships English on the Arabic
 * site. Neither is caught by JSON.parse alone.
 */
function flatten(obj: unknown, prefix = ""): Record<string, string> {
    const out: Record<string, string> = {};
    if (obj && typeof obj === "object") {
        for (const [k, v] of Object.entries(obj as Record<string, unknown>)) {
            const key = prefix ? `${prefix}.${k}` : k;
            if (v && typeof v === "object") {
                Object.assign(out, flatten(v, key));
            } else {
                out[key] = String(v);
            }
        }
    }
    return out;
}

describe("Leasing/Cheques i18n parity", () => {
    for (const ns of ["Leasing", "Cheques", "Recognition", "Termination", "Settlement"] as const) {
        it(`${ns}: every en.json key has a distinct ar.json translation`, () => {
            const enNs = flatten((en as Record<string, unknown>)[ns]);
            const arNs = flatten((ar as Record<string, unknown>)[ns]);

            const enKeys = Object.keys(enNs);
            expect(enKeys.length).toBeGreaterThan(0);

            expect(enKeys.filter((k) => !(k in arNs)), "keys absent from ar.json").toEqual([]);
            expect(Object.keys(arNs).filter((k) => !(k in enNs)), "keys absent from en.json").toEqual([]);
            expect(enKeys.filter((k) => arNs[k] === enNs[k]), "keys still holding English text").toEqual([]);
        });
    }

    it("carries every ChequeStatus value under Cheques.status", () => {
        const statuses = [
            "DRAFT", "REGISTERED", "DEPOSITED", "CLEARED", "BOUNCED",
            "REPLACED", "CANCELLED", "RETURNED", "ONLINE_PENDING",
        ];
        for (const s of statuses) {
            expect(en.Cheques.status).toHaveProperty(s);
            expect(ar.Cheques.status).toHaveProperty(s);
        }
    });

    it("carries every ChequeMode value under Leasing.mode", () => {
        for (const m of ["PDC", "CASH", "TRANSFER", "ONLINE"]) {
            expect(en.Leasing.mode).toHaveProperty(m);
            expect(ar.Leasing.mode).toHaveProperty(m);
        }
    });

    it("carries every PenaltyReason value under Cheques.reason", () => {
        for (const r of ["CHEQUE_RETURN", "LATE_PAYMENT", "OTHER"]) {
            expect(en.Cheques.reason).toHaveProperty(r);
            expect(ar.Cheques.reason).toHaveProperty(r);
        }
    });

    it("carries every PenaltyAssessmentStatus value under Cheques.penaltyStatus", () => {
        for (const s of ["PROPOSED", "APPROVED", "WAIVED", "REVERSED"]) {
            expect(en.Cheques.penaltyStatus).toHaveProperty(s);
            expect(ar.Cheques.penaltyStatus).toHaveProperty(s);
        }
    });

    it("carries every ChequeFailureReason value under Cheques.failureReasons", () => {
        for (const r of ["BOUNCE", "SIGNATURE_MISMATCH", "ACCOUNT_CLOSED"]) {
            expect(en.Cheques.failureReasons).toHaveProperty(r);
            expect(ar.Cheques.failureReasons).toHaveProperty(r);
        }
    });

    it("labels OnlinePaymentStatus.CAPTURED_UNAPPLIED", () => {
        expect(en.Cheques.onlinePaymentStatus.CAPTURED_UNAPPLIED).toBeTruthy();
        expect(ar.Cheques.onlinePaymentStatus.CAPTURED_UNAPPLIED).toBeTruthy();
    });
});
