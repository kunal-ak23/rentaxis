"use client";

import { useEffect, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { Link } from "@/i18n/routing";
import { Pagination } from "@/components/ui/Pagination";
import { fmtIsoDate } from "@/components/leases/leaseMath";
import { fmtAmount } from "@/lib/api/ledger";
import { chequeApi, type Cheque } from "@/lib/api/leasing";

/** Overdue scans the due list this many rows at a time. */
export const OVERDUE_SCAN_SIZE = 100;

const th = "text-start px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider whitespace-nowrap";
const td = "px-4 py-3 text-xs text-foreground";

type Props = { overdueOnly: boolean; propertyId?: string };

/**
 * Due and Overdue tabs of the Collection hub — a read view over
 * `GET /cheques/due` (matured, unpaid, oldest first). The cheque actions stay
 * on the register, which each row's contract and cheque number link to.
 *
 * Due pages on the server. Overdue has no server filter (the `overdue` flag is
 * worked out per row from the contract's grace days), so it scans the due list
 * a page at a time and says how far it has looked — never a silent cap.
 */
export default function DueChequesPanel({ overdueOnly, propertyId }: Props) {
    return overdueOnly ? <OverdueList propertyId={propertyId} /> : <DueList propertyId={propertyId} />;
}

function DueList({ propertyId }: { propertyId?: string }) {
    const t = useTranslations("Collections");
    const [pageIndex, setPageIndex] = useState(0);
    const [size, setSize] = useState(25);
    const [state, setState] = useState<{ rows: Cheque[]; total: number } | null>(null);
    const [failed, setFailed] = useState(false);

    useEffect(() => {
        let alive = true;
        chequeApi.due({ propertyId: propertyId || undefined, page: pageIndex, size })
            .then(p => { if (alive) { setState({ rows: p.content ?? [], total: p.totalElements ?? 0 }); setFailed(false); } })
            .catch(() => { if (alive) setFailed(true); });
        return () => { alive = false; };
    }, [propertyId, pageIndex, size]);

    if (failed) return <p className="text-xs text-error" role="alert">{t("loadFailed")}</p>;
    if (state === null) return <Skeleton />;
    if (state.rows.length === 0) return <p className="text-sm text-muted py-10 text-center">{t("empty")}</p>;
    return (
        <div className="bg-surface border border-border rounded-xl" data-testid="collections-due">
            <ChequeTable rows={state.rows} overdue={false} />
            <div className="px-3">
                <Pagination currentPage={pageIndex + 1} totalItems={state.total} itemsPerPage={size}
                    onPageChange={p => setPageIndex(p - 1)} onItemsPerPageChange={n => { setSize(n); setPageIndex(0); }} />
            </div>
        </div>
    );
}

function OverdueList({ propertyId }: { propertyId?: string }) {
    const t = useTranslations("Collections");
    const [rows, setRows] = useState<Cheque[] | null>(null);
    const [scanned, setScanned] = useState(0);
    const [total, setTotal] = useState(0);
    const [next, setNext] = useState(0);
    const [busy, setBusy] = useState(false);
    const [failed, setFailed] = useState(false);

    const scan = async (page: number, into: Cheque[]) => {
        setBusy(true);
        try {
            const p = await chequeApi.due({ propertyId: propertyId || undefined, page, size: OVERDUE_SCAN_SIZE });
            const content = p.content ?? [];
            setRows([...into, ...content.filter(c => c.overdue)]);
            setScanned(page * OVERDUE_SCAN_SIZE + content.length);
            setTotal(p.totalElements ?? 0);
            setNext(page + 1);
            setFailed(false);
        } catch {
            setFailed(true);
        } finally {
            setBusy(false);
        }
    };

    useEffect(() => {
        setRows(null);
        void scan(0, []);
        // eslint-disable-next-line react-hooks/exhaustive-deps -- a new property starts a new scan
    }, [propertyId]);

    if (failed && rows === null) return <p className="text-xs text-error" role="alert">{t("loadFailed")}</p>;
    if (rows === null) return <Skeleton />;
    const more = scanned < total;
    return (
        <div className="bg-surface border border-border rounded-xl" data-testid="collections-overdue">
            {rows.length === 0 && !more
                ? <p className="text-sm text-muted py-10 text-center">{t("empty")}</p>
                : <ChequeTable rows={rows} overdue />}
            <div className="flex flex-wrap items-center justify-between gap-3 px-4 py-3 border-t border-border">
                <p className="text-[11px] text-muted" data-testid="overdue-scanned">
                    {t("overdueScanned", { shown: rows.length, scanned, total })}
                </p>
                {more && (
                    <button type="button" data-testid="overdue-load-more" disabled={busy} onClick={() => scan(next, rows)}
                        className="px-3 py-1.5 rounded-lg border border-border text-xs font-semibold hover:bg-input/40 disabled:opacity-50 cursor-pointer">
                        {t("loadMore")}
                    </button>
                )}
            </div>
            {failed && <p className="px-4 pb-3 text-xs text-error" role="alert">{t("loadFailed")}</p>}
        </div>
    );
}

function ChequeTable({ rows, overdue }: { rows: Cheque[]; overdue: boolean }) {
    const t = useTranslations("Collections");
    const locale = useLocale();
    return (
        <div className="overflow-x-auto">
            <table className="w-full min-w-[640px]">
                <thead><tr className="bg-input/50">
                    <th className={th}>{t("colUnit")}</th>
                    <th className={th}>{t("colTenant")}</th>
                    <th className={th}>{t("colCheque")}</th>
                    <th className={th}>{t("colDueDate")}</th>
                    <th className={`${th} text-end`}>{t("colAmount")}</th>
                    {overdue && <th className={`${th} text-end`}>{t("colDaysOverdue")}</th>}
                </tr></thead>
                <tbody>
                    {rows.map(c => (
                        <tr key={c.id} data-testid={`due-row-${c.id}`} className="border-t border-border hover:bg-input/30">
                            <td className={td}>
                                <Link href={`/dashboard/leases/${c.leaseId}`} className="text-primary hover:underline">{c.unitIdentifier ?? "—"}</Link>
                            </td>
                            <td className={td}>{c.renterName ?? "—"}</td>
                            <td className={td}>
                                {c.chequeNumber ? (
                                    <Link href={`/dashboard/collections?tab=all&search=${encodeURIComponent(c.chequeNumber)}`} className="text-primary hover:underline">
                                        <bdi dir="ltr">{c.chequeNumber}</bdi>
                                    </Link>
                                ) : `#${c.seqNo}`}
                            </td>
                            <td className={`${td} tabular-nums`}>{fmtIsoDate(c.chequeDate ?? c.postingDate, locale)}</td>
                            <td className={`${td} text-end tabular-nums`}>{fmtAmount(c.amount)}</td>
                            {overdue && <td className={`${td} text-end tabular-nums text-error`}>{c.daysOverdue}</td>}
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    );
}

function Skeleton() {
    return (
        <div className="space-y-3 animate-pulse">
            {[1, 2, 3].map(i => <div key={i} className="bg-input rounded-xl h-14" />)}
        </div>
    );
}
