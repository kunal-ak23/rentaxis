"use client";

import { useState } from "react";
import { codedOf, serverText } from "@/components/finance/bankrec/serverText";

/**
 * F14-20: clear, receive, clear-batch, an issued cheque's present, a voucher's
 * post/amend and a payment run's post can all be refused with
 * `bank.statementCovers` — a statement already imported for that account
 * covers the entry's date. The server accepts `notOnStatement: true` to go
 * ahead anyway (the entry genuinely isn't on that statement — an error, or a
 * transaction the bank never reported).
 *
 * This hook is the one place that catches that refusal, shows its translated
 * message plus a confirm checkbox, and remembers whether the user ticked it
 * so the caller's next submit sends the flag. `tCommon` is `useTranslations
 * ("Common")` — the codes live under `Common.errors`, read with the same
 * `codedOf`/`serverText` helpers bank-rec's own dialogs use.
 */
type T = {
    (key: string, values?: Record<string, string | number>): string;
    has: (key: string) => boolean;
};

export function useStatementCoverGuard(tCommon: T) {
    const [notice, setNotice] = useState<string | null>(null);
    const [notOnStatement, setNotOnStatement] = useState(false);

    /** Catches the refusal and shows its notice; returns true if it did. */
    const catchStatementCover = (err: unknown): boolean => {
        const c = codedOf(err);
        if (c.code === "bank.statementCovers") {
            setNotice(serverText(tCommon, err));
            return true;
        }
        return false;
    };

    const reset = () => {
        setNotice(null);
        setNotOnStatement(false);
    };

    return { notice, notOnStatement, setNotOnStatement, catchStatementCover, reset };
}
