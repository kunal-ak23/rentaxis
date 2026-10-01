"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import LeaseDialog from "@/components/leases/LeaseDialog";
import BulkChequeUploadFlow from "@/components/cheques/BulkChequeUploadFlow";
import { leaseIsCollectable } from "@/components/cheques/registerActions";
import { leaseApi, type Cheque, type LeaseDetail, type LeaseStatus } from "@/lib/api/leasing";

/**
 * "Scan cheques" from outside a contract (the Cheque / Cash Collection hub and
 * the register's own rows).
 *
 * Restores the scan the pre-v2 Payments screen had on its collect dialog. The
 * scan itself is `BulkChequeUploadFlow`, which attaches to a contract's own
 * rows, so this only finds the contract: picked by search, or handed in by a
 * register row (`leaseId` + `chequeId`, which narrows the flow to that one
 * cheque). A contract the register can no longer move (closed) or one with no
 * post-dated row waiting is not offered; a draft contract is, since its grid
 * takes scans too.
 */
type Props = {
    open: boolean;
    /** Skip the search: the contract is known (a register row's own "Attach scan"). */
    leaseId?: string | null;
    /** Attach to this one cheque only. */
    chequeId?: string | null;
    /** The hub's property filter: the search stays inside it. */
    propertyId?: string | null;
    onClose: () => void;
    onDone: () => void;
};

const field = "w-full bg-surface border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";

/** A contract a scan can land on: drafted, or on the books and still collectable. */
const scannableLease = (l: Pick<LeaseDetail, "status">) => l.status === "DRAFT" || leaseIsCollectable(l.status);
/** What the server drops before it pages (PR #396 review P3-2): the rest are filtered again client-side. */
const NOT_SCANNABLE: LeaseStatus[] = ["CLOSED", "PENDING_SIGNATURE"];
const PAGE = 10;

