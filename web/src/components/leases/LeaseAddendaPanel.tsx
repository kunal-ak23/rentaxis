"use client";

import { useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { Pencil } from "lucide-react";
import { fmtAmount } from "@/lib/api/ledger";
import { fmtIsoDate } from "@/components/leases/leaseMath";
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
    const locale = useLocale();
    const [drafts, setDrafts] = useState<Record<string, string>>({});
    // Which already-recorded rows (PATCH /addenda/{id}/ejari accepts a new
    // value) are open for editing — the number stays read-only until then.
    const [editing, setEditing] = useState<Record<string, boolean>>({});
    const [error, setError] = useState<string | null>(null);

    const save = async (a: LeaseAddendum) => {
        setError(null);
        try {
            await leaseApi.recordAddendumEjari(leaseId, a.id, (drafts[a.id] ?? "").trim());
            setEditing(d => ({ ...d, [a.id]: false }));
            onChanged();
        } catch (e) {
            setError(e instanceof ApiError ? e.message : t("recordEjariFailed"));
        }
    };

    const startEdit = (a: LeaseAddendum) => {
        setDrafts(d => ({ ...d, [a.id]: a.ejariNumber ?? "" }));
        setEditing(d => ({ ...d, [a.id]: true }));
    };

    return (
        <section data-testid="lease-addenda" className="bg-surface border border-border rounded-xl overflow-x-auto">
            <h3 className="px-3 py-2 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("addenda")}</h3>
            {addenda.length === 0 ? (
                <p className="px-3 pb-3 text-xs text-muted">{t("noAddenda")}</p>
            ) : (
                <table className="w-full min-w-[640px]">
                    <thead>
                        <tr className="border-t border-border">
                            <th className={`${td} text-start font-semibold text-muted uppercase tracking-wider`}>{t("addendumNo")}</th>
                            <th className={`${td} text-start font-semibold text-muted uppercase tracking-wider`}>{t("effectiveFrom")}</th>
                            <th className={`${td} text-start font-semibold text-muted uppercase tracking-wider`}>{t("addendumReason")}</th>
                            <th className={`${td} text-end font-semibold text-muted uppercase tracking-wider`}>{t("addendumValue")}</th>
                            <th className={`${td} text-start font-semibold text-muted uppercase tracking-wider`}>{t("addendumEntry")}</th>
                            <th className={`${td} text-start font-semibold text-muted uppercase tracking-wider`}>{t("addendumEjari")}</th>
                        </tr>
                    </thead>
                    <tbody>
                        {addenda.map(a => (
                            <tr key={a.id} className="border-t border-border">
                                <td className={`${td} font-semibold`}>{a.addendumNumber}</td>
                                <td className={td}>{fmtIsoDate(a.effectiveFrom, locale)}</td>
                                <td className={td}>{a.reason ?? ""}</td>
                                <td className={`${td} text-end tabular-nums`}>{fmtAmount(a.value)}</td>
                                <td className={td}>
                                    {a.tcoEntryNumber}
                                    {a.superseded && (
                                        <span className="ms-2 inline-block px-2 py-0.5 rounded-full bg-input text-muted text-[10px] font-semibold">
                                            {t("addendumSuperseded")}
                                        </span>
                                    )}
                                </td>
                                <td className={td}>
                                    {a.ejariPending ? (
                                        <span className="inline-flex items-center gap-2">
                                            <span className="px-2 py-0.5 rounded-full bg-warning/15 text-warning text-[10px] font-semibold">
                                                {t("ejariPending")}
                                            </span>
                                            {canRecordEjari && !a.superseded && (
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
                                    ) : editing[a.id] ? (
                                        <span className="inline-flex items-center gap-2">
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
                                            <button
                                                type="button"
                                                data-testid={`addendum-ejari-cancel-${a.id}`}
                                                onClick={() => setEditing(d => ({ ...d, [a.id]: false }))}
                                                className="px-2 py-1 rounded-md text-[11px] font-semibold text-muted cursor-pointer"
                                            >
                                                {t("cancel")}
                                            </button>
                                        </span>
                                    ) : (
                                        <span className="inline-flex items-center gap-2">
                                            {a.ejariNumber}
                                            {canRecordEjari && !a.superseded && (
                                                <button
                                                    type="button"
                                                    data-testid={`addendum-ejari-edit-${a.id}`}
                                                    aria-label={`${t("editEjari")} ${a.addendumNumber}`}
                                                    onClick={() => startEdit(a)}
                                                    className="text-muted hover:text-foreground cursor-pointer"
                                                >
                                                    <Pencil size={12} />
                                                </button>
                                            )}
                                        </span>
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
