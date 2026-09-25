"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { X } from "lucide-react";
import { fmtAmount } from "@/lib/api/ledger";
import { vatReturnsApi, type VatDocument, type VatReturn } from "@/lib/api/vatReturns";

const th = "px-3 py-2.5 text-[11px] font-semibold text-muted uppercase tracking-wider whitespace-nowrap";
const td = "px-3 py-2 text-xs";
const num = `${td} text-end tabular-nums whitespace-nowrap`;

/**
 * #55: the return's boxes in FTA VAT 201 order. A box with documents opens them
 * (tax invoices / credit notes, rent entries, purchase invoices and bank charges).
 * RTL: logical start / end; amounts in `<bdi dir="ltr">`.
 */
export default function VatReturnView({ data, locale }: { data: VatReturn; locale: string }) {
    const t = useTranslations("VatReturn");
    const [open, setOpen] = useState<{ code: string; docs: VatDocument[] | null } | null>(null);
    const ar = locale === "ar";

    const drill = async (code: string) => {
        setOpen({ code, docs: null });
        try {
            setOpen({ code, docs: await vatReturnsApi.documents(data.periodStart, code) });
        } catch {
            setOpen({ code, docs: [] });
        }
    };
    const money = (v: number | null) => (v == null ? "" : fmtAmount(v));

    return (
        <>
            <div className="bg-surface rounded-xl border border-border shadow-sm overflow-x-auto">
                <table className="w-full text-start" data-testid="vat-boxes">
                    <thead className="bg-input/50 border-b border-border">
                        <tr>
                            <th className={`${th} text-start`}>{t("box")}</th>
                            <th className={`${th} text-start`}>{t("description")}</th>
                            <th className={`${th} text-end`}>{t("amount")}</th>
                            <th className={`${th} text-end`}>{t("vat")}</th>
                        </tr>
                    </thead>
                    <tbody>
                        {data.boxes.map(b => (
                            <tr key={b.code} data-testid={`vat-box-${b.code}`}
                                className={`border-b border-border/50 ${b.total ? "font-bold bg-input/20" : ""}`}>
                                <td className={td}>{b.code}</td>
                                <td className={td}>
                                    {b.documents > 0 ? (
                                        <button type="button" onClick={() => drill(b.code)} data-testid={`vat-drill-${b.code}`}
                                                className="text-primary hover:underline cursor-pointer text-start">
                                            {t(`boxes.${b.key.replace(".", "_")}`)} <span className="text-muted">({b.documents})</span>
                                        </button>
                                    ) : t(`boxes.${b.key.replace(".", "_")}`)}
                                </td>
                                <td className={num}><bdi dir="ltr">{money(b.amount)}</bdi></td>
                                <td className={num}><bdi dir="ltr">{money(b.vat)}</bdi></td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>

            {open && (
                <div className="fixed inset-0 z-50 flex justify-end bg-black/30" onClick={() => setOpen(null)}>
                    <div className="w-full max-w-2xl h-full bg-surface overflow-y-auto p-5" onClick={e => e.stopPropagation()}
                         data-testid="vat-drill">
                        <div className="flex items-center justify-between mb-4">
                            <h2 className="text-sm font-bold">{t("documentsOf", { box: open.code })}</h2>
                            <button type="button" onClick={() => setOpen(null)} aria-label={t("close")}><X size={16} /></button>
                        </div>
                        {open.docs === null ? (
                            <div className="animate-pulse bg-input rounded-xl h-24" />
                        ) : open.docs.length === 0 ? (
                            <p className="text-xs text-muted">{t("noDocuments")}</p>
                        ) : (
                            <table className="w-full text-start">
                                <thead className="border-b border-border">
                                    <tr>
                                        <th className={`${th} text-start`}>{t("document")}</th>
                                        <th className={`${th} text-start`}>{t("date")}</th>
                                        <th className={`${th} text-start`}>{t("party")}</th>
                                        <th className={`${th} text-end`}>{t("amount")}</th>
                                        <th className={`${th} text-end`}>{t("vat")}</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {open.docs.map(d => (
                                        <tr key={`${d.id}-${d.number}`} className="border-b border-border/50">
                                            <td className={td}>
                                                <bdi dir="ltr">{d.number}</bdi>
                                                {d.kind === "NO_TAX_INVOICE" && <span className="ms-1 text-warning">({t("noTaxInvoice")})</span>}
                                                {d.kind === "CREDIT_NOTE" && <span className="ms-1 text-muted">({t("creditNote")})</span>}
                                            </td>
                                            <td className={td}><bdi dir="ltr">{d.date}</bdi></td>
                                            <td className={td}>{ar && d.partyAr ? d.partyAr : d.party ?? ""}</td>
                                            <td className={num}><bdi dir="ltr">{money(d.amount)}</bdi></td>
                                            <td className={num}><bdi dir="ltr">{money(d.vat)}</bdi></td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        )}
                    </div>
                </div>
            )}
        </>
    );
}