export default function ScanChequesLauncher({ open, leaseId, chequeId, propertyId, onClose, onDone }: Props) {
    const t = useTranslations("Cheques");
    const tl = useTranslations("Leasing");
    const [query, setQuery] = useState("");
    const [results, setResults] = useState<LeaseDetail[]>([]);
    const [page, setPage] = useState(0);
    const [hasMore, setHasMore] = useState(false);
    const [searching, setSearching] = useState(false);
    const [picked, setPicked] = useState<LeaseDetail | null>(null);
    const [target, setTarget] = useState<{ leaseId: string; rows: Cheque[] } | null>(null);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [loading, setLoading] = useState(false);

    useEffect(() => {
        if (!open) {
            setQuery("");
            setResults([]);
            setPicked(null);
            setTarget(null);
            setLoadError(null);
        }
    }, [open]);

    const loadRows = async (id: string) => {
        setLoading(true);
        setLoadError(null);
        try {
            setTarget({ leaseId: id, rows: await leaseApi.cheques(id) });
        } catch {
            setLoadError(t("scanLoadFailed"));
        } finally {
            setLoading(false);
        }
    };

    // A row hands its contract in: straight to the scan.
    useEffect(() => {
        if (open && leaseId) void loadRows(leaseId);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [open, leaseId]);

    const search = (pageNo: number, append: boolean) => {
        setSearching(true);
        return leaseApi
            .paged({ search: query.trim(), propertyId: propertyId || undefined, excludeStatus: NOT_SCANNABLE, page: pageNo, size: PAGE })
            .then(res => {
                const rows = res.content.filter(scannableLease);
                setResults(prev => (append ? [...prev, ...rows] : rows));
                setPage(pageNo);
                setHasMore((pageNo + 1) * PAGE < res.totalElements);
            })
            .catch(() => {
                if (!append) setResults([]);
                setHasMore(false);
            })
            .finally(() => setSearching(false));
    };

    useEffect(() => {
        if (!open || leaseId || picked || query.trim().length < 2) {
            setResults([]);
            setHasMore(false);
            return;
        }
        const timer = window.setTimeout(() => void search(0, false), 250);
        return () => window.clearTimeout(timer);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [open, leaseId, picked, query, propertyId]);

    if (!open) return null;

    if (target) {
        return (
            <BulkChequeUploadFlow
                leaseId={target.leaseId}
                rows={target.rows}
                onlyChequeId={chequeId ?? null}
                onClose={onClose}
                onSuccess={onDone}
            />
        );
    }

    if (leaseId) {
        // Loading the row's contract; a failure is said where the row was.
        return loadError ? (
            <LeaseDialog open title={t("scanCheques")} onClose={onClose} onConfirm={() => void loadRows(leaseId)}
                confirmText={t("retry")} cancelText={tl("cancel")} busy={loading} confirmTestId="scan-cheques-retry">
                <p role="alert" className="text-xs text-error">{loadError}</p>
            </LeaseDialog>
        ) : null;
    }

    return (
        <LeaseDialog
            open
            title={t("scanCheques")}
            onClose={onClose}
            onConfirm={() => picked && void loadRows(picked.id)}
            confirmText={t("scanContinue")}
            cancelText={tl("cancel")}
            busy={loading}
            confirmDisabled={!picked}
            confirmTestId="scan-cheques-continue"
        >
            <div className="space-y-3" data-testid="scan-cheques-picker">
                <p className="text-[11px] text-muted">{t("scanPickerHint")}</p>
                {!picked ? (
                    <div>
                        <label className={label} htmlFor="scan-cheques-lease-search">{t("selectLease")}</label>
                        <input
                            id="scan-cheques-lease-search"
                            data-testid="scan-cheques-lease-search"
                            className={field}
                            placeholder={t("leaseSearchPlaceholder")}
                            value={query}
                            onChange={e => setQuery(e.target.value)}
                            autoFocus
                        />
                        {searching && <p className="text-[11px] text-muted mt-1">…</p>}
                        {!searching && query.trim().length >= 2 && results.length === 0 && (
                            <p className="text-[11px] text-muted mt-1" data-testid="scan-cheques-no-results">{t("scanNoContracts")}</p>
                        )}
                        {results.length > 0 && (
                            <ul className="mt-1.5 max-h-48 overflow-auto border border-border rounded-lg divide-y divide-border">
                                {results.map(l => (
                                    <li key={l.id}>
                                        <button
                                            type="button"
                                            data-testid={`scan-cheques-lease-option-${l.id}`}
                                            className="w-full text-start px-3 py-2 text-xs hover:bg-input cursor-pointer"
                                            onClick={() => setPicked(l)}
                                        >
                                            <span className="font-semibold">{l.unitIdentifier ?? "—"}</span>
                                            <span className="text-muted"> · {l.renterName ?? "—"} · {l.propertyName ?? "—"} · {tl(`leaseStatus.${l.status}`)}</span>
                                        </button>
                                    </li>
                                ))}
                            </ul>
                        )}
                        {hasMore && !searching && (
                            <button type="button" data-testid="scan-cheques-more" onClick={() => void search(page + 1, true)}
                                className="mt-1.5 text-[11px] font-semibold text-primary hover:underline cursor-pointer">
                                {t("scanMoreContracts")}
                            </button>
                        )}
                    </div>
                ) : (
                    <div className="rounded-lg border border-border px-3 py-2 flex items-center justify-between gap-2" data-testid="scan-cheques-selected-lease">
                        <span className="text-xs font-semibold">
                            {picked.unitIdentifier ?? "—"} · {picked.renterName ?? "—"}
                        </span>
                        <button type="button" className="text-[11px] text-primary hover:underline cursor-pointer" onClick={() => setPicked(null)}>
                            {t("scanChangeContract")}
                        </button>
                    </div>
                )}
                {loadError && <p role="alert" className="text-xs text-error">{loadError}</p>}
            </div>
        </LeaseDialog>
    );
}
