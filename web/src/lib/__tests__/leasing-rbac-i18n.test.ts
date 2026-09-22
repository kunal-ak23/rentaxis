import { describe, expect, it } from "vitest";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

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
    // LeaseController#giveNotice (LeaseController.java:250-251) — one role wider
    // than canTerminateLeases on purpose: taking a renter's notice writes no
    // journal and hands nothing back, so it is the building manager's job.
    canGiveNotice: ["SUPER_ADMIN", "TENANT_ADMIN", "ACCOUNTANT", "PROPERTY_MANAGER"],
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

/**
 * A value that is legitimately the same in both locales. One entry so far: PACT
 * is the outgoing system's name, and a name is not translated.
 */
const SAME_IN_BOTH_LOCALES = new Set(["Cutover.pactBalance"]);

describe("accounting i18n parity", () => {
    for (const ns of [
        "Leasing", "Cheques", "Recognition", "Termination", "Settlement", "Vouchers", "Cutover",
    ] as const) {
        it(`${ns}: every en.json key has a distinct ar.json translation`, () => {
            const enNs = flatten((en as Record<string, unknown>)[ns]);
            const arNs = flatten((ar as Record<string, unknown>)[ns]);

            const enKeys = Object.keys(enNs);
            expect(enKeys.length).toBeGreaterThan(0);

            expect(enKeys.filter((k) => !(k in arNs)), "keys absent from ar.json").toEqual([]);
            expect(Object.keys(arNs).filter((k) => !(k in enNs)), "keys absent from en.json").toEqual([]);
            expect(
                enKeys.filter((k) => arNs[k] === enNs[k] && !SAME_IN_BOTH_LOCALES.has(`${ns}.${k}`)),
                "keys still holding English text",
            ).toEqual([]);
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

/**
 * The other half of parity: a key that no source file names.
 *
 * Plan 4 left fourteen of them across `Vouchers` and `Cutover` — copy from
 * before the bulk post existed ("Posting a batch to the ledger … is not
 * available yet"), from before a REVERSED batch could be posted again, from
 * screens that were merged into others. Nothing fails on a dead key: it is
 * twenty-eight lines of translated English that quietly stop being true, and
 * the next person to grep for the copy on screen finds two candidates.
 *
 * Whole-word over `src/`, tests excluded — a key mentioned only by the test that
 * asserts it exists is still dead. The allowlist below is for keys composed at
 * the point of use, which a literal search cannot see; each entry names the line
 * that builds it, so an entry added without one is visible in review.
 */
const COMPOSED_AT_USE = new Map([
    // import-batches/page.tsx: t(`outcome${r.outcome}`) over LeaseOutcomeStatus.
    ["Cutover.outcomePOSTED", "t(`outcome${r.outcome}`)"],
    ["Cutover.outcomeSKIPPED_ALREADY_POSTED", "t(`outcome${r.outcome}`)"],
    ["Cutover.outcomeFAILED", "t(`outcome${r.outcome}`)"],
]);

/** Every non-test source file, read once. */
function sourceText(): string {
    const root = join(__dirname, "..", "..");
    const out: string[] = [];
    const walk = (dir: string) => {
        for (const e of readdirSync(dir, { withFileTypes: true })) {
            const full = join(dir, e.name);
            if (e.isDirectory()) {
                if (e.name !== "__tests__") walk(full);
            } else if (/\.tsx?$/.test(e.name) && !/\.test\.tsx?$/.test(e.name)) {
                out.push(readFileSync(full, "utf8"));
            }
        }
    };
    walk(root);
    return out.join("\n");
}

describe("accounting i18n: nothing in messages that nothing renders", () => {
    const blob = sourceText();

    for (const ns of ["Vouchers", "Cutover"] as const) {
        it(`${ns}: every key is named by a source file`, () => {
            const keys = Object.keys(flatten((en as Record<string, unknown>)[ns]));
            expect(keys.length).toBeGreaterThan(0);
            const dead = keys.filter(
                k =>
                    !COMPOSED_AT_USE.has(`${ns}.${k}`)
                    && !new RegExp(`\\b${k.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}\\b`).test(blob),
            );
            expect(dead, "keys in messages that no source file names").toEqual([]);
        });
    }
});
