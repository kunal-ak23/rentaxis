"use client";

import { useLocale, useTranslations } from "next-intl";
import { cn } from "@/lib/utils";
import { fmtAmount } from "@/lib/api/ledger";
import type { Cheque } from "@/lib/api/leasing";
import { fmtIsoDate } from "./leaseMath";
import type { ChequeDecisions } from "./terminationMath";

/**
 * The register, as the termination screen has to ask about it (spec §9.1).
 *
 * Two tables, because the server draws the line in exactly this place:
 *
 *  - **uncleared** rows (REGISTERED / DEPOSITED / ONLINE_PENDING) each need a
 *    decision, and `TerminateLeaseRequest` refuses a body that leaves one out
 *    of both lists — "Every uncleared cheque must be either returned or kept".
 *    So the toggle is per row and there is no third state.
 *  - **bounced** rows are read-only. `ChequeStatus.isUncleared()` excludes
 *    BOUNCED deliberately (its CBR already put the debt back on the
 *    receivable), so sending one in either list is refused with "These cheques
 *    are not uncleared rows of this lease". Offering a toggle here would be
 *    offering an action the server always refuses — they are listed rather than
 *    buried because the money is still owed.
 */

const th = "text-start px-3 py-2 text-[11px] font-semibold text-muted uppercase tracking-wider";
const td = "px-3 py-1.5 text-xs";

type Props = {
    rows: Cheque[];
    bounced: Cheque[];
    decisions: ChequeDecisions;
    onChange: (id: string, decision: "RETURN" | "KEEP") => void;
    /** Read-only for a role that may price a termination but not perform one. */
    disabled?: boolean;
};

export default function ChequeReturnTable({ rows, bounced, decisions, onChange, disabled }: Props) {
    const t = useTranslations("Termination");
    // `mode` is a Java enum, and every other screen renders it through
    // `Leasing.mode.*` — on the Arabic termination table these were the only
    // Latin tokens in the grid.
    const tLeasing = useTranslations("Leasing");
    const locale = useLocale();

    return (
        <div className="space-y-4">
            <div className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm">
                <div className="px-4 py-3 border-b border-border">
                    <h3 className="text-xs font-semibold text-muted uppercase tracking-wider">{t("uncleared")}</h3>
                    <p className="text-[11px] text-muted mt-1">{t("unclearedHint")}</p>
                </div>
                <div className="overflow-x-auto">
                    <table className="w-full min-w-[640px]">
                        <thead>
                            <tr className="bg-input/50">
                                <th className={th}>{t("seq")}</th>
                                <th className={th}>{t("chequeNo")}</th>
                                <th className={th}>{t("chequeDate")}</th>
                                <th className={th}>{t("mode")}</th>
                                <th className={`${th} text-end`}>{t("amount")}</th>
                                <th className={th}>{t("decision")}</th>
                            </tr>
                        </thead>
                        <tbody>
                            {rows.map(c => {
                                const decision = decisions[c.id] === "RETURN" ? "RETURN" : "KEEP";
                                return (
                                    <tr key={c.id} data-testid={`terminate-row-${c.id}`} className="border-t border-border">
                                        <td className={`${td} tabular-nums`}>{c.seqNo}</td>
                                        <td className={td}>{c.chequeNumber || "—"}</td>
                                        <td className={td}>{fmtIsoDate(c.chequeDate, locale)}</td>
                                        <td className={td}>{tLeasing(`mode.${c.mode}`)}</td>
                                        <td className={`${td} text-end tabular-nums`}>{fmtAmount(c.amount)}</td>
                                        <td className={td}>
                                            <div
                                                className="inline-flex rounded-lg border border-border overflow-hidden"
                                                data-testid={`terminate-decision-${c.id}`}
                                                data-decision={decision}
                                            >
                                                <button
                                                    type="button"
                                                    data-testid={`terminate-return-${c.id}`}
                                                    aria-pressed={decision === "RETURN"}
                                                    disabled={disabled}
                                                    onClick={() => onChange(c.id, "RETURN")}
                                                    className={cn(
                                                        "px-2.5 py-1 text-[11px] font-semibold transition-colors cursor-pointer disabled:cursor-not-allowed disabled:opacity-60",
                                                        decision === "RETURN"
                                                            ? "bg-primary text-primary-foreground"
                                                            : "bg-surface text-muted hover:bg-input",
                                                    )}
                                                >
                                                    {t("return")}
                                                </button>
                                                <button
                                                    type="button"
                                                    data-testid={`terminate-keep-${c.id}`}
                                                    aria-pressed={decision === "KEEP"}
                                                    disabled={disabled}
                                                    onClick={() => onChange(c.id, "KEEP")}
                                                    className={cn(
                                                        "px-2.5 py-1 text-[11px] font-semibold border-s border-border transition-colors cursor-pointer disabled:cursor-not-allowed disabled:opacity-60",
                                                        decision === "KEEP"
                                                            ? "bg-primary text-primary-foreground"
                                                            : "bg-surface text-muted hover:bg-input",
                                                    )}
                                                >
                                                    {t("keep")}
                                                </button>
                                            </div>
                                        </td>
                                    </tr>
                                );
                            })}
                            {rows.length === 0 && (
                                <tr>
                                    <td className={`${td} text-muted text-center py-6`} colSpan={6}>
                                        {t("noUncleared")}
                                    </td>
                                </tr>
                            )}
                        </tbody>
                    </table>
                </div>
            </div>

            {bounced.length > 0 && (
                <div className="bg-surface border border-warning/30 rounded-xl overflow-hidden shadow-sm">
                    <div className="px-4 py-3 border-b border-warning/20">
                        <h3 className="text-xs font-semibold text-warning uppercase tracking-wider">{t("bounced")}</h3>
                        <p className="text-[11px] text-muted mt-1">{t("bouncedHint")}</p>
                    </div>
                    <div className="overflow-x-auto">
                        <table className="w-full min-w-[520px]">
                            <thead>
                                <tr className="bg-input/50">
                                    <th className={th}>{t("seq")}</th>
                                    <th className={th}>{t("chequeNo")}</th>
                                    <th className={th}>{t("chequeDate")}</th>
                                    <th className={`${th} text-end`}>{t("amount")}</th>
                                </tr>
                            </thead>
                            <tbody>
                                {bounced.map(c => (
                                    <tr key={c.id} data-testid={`terminate-bounced-${c.id}`} className="border-t border-border">
                                        <td className={`${td} tabular-nums`}>{c.seqNo}</td>
                                        <td className={td}>{c.chequeNumber || "—"}</td>
                                        <td className={td}>{fmtIsoDate(c.chequeDate, locale)}</td>
                                        <td className={`${td} text-end tabular-nums`}>{fmtAmount(c.amount)}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                </div>
            )}
        </div>
    );
}
