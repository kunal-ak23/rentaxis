"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useParams } from "next/navigation";
import { useSession } from "next-auth/react";
import { useLocale, useTranslations } from "next-intl";
import { Link } from "@/i18n/routing";
import {
    ArrowLeft, CheckCircle2, Download, Eye, FileText, Loader2, Paperclip,
    Plus, ShieldCheck, Trash2, Upload, Video, X,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { fmtAmount, ledgerApi } from "@/lib/api/ledger";
import { NumberInput } from "@/components/ui/NumberInput";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import AccountPicker from "@/components/finance/AccountPicker";
import { assetSrc } from "@/lib/assetUrl";
import { clampIso, fmtIsoDate, isoDayAfter, maxIso, todayIso } from "@/components/leases/leaseMath";
import {
    netRefundOf, toSaveLines, totalOf, totalVatOf, type SettlementRow,
} from "@/components/leases/settlementMath";
import {
    ApiError, leaseApi, settlementApi,
    SETTLEMENT_ADDITION_CATEGORIES, SETTLEMENT_DEDUCTION_CATEGORIES,
    type AdditionCategory, type DeductionAttachment, type DeductionCategory,
    type LeaseDetail, type SettlementResponse, type SettlementStatement,
} from "@/lib/api/leasing";

/**
 * The move-out statement (spec §9.2).
 *
 * Nothing on this page is stored while the settlement is a draft: every figure
 * is re-derived from the ledger on each read, so a statement opened after a
 * cheque cleared or a recognition run posted shows the new position. Once it is
 * FINALIZED the snapshot on the stored row is what the `STL` was posted
 * against, and *that* is what is shown — the live statement would keep moving
 * and would no longer describe the document.
 *
 * The gates, each mirroring `SettlementService`:
 *
 *  - only a TERMINATED / EXPIRED / RENEWED lease may be settled (`SETTLEABLE`
 *    :144-145, `requireSettleable` :758-762) — termination is its own act with
 *    its own date and its own journals, and this statement is drawn from the
 *    receivable it leaves behind. RENEWED is in because a predecessor that
 *    settles instead of carrying its deposit forward is settled like any other
 *    (spec §6.6); **CLOSED is not**, because closure already requires a
 *    FINALIZED settlement — a closed contract's statement is a document to read,
 *    never one to finalise, which is exactly what this page shows for it;
 *  - PENALTIES / UNPAID_RENT / PREPAID_RENT / UTILITY_OVERPAYMENT are not
 *    offered at all (:737-761): all four are already inside
 *    `receivableBalance`, which the statement subtracts;
 *  - a refund needs an asset leaf to pay from, and only when it refunds
 *    (:452-454, `requireRefundBank` :800-816);
 *  - a refund paid while the register still holds something needs
 *    `acknowledgeOutstanding`;
 *  - the settlement date is on or after `terminatedOn` and in an open period
 *    (`requireUsableDate` :669-681).
 *
 * **Finalise does not close the contract.** `LeaseClosureService` closes it
 * only when nothing is left to collect, so the page reads the lease's real
 * status back afterwards instead of promising closure.
 */

const SETTLEABLE: LeaseDetail["status"][] = ["TERMINATED", "EXPIRED", "RENEWED"];

/**
 * The sentence `requireOutstandingAcknowledged` (`SettlementService` :774)
 * answers with. The client cannot always know `instrumentsOutstanding` — the
 * field is optional on the statement — so when the refusal is *this* one, the
 * checkbox that satisfies it is revealed rather than left off the screen, and
 * the server's own message carries the amount.
 */
function refusedForAcknowledgement(message: string): boolean {
    return /outstanding/i.test(message) && /acknowledge/i.test(message);
}

const th = "text-start px-3 py-2 text-[11px] font-semibold text-muted uppercase tracking-wider";
const td = "px-3 py-2 text-xs align-top";

function Figure({ label, value, hint, testId, tone }: {
    label: string;
    value: string;
    hint?: string;
    testId: string;
    tone?: "default" | "success" | "warning";
}) {
    return (
        <div className="bg-surface border border-border rounded-xl px-4 py-3">
            <p className="text-[10px] font-semibold text-muted uppercase tracking-wider">{label}</p>
            <p
                data-testid={testId}
                className={cn(
                    "text-lg font-bold tabular-nums mt-1",
                    tone === "success" ? "text-success" : tone === "warning" ? "text-warning" : "text-foreground",
                )}
            >
                {value}
            </p>
            {hint && <p className="text-[10px] text-muted mt-1">{hint}</p>}
        </div>
    );
}

function AttachmentThumbnail({ attachment, onDelete, onPreview, editable }: {
    attachment: DeductionAttachment;
    onDelete: (id: string) => void;
    onPreview: (a: DeductionAttachment) => void;
    editable: boolean;
}) {
    const isImage = attachment.fileType?.startsWith("image/");
    const isVideo = attachment.fileType?.startsWith("video/");
    return (
        <div
            className="relative group w-16 h-16 rounded-lg border border-border bg-input/50 overflow-hidden flex items-center justify-center cursor-pointer"
            onClick={() => onPreview(attachment)}
        >
            {isImage ? (
                // eslint-disable-next-line @next/next/no-img-element
                <img src={assetSrc(attachment.fileUrl)} alt={attachment.name} className="w-full h-full object-cover" />
            ) : isVideo ? (
                <Video size={20} className="text-muted" />
            ) : (
                <FileText size={20} className="text-muted" />
            )}
            <div className="absolute inset-0 bg-black/0 group-hover:bg-black/40 transition-all flex items-center justify-center gap-1">
                <button
                    onClick={e => { e.stopPropagation(); onPreview(attachment); }}
                    className="opacity-0 group-hover:opacity-100 p-1 bg-white/90 text-neutral-700 rounded-full cursor-pointer transition-opacity"
                >
                    <Eye size={10} />
                </button>
                {editable && (
                    <button
                        onClick={e => { e.stopPropagation(); onDelete(attachment.id); }}
                        className="opacity-0 group-hover:opacity-100 p-1 bg-error text-white rounded-full cursor-pointer transition-opacity"
                    >
                        <Trash2 size={10} />
                    </button>
                )}
            </div>
            <p className="absolute bottom-0 start-0 end-0 bg-black/60 text-white text-[8px] truncate px-1 py-0.5 leading-tight">
                {attachment.name}
            </p>
        </div>
    );
}

function AttachmentPreviewDialog({ attachment, onClose }: {
    attachment: DeductionAttachment | null;
    onClose: () => void;
}) {
    if (!attachment) return null;
    const isImage = attachment.fileType?.startsWith("image/");
    const isPdf = attachment.fileType === "application/pdf";
    const downloadUrl = `/api/proxy/v1/settlements/attachments/${attachment.id}/download`;

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4" onClick={onClose}>
            <div className="bg-surface rounded-2xl shadow-2xl w-full max-w-3xl max-h-[90vh] flex flex-col overflow-hidden" onClick={e => e.stopPropagation()}>
                <div className="flex items-center justify-between px-5 py-3 border-b border-border shrink-0">
                    <div className="flex items-center gap-2 min-w-0">
                        <FileText size={16} className="text-muted shrink-0" />
                        <span className="text-sm font-semibold text-foreground truncate">{attachment.name}</span>
                    </div>
                    <div className="flex items-center gap-2 shrink-0 ms-3">
                        <a
                            href={downloadUrl}
                            download={attachment.name}
                            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-primary text-primary-foreground text-xs font-semibold hover:opacity-90"
                            onClick={e => e.stopPropagation()}
                        >
                            <Download size={13} />
                        </a>
                        <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-input transition-colors cursor-pointer">
                            <X size={16} className="text-muted" />
                        </button>
                    </div>
                </div>
                <div className="flex-1 overflow-auto bg-input/30 flex items-center justify-center p-4">
                    {isImage ? (
                        // eslint-disable-next-line @next/next/no-img-element
                        <img src={assetSrc(attachment.fileUrl)} alt={attachment.name} className="max-w-full max-h-[70vh] object-contain rounded-lg shadow" />
                    ) : isPdf ? (
                        <iframe src={assetSrc(attachment.fileUrl)} title={attachment.name} className="w-full h-[70vh] rounded-lg border border-border" />
                    ) : (
                        <a
                            href={downloadUrl}
                            download={attachment.name}
                            className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-primary text-primary-foreground text-sm font-semibold"
                        >
                            <Download size={15} /> {attachment.name}
                        </a>
                    )}
                </div>
            </div>
        </div>
    );
}

