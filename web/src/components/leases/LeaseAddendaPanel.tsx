"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { fmtAmount } from "@/lib/api/ledger";
import { ApiError, leaseApi, type LeaseAddendum } from "@/lib/api/leasing";

/**
 * The addenda on a lease, oldest first. An addendum with no Ejari is flagged
 * pending — that flag is the whole of the Ejari follow-up — and finance can
 * record the number here once the variation is re-registered.
 */

const td = "px-3 py-2 text-xs";

type Props = {
    leaseId: string;
    addenda: LeaseAddendum[];
    canRecordEjari: boolean;
    onChanged: () => void;
};

export default function LeaseAddendaPanel({ leaseId, addenda, canRecordEjari, onChanged }: Props) {
    const t = useTranslations("Leasing");
    const [drafts, setDrafts] = useState<Record<string, string>>({});
    const [error, setError] = useState<string | null>(null);

    const save = async (a: LeaseAddendum) => {
        setError(null);
        try {
            await leaseApi.recordAddendumEjari(leaseId, a.id, (drafts[a.id] ?? "").trim());
            onChanged();
        } catch (e) {
            setError(e instanceof ApiError ? e.message : t("recordEjariFailed"));
        }
    };

    return (
        <section data-testid="lease-addenda" className="bg-surface border border-border rounded-xl overflow-x-auto">
            <h3 className="px-3 py-2 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("addenda")}</h3>
            {addenda.length === 0 ? (
                <p className="px-3 pb-3 text-xs text-muted">{t("noAddenda")}</p>
            ) : (
                <table className="w-full min-w-[640px]">
                    <tbody>
                        {addenda.map(a => (
                            <tr key={a.id} className="border-t border-border">
                                <td className={`${td} font-semibold`}>{a.addendumNumber}</td>
                                <td className={td}>{a.effectiveFrom}</td>
                                <td className={td}>{a.reason ?? ""}</td>
                                <td className={`${td} text-end tabular-nums`}>{fmtAmount(a.value)}</td>
                                <td className={td}>{a.tcoEntryNumber}</td>
                                <td className={td}>
                                    {a.ejariPending ? (
                                        <span className="inline-flex items-center gap-2">
                                            <span className="px-2 py-0.5 rounded-full bg-warning/15 text-warning text-[10px] font-semibold">
                                                {t("ejariPending")}
                                            </span>
                                            {canRecordEjari && (
                                                <>
                                                    <input
                                                        data-testid={`addendum-ejari-${a.id}`}
                                                        aria-label={`${t("recordEjari")} ${a.addendumNumber}`}
                                                        className="bg-input border border-border rounded-md px-2 py-1 text-xs"
                                                        value={drafts[a.id] ?? ""}
                                                        onChange={e => setDrafts(d => ({ ...d, [a.id]: e.target.value }))}
                                                    />
                                                    <button
                                                        type="button"
                                                        data-testid={`addendum-ejari-save-${a.id}`}
                                                        disabled={!(drafts[a.id] ?? "").trim()}
                                                        onClick={() => save(a)}
                                                        className="px-2 py-1 rounded-md text-[11px] font-semibold border border-border disabled:opacity-50 cursor-pointer"
                                                    >
                                                        {t("recordEjari")}
                                                    </button>
                                                </>
                                            )}
                                        </span>
                                    ) : (
                                        a.ejariNumber
                                    )}
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            )}
            {error && <p className="px-3 pb-3 text-[11px] text-error">{error}</p>}
        </section>
    );
}
