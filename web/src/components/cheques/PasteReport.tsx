"use client";

import { useState, type FormEvent } from "react";
import { useTranslations } from "next-intl";
import type { GridField, PasteOutcome, PasteRequest } from "./chequeGridKeys";

const FIELD_LABEL: Record<GridField, string> = {
    postingDate: "postingDate", chequeNumber: "chequeNo", chequeDate: "chequeDate", payeeBank: "payeeBank",
    debitAccountId: "debitAccount", amount: "amount", vatAmount: "vatColumn", narration: "narration", mode: "chequeMode",
};

type Report = Omit<PasteOutcome<unknown>, "rows"> & { request: PasteRequest };

/**
 * A grid's record of its last paste: what could not be written (highlighted
 * until edited) and what fell outside the grid. Nothing a paste drops goes
 * unsaid (PR #365 R1).
 */
export function usePasteReport() {
    const [report, setReport] = useState<Report | null>(null);
    const [bad, setBad] = useState<Set<string>>(new Set());
    return {
        report,
        isBad: (row: number, field: GridField) => bad.has(`${row}:${field}`),
        onPasted: (o: PasteOutcome<unknown>, request: PasteRequest) => {
            const quiet = o.issues.length === 0 && o.extraColumns === 0 && o.extraRows === 0 && o.addedRows === 0;
            setReport(quiet ? null : { issues: o.issues, extraColumns: o.extraColumns, extraRows: o.extraRows, addedRows: o.addedRows, request });
            setBad(new Set(o.issues.map(i => `${i.row}:${i.field}`)));
        },
        /** `onInput` on the table: an edited cell is no longer flagged. */
        clearEdited: (e: FormEvent<HTMLElement>) => {
            const el = e.target as HTMLElement;
            const field = (el.closest("[data-grid-field]") as HTMLElement | null)?.dataset.gridField;
            const row = (el.closest("[data-grid-row]") as HTMLElement | null)?.dataset.gridRow;
            if (field && row !== undefined && bad.has(`${row}:${field}`)) {
                setBad(prev => { const n = new Set(prev); n.delete(`${row}:${field}`); return n; });
            }
        },
        dismiss: () => { setReport(null); setBad(new Set()); },
    };
}

export default function PasteReport({ report, onDismiss, onAddRows, testId }: {
    report: Report | null; onDismiss: () => void; onAddRows?: (request: PasteRequest) => void; testId: string;
}) {
    const t = useTranslations("Cheques");
    const tl = useTranslations("Leasing");
    if (!report) return null;
    const list = report.issues.map(i => t("pasteCell", { row: i.row + 1, field: tl(FIELD_LABEL[i.field]), raw: i.raw })).join("; ");
    return (
        <div role="status" data-testid={testId} className="px-4 py-2 text-[11px] bg-warning/10 border-b border-warning/20 text-foreground flex flex-wrap items-center gap-x-3 gap-y-1">
            {report.issues.length > 0 && <span data-testid={`${testId}-cells`}>{t("pasteBadCells", { n: report.issues.length, list })}</span>}
            {report.extraColumns > 0 && <span data-testid={`${testId}-columns`}>{t("pasteExtraColumns", { n: report.extraColumns })}</span>}
            {report.addedRows > 0 && <span data-testid={`${testId}-added`}>{t("pasteAddedRows", { n: report.addedRows })}</span>}
            {report.extraRows > 0 && (
                <span data-testid={`${testId}-rows`}>
                    {t("pasteExtraRows", { n: report.extraRows })}
                    {onAddRows && (
                        <button type="button" data-testid={`${testId}-add-rows`} onClick={() => onAddRows(report.request)}
                            className="ms-2 px-2 py-0.5 rounded-md border border-border font-semibold hover:bg-input/40 cursor-pointer">
                            {t("pasteAddRows", { n: report.extraRows })}
                        </button>
                    )}
                </span>
            )}
            <button type="button" onClick={onDismiss} className="ms-auto text-muted hover:text-foreground underline cursor-pointer">{t("pasteDismiss")}</button>
        </div>
    );
}