export default function SettlementPage() {
    const params = useParams();
    const locale = useLocale();
    const leaseId = params.id as string;

    const t = useTranslations("Settlement");
    const tLeasing = useTranslations("Leasing");
    const tCheques = useTranslations("Cheques");
    const tLedger = useTranslations("Ledger");
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;

    const canView = hasPermission(userRole, "canViewSettlement");
    const canSettle = hasPermission(userRole, "canSettleLeases");
    const canTerminate = hasPermission(userRole, "canPreviewTermination");
    // F14-36: paying a deposit refund out is an ordinary voucher post.
    const canPayRefund = hasPermission(userRole, "canManageVouchers");

    const [lease, setLease] = useState<LeaseDetail | null>(null);
    const [statement, setStatement] = useState<SettlementStatement | null>(null);
    const [storedRow, setStoredRow] = useState<SettlementResponse | null>(null);
    const [lockedThrough, setLockedThrough] = useState<string | null>(null);
    const [rows, setRows] = useState<SettlementRow[]>([]);
    const [notes, setNotes] = useState("");
    const [dirty, setDirty] = useState(false);
    const [settlementDate, setSettlementDate] = useState<string | null>(null);
    const [acknowledged, setAcknowledged] = useState(false);
    /**
     * Set when a finalise is refused for the acknowledgement the screen did not
     * know to ask for — `instrumentsOutstanding` is optional on the statement,
     * and a client that reads an absent value as 0 hides the checkbox, sends
     * `acknowledgeOutstanding: false` and leaves the user with a refusal and no
     * control on screen that could satisfy it.
     */
    const [ackDemanded, setAckDemanded] = useState(false);

    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [saving, setSaving] = useState(false);
    const [saveError, setSaveError] = useState<string | null>(null);
    const [confirmOpen, setConfirmOpen] = useState(false);
    const [finalizing, setFinalizing] = useState(false);
    const [finalizeError, setFinalizeError] = useState<string | null>(null);
    const [closure, setClosure] = useState<"CLOSED" | "OPEN" | null>(null);
    const [previewAttachment, setPreviewAttachment] = useState<DeductionAttachment | null>(null);
    const [uploadingId, setUploadingId] = useState<string | null>(null);
    const nextKey = useRef(1);

    /** The stored lines, as the editable grid. */
    const rowsOf = useCallback((s: SettlementResponse | null): SettlementRow[] => {
        if (!s) return [];
        return (s.deductions ?? []).map(d => ({
            key: nextKey.current++,
            id: d.id,
            type: d.type,
            category: (d.type === "ADDITION" ? d.additionCategory : d.category) ?? "OTHER",
            description: d.description ?? "",
            amount: d.amount ?? 0,
            accountId: d.accountId ?? null,
            accountName: d.accountName ?? null,
            autoCalculated: d.autoCalculated ?? false,
            attachments: d.attachments ?? [],
            vatAmount: d.vatAmount ?? 0,
        }));
    }, []);

    const load = useCallback(async () => {
        setLoading(true);
        setLoadError(null);
        try {
            const [detail, live, saved, fiscal] = await Promise.all([
                leaseApi.get(leaseId),
                settlementApi.statement(leaseId),
                // 404 is "no settlement yet", which is the normal state before
                // the first Save draft — not a failure. Anything else is.
                settlementApi.get(leaseId).catch(e => {
                    if (e instanceof ApiError && e.status === 404) return null;
                    throw e;
                }),
                ledgerApi.fiscal.get().catch(() => null),
            ]);
            setLease(detail);
            setStatement(live);
            setStoredRow(saved);
            setRows(rowsOf(saved));
            setNotes(saved?.notes ?? "");
            setLockedThrough(fiscal?.booksLockedThrough ?? null);
            setDirty(false);
        } catch (e) {
            setLoadError(e instanceof ApiError ? e.message : t("loadFailed"));
        } finally {
            setLoading(false);
        }
    }, [leaseId, rowsOf, t]);

    useEffect(() => {
        // Nothing until NextAuth has answered. Loading before the role is known
        // means loading AGAIN when it arrives, and `load` resets the grid, the
        // notes and the refund bank from the stored settlement — so that second
        // load lands on a screen whose Save and Finalize controls are already
        // live (they need `canSettle`, which needs the role) and empties
        // whatever has been typed into it. Found by the plan 3 walkthrough.
        if (!userRole) return;
        if (!canView) {
            setLoading(false);
            return;
        }
        load();
    }, [canView, userRole, load]);

    const finalized = storedRow?.status === "FINALIZED";
    const settleable = !!lease && SETTLEABLE.includes(lease.status);
    const editable = canSettle && !finalized;

    /** `requireUsableDate`'s two bounds, as the picker's own. */
    const minSettlementDate = maxIso(lease?.terminatedOn, isoDayAfter(lockedThrough));

    useEffect(() => {
        if (settlementDate !== null) return;
        if (finalized && storedRow?.settlementDate) {
            setSettlementDate(storedRow.settlementDate);
            return;
        }
        if (!lease) return;
        setSettlementDate(clampIso(todayIso(), minSettlementDate, null));
    }, [settlementDate, finalized, storedRow, lease, minSettlementDate]);

    /**
     * A FINALIZED settlement shows its own snapshot; a draft shows the live
     * statement re-priced against whatever is currently in the grid.
     */
    const shown = useMemo(() => {
        if (finalized && storedRow) {
            return {
                earnedRent: storedRow.earnedRent ?? 0,
                receivedTotal: storedRow.receivedTotal ?? 0,
                receivableBalance: storedRow.receivableBalance ?? 0,
                depositsHeld: storedRow.depositsHeld ?? 0,
                penaltiesOutstanding: storedRow.penaltiesOutstanding ?? 0,
                totalDeductions: storedRow.totalDeductions ?? 0,
                totalAdditions: storedRow.totalAdditions ?? 0,
                netRefund: (storedRow.refundAmount ?? 0) - (storedRow.balanceDue ?? 0),
                // F14-37: not on SettlementResponseDTO — recomputed from the
                // stored lines' own vatAmount, same as the draft branch.
                totalDeductionVat: totalVatOf(rows),
            };
        }
        if (!statement) return null;
        return {
            earnedRent: statement.earnedRent,
            receivedTotal: statement.receivedTotal,
            receivableBalance: statement.receivableBalance,
            depositsHeld: statement.depositsHeld,
            penaltiesOutstanding: statement.penaltiesOutstanding,
            totalDeductions: totalOf(rows, "DEDUCTION"),
            totalAdditions: totalOf(rows, "ADDITION"),
            netRefund: netRefundOf(statement, rows),
            totalDeductionVat: totalVatOf(rows),
        };
    }, [finalized, storedRow, statement, rows]);

    const netRefund = shown?.netRefund ?? 0;
    const refunds = netRefund > 0;
    // Absent means 0 — the same thing the screen shows when the register really
    // is empty, and finalise re-checks it either way.
    const instrumentsOutstanding = statement?.instrumentsOutstanding ?? 0;
    const needsAcknowledgement = refunds && (instrumentsOutstanding > 0 || ackDemanded);

    const canFinalize =
        editable
        && settleable
        && !dirty
        && !!settlementDate
        && (!needsAcknowledgement || acknowledged);

    /**
     * The ids of whichever reasons are currently on screen, for the disabled
     * button's `aria-describedby`.
     *
     * Each reason already renders as visible text beside the control it belongs
     * to — the point of the association is that a screen-reader user who tabs
     * straight to a disabled Finalize is told why, rather than having to go
     * hunting for a sentence somewhere above it.
     */
    const finalizeReasons = [
        needsAcknowledgement && !acknowledged ? "settlement-acknowledge-required" : null,
        dirty ? "settlement-unsaved" : null,
    ].filter(Boolean) as string[];

    const patchRow = (key: number, patch: Partial<SettlementRow>) => {
        setRows(prev => prev.map(r => (r.key === key ? { ...r, ...patch } : r)));
        setDirty(true);
    };

    const addRow = (type: "DEDUCTION" | "ADDITION") => {
        setRows(prev => [
            ...prev,
            {
                key: nextKey.current++,
                type,
                category: type === "ADDITION" ? SETTLEMENT_ADDITION_CATEGORIES[0] : SETTLEMENT_DEDUCTION_CATEGORIES[0],
                description: "",
                amount: 0,
                accountId: null,
                autoCalculated: false,
                attachments: [],
                vatAmount: 0,
            },
        ]);
        setDirty(true);
    };

    const saveDraft = async () => {
        setSaving(true);
        setSaveError(null);
        try {
            const saved = await settlementApi.saveDraft(leaseId, {
                notes: notes.trim() || null,
                deductions: toSaveLines(rows),
            });
            setStoredRow(saved);
            setRows(rowsOf(saved));
            setDirty(false);
            // The lines moved, so the ledger's answer did too.
            setStatement(await settlementApi.statement(leaseId));
        } catch (e) {
            setSaveError(e instanceof ApiError ? e.message : t("saveFailed"));
        } finally {
            setSaving(false);
        }
    };

    const finalize = async () => {
        if (!settlementDate) return;
        setFinalizing(true);
        setFinalizeError(null);
        try {
            const saved = await settlementApi.finalize(leaseId, {
                settlementDate,
                acknowledgeOutstanding: needsAcknowledgement ? acknowledged : false,
            });
            setStoredRow(saved);
            setRows(rowsOf(saved));
            setConfirmOpen(false);
            // Finalise does NOT necessarily close the contract — the register
            // decides. Read the lease's real status rather than announcing one.
            const detail = await leaseApi.get(leaseId).catch(() => null);
            if (detail) setLease(detail);
            setClosure(detail?.status === "CLOSED" ? "CLOSED" : "OPEN");
        } catch (e) {
            const message = e instanceof ApiError ? e.message : t("finalizeFailed");
            setFinalizeError(message);
            // The one refusal the screen can answer with a control rather than
            // with an apology.
            if (e instanceof ApiError && refusedForAcknowledgement(message)) {
                setAckDemanded(true);
                setAcknowledged(false);
            }
            setConfirmOpen(false);
        } finally {
            setFinalizing(false);
        }
    };

    const reloadAttachments = async (deductionId: string) => {
        const res = await fetch(`/api/proxy/v1/settlements/deductions/${deductionId}/attachments`);
        if (!res.ok) return;
        const next: DeductionAttachment[] = await res.json();
        setRows(prev => prev.map(r => (r.id === deductionId ? { ...r, attachments: next } : r)));
    };

    const uploadAttachment = async (deductionId: string, file: File) => {
        setUploadingId(deductionId);
        try {
            const form = new FormData();
            form.append("file", file);
            form.append("name", file.name);
            const res = await fetch(`/api/upload?path=/api/v1/settlements/deductions/${deductionId}/attachments`, {
                method: "POST",
                body: form,
            });
            if (res.ok) await reloadAttachments(deductionId);
        } finally {
            setUploadingId(null);
        }
    };

    const deleteAttachment = async (attachmentId: string, deductionId: string) => {
        const res = await fetch(`/api/proxy/v1/settlements/attachments/${attachmentId}`, { method: "DELETE" });
        if (res.ok) await reloadAttachments(deductionId);
    };

    if (userRole && !canView) {
        return (
            <div className="max-w-3xl">
                <div className="bg-surface rounded-xl p-12 shadow-sm border border-border text-center">
                    <ShieldCheck size={48} className="mx-auto text-muted mb-4" />
                    <h2 className="text-lg font-bold text-foreground mb-2">{tLedger("accessDeniedTitle")}</h2>
                    <p className="text-sm text-muted" data-testid="settlement-access-denied">{t("accessDenied")}</p>
                </div>
            </div>
        );
    }

    if (loading) {
        return (
            <div className="flex justify-center py-24">
                <Loader2 size={20} className="animate-spin text-muted" />
            </div>
        );
    }

    const deductionRows = rows.filter(r => r.type === "DEDUCTION");
    const additionRows = rows.filter(r => r.type === "ADDITION");

    return (
        <div className="max-w-5xl space-y-6">
            <div className="flex items-center gap-3">
                <Link
                    href={`/dashboard/leases/${leaseId}`}
                    data-testid="settlement-back"
                    aria-label={t("backToLease")}
                    className="p-2 rounded-lg hover:bg-input transition-colors text-muted hover:text-foreground"
                >
                    <ArrowLeft size={18} />
                </Link>
                <div className="flex-1">
                    <div className="flex items-center gap-3">
                        <h1 className="text-xl font-bold text-foreground tracking-tight mb-0.5">{t("title")}</h1>
                        {storedRow && (
                            <span
                                data-testid="settlement-status"
                                className={cn(
                                    "px-2.5 py-1 rounded-lg text-[10px] font-semibold border",
                                    finalized
                                        ? "bg-success/10 text-success border-success/20"
                                        : "bg-input text-muted border-border",
                                )}
                            >
                                {t(`status${storedRow.status}`)}
                            </span>
                        )}
                    </div>
                    <p className="text-xs text-muted">
                        {t("desc")}
                        {statement && !finalized && ` · ${t("asOf")} ${fmtIsoDate(statement.asOf, locale)}`}
                    </p>
                </div>
            </div>

            {loadError && <LoadErrorBanner message={loadError} onRetry={load} />}

            {statement && statement.unrecognisedEntries > 0 && (
                <div
                    data-testid="settlement-unrecognised"
                    className="bg-warning/10 border border-warning/30 text-warning rounded-xl px-5 py-3 text-sm flex flex-wrap items-center gap-2"
                >
                    <span>{t("unrecognisedBanner", { count: statement.unrecognisedEntries })}</span>
                    <Link href="/dashboard/finance/recognition" className="font-semibold underline">
                        {t("runRecognition")}
                    </Link>
                </div>
            )}

            {/*
              * "Terminate first" is advice for a running contract. A finalized
              * settlement, or a CLOSED contract (closure already required one),
              * has nothing left to terminate: the banner sat above "Finalized
              * … by …" offering a Terminate button (gap #51).
              */}
            {lease && !settleable && !finalized && lease.status !== "CLOSED" && (
                <div
                    role="alert"
                    data-testid="settlement-not-settleable"
                    className="bg-warning/10 border border-warning/30 text-warning rounded-xl px-5 py-3 text-sm flex flex-wrap items-center gap-2"
                >
                    {/* The label, not the Java enum (`Leasing.leaseStatus.*`). */}
                    <span>{t("notSettleable", { status: tLeasing(`leaseStatus.${lease.status}`) })}</span>
                    {canTerminate && (
                        <Link
                            href={`/dashboard/leases/${leaseId}/terminate`}
                            data-testid="settlement-terminate-link"
                            className="font-semibold underline"
                        >
                            {t("terminateFirst")}
                        </Link>
                    )}
                </div>
            )}

            {finalized && storedRow && (
                <div
                    data-testid="settlement-read-only"
                    className="bg-success/5 border border-success/20 rounded-xl px-5 py-3 text-xs text-muted flex flex-wrap items-center gap-2"
                >
                    <CheckCircle2 size={14} className="text-success" />
                    <span>
                        {t("readOnlyFinalized", {
                            date: fmtIsoDate(storedRow.settlementDate ?? storedRow.settledAt, locale),
                            who: storedRow.settledByName ?? storedRow.settledBy ?? "—",
                        })}
                    </span>
                    {storedRow.journalNumber && (
                        <span data-testid="settlement-journal" className="font-semibold text-foreground">
                            {t("journalNumber")}: {storedRow.journalNumber}
                        </span>
                    )}
                </div>
            )}

            {closure && (
                <p
                    data-testid="settlement-closure"
                    className="rounded-xl bg-info/10 border border-info/30 px-4 py-2.5 text-xs text-info"
                >
                    {closure === "CLOSED" ? t("leaseClosed") : t("leaseStaysOpen")}
                </p>
            )}

            {!canSettle && canView && !finalized && (
                <p
                    data-testid="settlement-read-only-role"
                    className="rounded-xl bg-info/10 border border-info/30 px-4 py-2.5 text-xs text-info"
                >
                    {t("readOnlyRole")}
                </p>
            )}

            {shown && (
                <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
                    <Figure label={t("earnedRent")} value={fmtAmount(shown.earnedRent)} testId="settlement-earned-rent" />
                    <Figure label={t("receivedTotal")} value={fmtAmount(shown.receivedTotal)} testId="settlement-received" />
                    <Figure
                        label={t("receivableBalance")}
                        value={fmtAmount(shown.receivableBalance)}
                        hint={t("receivableHint")}
                        testId="settlement-receivable-balance"
                        tone={shown.receivableBalance > 0 ? "warning" : "default"}
                    />
                    <Figure label={t("depositsHeld")} value={fmtAmount(shown.depositsHeld)} testId="settlement-deposits-held" />
                    <Figure
                        label={t("penaltiesOutstanding")}
                        value={fmtAmount(shown.penaltiesOutstanding)}
                        hint={t("penaltiesHint")}
                        testId="settlement-penalties"
                    />
                    {/*
                      Every other figure on a FINALIZED settlement comes from the
                      stored snapshot. This one has no column to come from
                      (`SettlementResponseDTO` carries no acknowledged figure —
                      issue #291), and left reading the live statement it drifts:
                      a cheque that clears after finalising silently changes one
                      tile of a document that is supposed to be frozen. So after
                      FINALIZED it is not shown as a figure at all.
                    */}
                    {finalized ? (
                        <div
                            className="bg-surface border border-border rounded-xl px-4 py-3"
                            data-testid="settlement-instruments-frozen"
                        >
                            <p className="text-[10px] font-semibold text-muted uppercase tracking-wider">
                                {t("instrumentsOutstanding")}
                            </p>
                            <p className="text-[11px] text-muted mt-1">{t("instrumentsFrozen")}</p>
                        </div>
                    ) : (
                        <Figure
                            label={t("instrumentsOutstanding")}
                            value={fmtAmount(instrumentsOutstanding)}
                            hint={t("instrumentsHint")}
                            testId="settlement-instruments"
                            tone={instrumentsOutstanding > 0 ? "warning" : "default"}
                        />
                    )}
                </div>
            )}

            {statement?.outstandingInstruments && statement.outstandingInstruments.length > 0 && (
                <div className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm">
                    <div className="px-4 py-3 border-b border-border">
                        <h2 className="text-xs font-semibold text-muted uppercase tracking-wider">
                            {t("outstandingInstruments")}
                        </h2>
                    </div>
                    <div className="overflow-x-auto">
                        <table className="w-full min-w-[520px]">
                            <thead>
                                <tr className="bg-input/50">
                                    <th scope="col" className={th}>#</th>
                                    <th scope="col" className={th}>{tLedger("chequeNo")}</th>
                                    <th scope="col" className={th}>{tLedger("docDate")}</th>
                                    <th scope="col" className={`${th} text-end`}>{t("amount")}</th>
                                    <th scope="col" className={th}>{tLedger("status")}</th>
                                </tr>
                            </thead>
                            <tbody>
                                {statement.outstandingInstruments.map(i => (
                                    <tr key={i.id} data-testid={`settlement-outstanding-${i.id}`} className="border-t border-border">
                                        <td className={`${td} tabular-nums`}>{i.seqNo}</td>
                                        <td className={td}>
                                            {/* `mode` and `status` are Java enums; every
                                                other screen renders them through
                                                `Leasing.mode.*` / `Cheques.status.*`. */}
                                            {i.chequeNumber || tLeasing(`mode.${i.mode}`)}
                                            {i.penaltyCollection && (
                                                <span className="ms-2 px-1.5 py-0.5 rounded text-[9px] font-bold bg-warning/10 text-warning uppercase">
                                                    {t("penaltyRow")}
                                                </span>
                                            )}
                                        </td>
                                        <td className={td}>{fmtIsoDate(i.chequeDate, locale)}</td>
                                        <td className={`${td} text-end tabular-nums`}>{fmtAmount(i.amount)}</td>
                                        <td className={`${td} text-muted`}>{tCheques(`status.${i.status}`)}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                </div>
            )}

            {/* ── Lines ───────────────────────────────────────────────── */}
            <div className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm">
                <div className="px-4 py-3 border-b border-border flex items-center justify-between">
                    <h2 className="text-xs font-semibold text-muted uppercase tracking-wider">{t("deductions")}</h2>
                    {editable && (
                        <button
                            type="button"
                            data-testid="settlement-add-deduction"
                            onClick={() => addRow("DEDUCTION")}
                            className="flex items-center gap-1 text-[11px] font-semibold text-primary hover:text-primary/80 cursor-pointer"
                        >
                            <Plus size={12} /> {t("addDeduction")}
                        </button>
                    )}
                </div>
                <LineTable
                    rows={deductionRows}
                    allRows={rows}
                    type="DEDUCTION"
                    editable={editable}
                    propertyId={lease?.propertyId ?? null}
                    uploadingId={uploadingId}
                    onPatch={patchRow}
                    onRemove={key => { setRows(prev => prev.filter(r => r.key !== key)); setDirty(true); }}
                    onUpload={uploadAttachment}
                    onDeleteAttachment={deleteAttachment}
                    onPreviewAttachment={setPreviewAttachment}
                />
            </div>

            <div className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm">
                <div className="px-4 py-3 border-b border-border flex items-center justify-between">
                    <h2 className="text-xs font-semibold text-muted uppercase tracking-wider">{t("additions")}</h2>
                    {editable && (
                        <button
                            type="button"
                            data-testid="settlement-add-addition"
                            onClick={() => addRow("ADDITION")}
                            className="flex items-center gap-1 text-[11px] font-semibold text-success hover:text-success/80 cursor-pointer"
                        >
                            <Plus size={12} /> {t("addAddition")}
                        </button>
                    )}
                </div>
                <LineTable
                    rows={additionRows}
                    allRows={rows}
                    type="ADDITION"
                    editable={editable}
                    propertyId={lease?.propertyId ?? null}
                    uploadingId={uploadingId}
                    onPatch={patchRow}
                    onRemove={key => { setRows(prev => prev.filter(r => r.key !== key)); setDirty(true); }}
                    onUpload={uploadAttachment}
                    onDeleteAttachment={deleteAttachment}
                    onPreviewAttachment={setPreviewAttachment}
                />
            </div>

            {/* ── The bottom line ─────────────────────────────────────── */}
            {shown && (
                <div className="bg-surface border border-border rounded-xl px-5 py-4 space-y-3">
                    <Row label={t("totalDeductions")} value={`- ${fmtAmount(shown.totalDeductions)}`} tone="error" testId="settlement-total-deductions" />
                    {shown.totalDeductionVat > 0 && (
                        <Row label={t("totalDeductionVat")} value={`- ${fmtAmount(shown.totalDeductionVat)}`} tone="error" testId="settlement-total-deduction-vat" />
                    )}
                    <Row label={t("totalAdditions")} value={`+ ${fmtAmount(shown.totalAdditions)}`} tone="success" testId="settlement-total-additions" />
                    <div className="border-t-2 border-border pt-3 flex justify-between items-center">
                        <span className="text-sm font-bold text-foreground">
                            {netRefund > 0 ? t("refundDue") : netRefund < 0 ? t("balanceDue") : t("netRefund")}
                        </span>
                        <span
                            data-testid={netRefund < 0 ? "settlement-balance-due" : "settlement-net-refund"}
                            className={cn("text-sm font-bold tabular-nums", netRefund < 0 ? "text-error" : "text-success")}
                        >
                            {fmtAmount(Math.abs(netRefund))}
                        </span>
                    </div>
                    {netRefund === 0 && <p className="text-[11px] text-muted">{t("settled")}</p>}
                </div>
            )}

            {/* F14-36: once finalized, a refund owed is paid out by an ordinary
                BPV naming this settlement — never a bank account picked here. */}
            {finalized && storedRow && (storedRow.refundAmount ?? 0) > 0 && (
                <div className="bg-surface border border-border rounded-xl px-5 py-4 space-y-3" data-testid="settlement-refund-status">
                    <Row label={t("refundOwed")} value={fmtAmount(storedRow.refundAmount ?? 0)} testId="settlement-refund-owed" />
                    <Row label={t("refundPaid")} value={fmtAmount(storedRow.refundPaid ?? 0)} testId="settlement-refund-paid" />
                    <div className="border-t border-border pt-3 flex justify-between items-center">
                        <span className="text-sm font-bold text-foreground">{t("refundOutstanding")}</span>
                        <span
                            data-testid="settlement-refund-outstanding"
                            className={cn("text-sm font-bold tabular-nums", (storedRow.refundOutstanding ?? storedRow.refundAmount ?? 0) > 0 ? "text-warning" : "text-success")}
                        >
                            {fmtAmount(storedRow.refundOutstanding ?? storedRow.refundAmount ?? 0)}
                        </span>
                    </div>
                    {canPayRefund && (storedRow.refundOutstanding ?? storedRow.refundAmount ?? 0) > 0 && (
                        <Link
                            data-testid="settlement-pay-refund"
                            href={`/dashboard/finance/vouchers/payment?${new URLSearchParams({
                                settlementId: storedRow.id,
                                renter: lease?.renterName ?? "",
                                unit: lease?.unitIdentifier ?? "",
                                amount: String(storedRow.refundOutstanding ?? storedRow.refundAmount ?? 0),
                            }).toString()}`}
                            className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-xs font-bold bg-primary text-primary-foreground cursor-pointer"
                        >
                            {t("payRefund")}
                        </Link>
                    )}
                </div>
            )}

            {editable && (
                <div className="bg-surface border border-border rounded-xl px-5 py-4 space-y-4">
                    <label className="flex flex-col gap-1">
                        <span className="text-[10px] font-semibold text-muted uppercase tracking-wider">{t("notes")}</span>
                        <textarea
                            data-testid="settlement-notes"
                            value={notes}
                            rows={2}
                            placeholder={t("notesPlaceholder")}
                            onChange={e => { setNotes(e.target.value); setDirty(true); }}
                            className="w-full border border-border rounded-lg bg-surface px-3 py-2 text-xs text-foreground placeholder:text-muted/50 focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none resize-none"
                        />
                    </label>

                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                        <label className="flex flex-col gap-1">
                            <span className="text-[10px] font-semibold text-muted uppercase tracking-wider">
                                {t("settlementDate")}
                            </span>
                            <input
                                type="date"
                                data-testid="settlement-date"
                                value={settlementDate ?? ""}
                                min={minSettlementDate}
                                onChange={e => setSettlementDate(e.target.value)}
                                className="border border-border rounded-lg bg-surface px-3 py-1.5 text-xs text-foreground focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                            />
                            {lease?.terminatedOn && (
                                <span className="text-[10px] text-muted">
                                    {t("settlementDateHint", { date: fmtIsoDate(lease.terminatedOn, locale) })}
                                </span>
                            )}
                        </label>
                    </div>

                    {needsAcknowledgement && (
                        <label className="flex items-start gap-2 text-xs text-warning">
                            <input
                                type="checkbox"
                                data-testid="settlement-acknowledge"
                                checked={acknowledged}
                                onChange={e => setAcknowledged(e.target.checked)}
                                className="mt-0.5"
                            />
                            <span>
                                {/*
                                  When the statement carried the figure, name it.
                                  When it did not and the server had to say so,
                                  the server's own sentence is on screen just
                                  above (`settlement-finalize-error`), so this
                                  asks without inventing an amount.
                                */}
                                {instrumentsOutstanding > 0
                                    ? t("acknowledgeOutstanding", { amount: fmtAmount(instrumentsOutstanding) })
                                    : t("acknowledgeOutstandingUnknown")}
                                {!acknowledged && (
                                    <span id="settlement-acknowledge-required" className="block mt-0.5">
                                        {t("acknowledgeRequired")}
                                    </span>
                                )}
                            </span>
                        </label>
                    )}

                    {saveError && <p className="text-xs text-error" data-testid="settlement-save-error">{saveError}</p>}
                    {finalizeError && <p className="text-xs text-error" data-testid="settlement-finalize-error">{finalizeError}</p>}
                    {dirty && (
                        <p id="settlement-unsaved" className="text-[11px] text-warning" data-testid="settlement-unsaved">
                            {t("unsavedChanges")}
                        </p>
                    )}

                    <div className="flex items-center justify-end gap-3">
                        <button
                            type="button"
                            data-testid="settlement-save-draft"
                            onClick={saveDraft}
                            disabled={saving}
                            className="flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold border border-primary text-primary hover:bg-primary/5 transition-colors cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
                        >
                            {saving ? <Loader2 size={12} className="animate-spin" /> : <CheckCircle2 size={12} />}
                            {t("saveDraft")}
                        </button>
                        {settleable && (
                            <button
                                type="button"
                                data-testid="settlement-finalize"
                                onClick={() => setConfirmOpen(true)}
                                disabled={!canFinalize || finalizing}
                                aria-describedby={finalizeReasons.length ? finalizeReasons.join(" ") : undefined}
                                className="flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold bg-primary text-primary-foreground hover:bg-primary/90 transition-colors cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
                            >
                                {finalizing && <Loader2 size={12} className="animate-spin" />}
                                {t("finalize")}
                            </button>
                        )}
                    </div>
                </div>
            )}

            <ConfirmDialog
                isOpen={confirmOpen}
                onClose={() => setConfirmOpen(false)}
                onConfirm={finalize}
                isLoading={finalizing}
                title={t("confirmTitle")}
                description={t("confirmBody", { date: fmtIsoDate(settlementDate, locale) })}
                confirmText={t("finalize")}
                cancelText={tLedger("cancel")}
                confirmTestId="settlement-finalize-confirm"
            />

            <AttachmentPreviewDialog attachment={previewAttachment} onClose={() => setPreviewAttachment(null)} />
        </div>
    );
}

function Row({ label, value, tone, testId }: { label: string; value: string; tone?: "error" | "success"; testId: string }) {
    return (
        <div className="flex justify-between items-center">
            <span className="text-xs text-muted">{label}</span>
            <span
                data-testid={testId}
                className={cn("text-xs font-semibold tabular-nums", tone === "error" ? "text-error" : tone === "success" ? "text-success" : "text-foreground")}
            >
                {value}
            </span>
        </div>
    );
}

function LineTable({
    rows, allRows, type, editable, propertyId, uploadingId,
    onPatch, onRemove, onUpload, onDeleteAttachment, onPreviewAttachment,
}: {
    rows: SettlementRow[];
    /** For the row's index in the whole grid, which is what the testids count. */
    allRows: SettlementRow[];
    type: "DEDUCTION" | "ADDITION";
    editable: boolean;
    propertyId: string | null;
    uploadingId: string | null;
    onPatch: (key: number, patch: Partial<SettlementRow>) => void;
    onRemove: (key: number) => void;
    onUpload: (deductionId: string, file: File) => void;
    onDeleteAttachment: (attachmentId: string, deductionId: string) => void;
    onPreviewAttachment: (a: DeductionAttachment) => void;
}) {
    const t = useTranslations("Settlement");
    const categories = type === "ADDITION" ? SETTLEMENT_ADDITION_CATEGORIES : SETTLEMENT_DEDUCTION_CATEGORIES;
    const labelFor = (c: string) =>
        type === "ADDITION"
            ? t(`additionCategory.${c as AdditionCategory}`)
            : t(`deductionCategory.${c as DeductionCategory}`);

    if (rows.length === 0) {
        return (
            <p className="text-xs text-muted text-center py-6">
                {type === "ADDITION" ? t("noAdditions") : t("noDeductions")}
            </p>
        );
    }

    return (
        <div className="overflow-x-auto">
            <table className="w-full min-w-[720px]">
                <thead>
                    <tr className="bg-input/50">
                        <th scope="col" className={th}>{t("category")}</th>
                        <th scope="col" className={th}>{t("description")}</th>
                        <th scope="col" className={`${th} text-end`}>{t("amount")}</th>
                        <th scope="col" className={th}>{t("account")}</th>
                        <th scope="col" className={th}>{t("attachments")}</th>
                        {editable && <th scope="col" className={th} />}
                    </tr>
                </thead>
                <tbody>
                    {rows.map(r => {
                        const index = allRows.findIndex(x => x.key === r.key);
                        return (
                            <tr key={r.key} data-testid={`settlement-line-${index}`} className="border-t border-border">
                                <td className={td}>
                                    {editable ? (
                                        <select
                                            data-testid={`settlement-category-${index}`}
                                            aria-label={t("category")}
                                            value={r.category}
                                            onChange={e => onPatch(r.key, { category: e.target.value as DeductionCategory })}
                                            className="w-full border border-border rounded-lg bg-surface px-2 py-1.5 text-xs text-foreground focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                                        >
                                            {categories.map(c => (
                                                <option key={c} value={c}>{labelFor(c)}</option>
                                            ))}
                                        </select>
                                    ) : (
                                        <span data-testid={`settlement-category-${index}`}>{labelFor(r.category)}</span>
                                    )}
                                </td>
                                <td className={td}>
                                    {editable ? (
                                        <input
                                            type="text"
                                            data-testid={`settlement-description-${index}`}
                                            aria-label={t("description")}
                                            value={r.description}
                                            placeholder={t("descriptionPlaceholder")}
                                            onChange={e => onPatch(r.key, { description: e.target.value })}
                                            className="w-full border border-border rounded-lg bg-surface px-3 py-1.5 text-xs text-foreground placeholder:text-muted/50 focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                                        />
                                    ) : (
                                        <span className="text-muted">{r.description || "—"}</span>
                                    )}
                                </td>
                                <td className={`${td} text-end`}>
                                    {editable ? (
                                        <NumberInput
                                            value={r.amount}
                                            min={0}
                                            step={0.01}
                                            data-testid={`settlement-amount-${index}`}
                                            aria-label={t("amount")}
                                            onChange={v => onPatch(r.key, { amount: v })}
                                            className="w-28 border border-border rounded-lg bg-surface px-3 py-1.5 text-xs text-foreground text-end tabular-nums focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                                        />
                                    ) : (
                                        <span className="tabular-nums">{fmtAmount(r.amount)}</span>
                                    )}
                                    {r.type === "DEDUCTION" && r.vatAmount > 0 && (
                                        <span
                                            className="block text-[10px] text-muted tabular-nums"
                                            data-testid={`settlement-line-vat-${index}`}
                                        >
                                            {t("lineVat", { amount: fmtAmount(r.vatAmount) })}
                                        </span>
                                    )}
                                </td>
                                <td className={td}>
                                    {editable ? (
                                        <div className="min-w-[180px]">
                                            {/*
                                              INCOME only. A deduction must name an INCOME leaf; an
                                              addition may name INCOME or EXPENSE, and INCOME is
                                              strictly inside that — `requireUsableAccount`
                                              (SettlementService.java:772-798) refuses anything else,
                                              and both categories' defaults resolve to income roles.
                                            */}
                                            <AccountPicker
                                                value={r.accountId}
                                                onChange={id => onPatch(r.key, { accountId: id })}
                                                accountType="INCOME"
                                                leafOnly
                                                propertyId={propertyId}
                                                placeholder={t("accountDefault")}
                                            />
                                        </div>
                                    ) : (
                                        <span className="text-muted">{r.accountName ?? t("accountDefault")}</span>
                                    )}
                                </td>
                                <td className={td}>
                                    {r.id ? (
                                        <div className="flex flex-wrap items-center gap-2">
                                            {r.attachments.map(a => (
                                                <AttachmentThumbnail
                                                    key={a.id}
                                                    attachment={a}
                                                    editable={editable}
                                                    onPreview={onPreviewAttachment}
                                                    onDelete={id => onDeleteAttachment(id, r.id!)}
                                                />
                                            ))}
                                            {r.attachments.length < 10 && (
                                                <label className="flex items-center gap-1 px-2 py-1 rounded-lg text-[10px] font-semibold bg-primary/10 text-primary hover:bg-primary/20 cursor-pointer">
                                                    {uploadingId === r.id
                                                        ? <Loader2 size={10} className="animate-spin" />
                                                        : <Upload size={10} />}
                                                    {t("addFiles")}
                                                    <input
                                                        type="file"
                                                        accept="image/*,video/*,.pdf"
                                                        className="hidden"
                                                        disabled={uploadingId === r.id}
                                                        onChange={e => {
                                                            const file = e.target.files?.[0];
                                                            if (file && r.id) onUpload(r.id, file);
                                                            if (e.target) e.target.value = "";
                                                        }}
                                                    />
                                                </label>
                                            )}
                                        </div>
                                    ) : (
                                        <span className="text-[10px] text-muted italic flex items-center gap-1">
                                            <Paperclip size={9} /> {t("saveDraftToAttach")}
                                        </span>
                                    )}
                                </td>
                                {editable && (
                                    <td className={td}>
                                        <button
                                            type="button"
                                            data-testid={`settlement-remove-${index}`}
                                            onClick={() => onRemove(r.key)}
                                            title={t("remove")}
                                            className="p-1 text-error hover:text-error/80 cursor-pointer"
                                        >
                                            <Trash2 size={13} />
                                        </button>
                                    </td>
                                )}
                            </tr>
                        );
                    })}
                </tbody>
            </table>
        </div>
    );
}
