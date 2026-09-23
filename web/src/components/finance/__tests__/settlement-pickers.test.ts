import { describe, expect, it } from "vitest";
import * as fs from "node:fs";
import * as path from "node:path";

/**
 * Every debit-account field on a cheque goes through `SettlementAccountPicker`.
 *
 * The per-dialog tests prove one picker's options; this one guards the set,
 * because the defect is an *omission* — a new dialog written with the plain
 * `<AccountPicker leafOnly />` the other seven used would offer income,
 * liability and expense leaves for a field `ChequeService.requireSettlementAccount`
 * (ChequeService.java:1083-1093) 400s, and no render test anywhere would
 * notice. That is exactly how all seven came to be wrong at once.
 */

const SRC = path.join(__dirname, "..", "..", "..");

/** The files that own a cheque's debit account. */
const CHEQUE_FORMS = [
    "components/leases/ChequeGrid.tsx",
    "components/cheques/ChequeRowsEditor.tsx",
    "components/cheques/ChequeActionDialog.tsx",
    "components/cheques/ReplaceChequeDialog.tsx",
    "components/cheques/ReceiveCashDialog.tsx",
    "components/cheques/DepositBatchDialog.tsx",
    "components/cheques/BounceChequeDialog.tsx",
];

describe("cheque debit-account pickers", () => {
    it("never renders a bare AccountPicker", () => {
        const offenders: string[] = [];
        for (const rel of CHEQUE_FORMS) {
            const source = fs.readFileSync(path.join(SRC, rel), "utf8");
            if (/<AccountPicker\b/.test(source)) offenders.push(rel);
        }
        expect(offenders).toEqual([]);
    });

    it("has a settlement picker in every cheque form that takes a debit account", () => {
        // BounceChequeDialog is the exception on purpose: `ChequeService.bounce`
        // never reads a debit account, so the field is gone rather than filtered.
        const withoutPicker = CHEQUE_FORMS.filter(rel => {
            const source = fs.readFileSync(path.join(SRC, rel), "utf8");
            return !/<SettlementAccountPicker\b/.test(source);
        });
        expect(withoutPicker).toEqual(["components/cheques/BounceChequeDialog.tsx"]);
    });
});
