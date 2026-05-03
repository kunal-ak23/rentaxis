"use client";

import { useState, useEffect, useCallback } from "react";
import { useTranslations } from "next-intl";
import {
    AlertTriangle,
    CheckCircle,
    AlertCircle,
    Clock,
    Building2,
    CreditCard,
    ChevronDown,
    ChevronUp,
    Info,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { formatCurrency } from "@/lib/format";

// ─── Types ────────────────────────────────────────────────────────────────────

type PenaltyPaymentDTO = {
    id: string;
    amount: number;
    paymentMethod: string;
    paymentReference: string | null;
    receivedAt: string | null;
    notes: string | null;
    createdAt: string;
};

type PenaltyDTO = {
    id: string;
    paymentScheduleId: string;
    leaseId: string;
    penaltyType: string;
    failureReason: "BOUNCE" | "SIGNATURE_MISMATCH" | "ACCOUNT_CLOSED" | null;
    penaltyAmount: number;
    daysOverdue: number;
    fineGraceDays: number | null;
    finePerDayRate: number | null;
    currentTotal: number;
    outstanding: number;
    waived: boolean;
    waivedReason: string | null;
    createdAt: string;
    clearedAt: string | null;
    payments: PenaltyPaymentDTO[];
};

type PenaltyStatus = "OPEN" | "CLEARED" | "WAIVED";

/** Derive status from DTO fields — do NOT rely on a serialised server method. */
function derivePenaltyStatus(p: PenaltyDTO): PenaltyStatus {
    if (p.clearedAt && p.waived) return "WAIVED";
    if (p.clearedAt) return "CLEARED";
    return "OPEN";
}

type TabKey = "open" | "cleared";

// ─── Constants ────────────────────────────────────────────────────────────────

const FAILURE_REASON_STYLES: Record<string, string> = {
    BOUNCE: "bg-error/10 text-error border border-error/20",
    SIGNATURE_MISMATCH: "bg-warning/10 text-warning border border-warning/20",
    ACCOUNT_CLOSED: "bg-error/10 text-error border border-error/20",
};

const STATUS_STYLES: Record<PenaltyStatus, string> = {
    OPEN: "bg-error/10 text-error border border-error/20",
    CLEARED: "bg-success/10 text-success border border-success/20",
    WAIVED: "bg-info/10 text-info border border-info/20",
};

// ─── Sub-components ───────────────────────────────────────────────────────────

function PenaltyCard({ penalty }: { penalty: PenaltyDTO }) {
    const t = useTranslations("RenterPenalties");
    const [expanded, setExpanded] = useState(false);

    const status = derivePenaltyStatus(penalty);
    const accrued = penalty.currentTotal - penalty.penaltyAmount;
    // TODO: embed installment metadata in PenaltyDTO (M12 deferred — option B)
    const chequeLabel = `Cheque #${penalty.paymentScheduleId.slice(0, 8)}`;

    return (
        <div className="bg-surface rounded-xl border border-border hover:shadow-md transition-all duration-200">
            {/* Card header */}
            <div className="p-5">
                <div className="flex flex-wrap items-start justify-between gap-3 mb-4">
                    <div className="flex items-center gap-3">
                        <div
                            className={cn(
                                "w-10 h-10 rounded-xl flex items-center justify-center border shrink-0",
                                status === "OPEN"
                                    ? "bg-error/10 text-error border-error/20"
                                    : status === "WAIVED"
                                      ? "bg-info/10 text-info border-info/20"
                                      : "bg-success/10 text-success border-success/20"
                            )}
                        >
                            {status === "OPEN" ? (
                                <AlertTriangle size={18} />
                            ) : status === "WAIVED" ? (
                                <Info size={18} />
                            ) : (
                                <CheckCircle size={18} />
                            )}
                        </div>
                        <div>
                            <p className="text-xs font-bold text-foreground tracking-tight">
                                {chequeLabel}
                            </p>
                            <p className="text-[10px] text-muted font-medium">
                                {t("raisedOn")} {new Date(penalty.createdAt).toLocaleDateString()}
                            </p>
                        </div>
                    </div>

                    <div className="flex items-center gap-2 flex-wrap">
                        {/* Failure reason badge */}
                        {penalty.failureReason && (
                            <span
                                className={cn(
                                    "inline-flex items-center px-2.5 py-1 rounded-lg text-[9px] font-bold uppercase tracking-widest",
                                    FAILURE_REASON_STYLES[penalty.failureReason] ??
                                        "bg-input text-muted border border-border"
                                )}
                            >
                                {t(`reason_${penalty.failureReason}`)}
                            </span>
                        )}
                        {/* Status badge */}
                        <span
                            className={cn(
                                "inline-flex items-center gap-1 px-2.5 py-1 rounded-lg text-[9px] font-bold uppercase tracking-widest",
                                STATUS_STYLES[status]
                            )}
                        >
                            {status === "OPEN" && <Clock size={10} />}
                            {status === "CLEARED" && <CheckCircle size={10} />}
                            {t(`status_${status}`)}
                        </span>
                    </div>
                </div>

                {/* Days overdue pill */}
                {penalty.daysOverdue > 0 && status === "OPEN" && (
                    <div className="mb-4">
                        <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-[10px] font-semibold bg-error/10 text-error border border-error/20">
                            <AlertTriangle size={11} />
                            {penalty.daysOverdue} {t("daysOverdue")}
                        </span>
                    </div>
                )}

                {/* Amount grid */}
                <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                    <div className="bg-input/70 rounded-xl p-3 border border-border">
                        <p className="text-[9px] font-semibold text-muted uppercase tracking-[0.15em] mb-1">
                            {t("baseFine")}
                        </p>
                        <p className="text-xs font-bold text-foreground tabular-nums">
                            {formatCurrency(penalty.penaltyAmount)}
                        </p>
                    </div>
                    <div className="bg-input/70 rounded-xl p-3 border border-border">
                        <p className="text-[9px] font-semibold text-muted uppercase tracking-[0.15em] mb-1">
                            {t("accrued")}
                        </p>
                        <p
                            className={cn(
                                "text-xs font-bold tabular-nums",
                                accrued > 0 ? "text-error" : "text-foreground"
                            )}
                        >
                            {formatCurrency(accrued)}
                        </p>
                    </div>
                    <div className="bg-input/70 rounded-xl p-3 border border-border">
                        <p className="text-[9px] font-semibold text-muted uppercase tracking-[0.15em] mb-1">
                            {t("currentTotal")}
                        </p>
                        <p className="text-xs font-bold text-foreground tabular-nums">
                            {formatCurrency(penalty.currentTotal)}
                        </p>
                    </div>
                    <div
                        className={cn(
                            "rounded-xl p-3 border",
                            status === "OPEN"
                                ? "bg-error/5 border-error/20"
                                : "bg-input/70 border-border"
                        )}
                    >
                        <p
                            className={cn(
                                "text-[9px] font-semibold uppercase tracking-[0.15em] mb-1",
                                status === "OPEN" ? "text-error" : "text-muted"
                            )}
                        >
                            {t("outstanding")}
                        </p>
                        <p
                            className={cn(
                                "text-xs font-bold tabular-nums",
                                status === "OPEN" ? "text-error" : "text-foreground"
                            )}
                        >
                            {formatCurrency(penalty.outstanding)}
                        </p>
                    </div>
                </div>

                {/* Cleared date for history tab */}
                {penalty.clearedAt && (
                    <div className="mt-3 flex items-center gap-2 text-[10px] text-muted font-medium">
                        <CheckCircle size={12} className="text-success" />
                        {t("clearedOn")} {new Date(penalty.clearedAt).toLocaleDateString()}
                        {penalty.waived && penalty.waivedReason && (
                            <span className="ml-2 text-info">({penalty.waivedReason})</span>
                        )}
                    </div>
                )}
            </div>

            {/* Payment history (expanded inline for cleared/waived) */}
            {penalty.payments.length > 0 && (
                <div className="border-t border-border">
                    <button
                        onClick={() => setExpanded(v => !v)}
                        className="w-full flex items-center justify-between px-5 py-3 text-[10px] font-semibold text-muted hover:text-foreground transition-colors cursor-pointer"
                    >
                        <span className="flex items-center gap-2">
                            <CreditCard size={12} />
                            {t("paymentHistory")} ({penalty.payments.length})
                        </span>
                        {expanded ? <ChevronUp size={13} /> : <ChevronDown size={13} />}
                    </button>

                    {expanded && (
                        <div className="px-5 pb-4 space-y-2">
                            {penalty.payments.map(pmt => (
                                <div
                                    key={pmt.id}
                                    className="bg-input/50 rounded-lg p-3 border border-border flex flex-wrap gap-4 text-[10px]"
                                >
                                    <div>
                                        <span className="text-muted font-semibold uppercase tracking-[0.12em]">
                                            {t("amount")}
                                        </span>
                                        <p className="font-bold text-foreground tabular-nums mt-0.5">
                                            {formatCurrency(pmt.amount)}
                                        </p>
                                    </div>
                                    <div>
                                        <span className="text-muted font-semibold uppercase tracking-[0.12em]">
                                            {t("method")}
                                        </span>
                                        <p className="font-bold text-foreground mt-0.5">
                                            {pmt.paymentMethod.replace(/_/g, " ")}
                                        </p>
                                    </div>
                                    {pmt.paymentReference && (
                                        <div>
                                            <span className="text-muted font-semibold uppercase tracking-[0.12em]">
                                                {t("reference")}
                                            </span>
                                            <p className="font-bold text-foreground mt-0.5">
                                                {pmt.paymentReference}
                                            </p>
                                        </div>
                                    )}
                                    {pmt.receivedAt && (
                                        <div>
                                            <span className="text-muted font-semibold uppercase tracking-[0.12em]">
                                                {t("receivedAt")}
                                            </span>
                                            <p className="font-bold text-foreground mt-0.5">
                                                {new Date(pmt.receivedAt).toLocaleDateString()}
                                            </p>
                                        </div>
                                    )}
                                </div>
                            ))}
                        </div>
                    )}
                </div>
            )}
        </div>
    );
}

// ─── Skeleton loader ──────────────────────────────────────────────────────────

function PenaltySkeleton() {
    return (
        <div className="space-y-4">
            {[1, 2].map(i => (
                <div key={i} className="bg-surface rounded-xl p-5 border border-border animate-pulse">
                    <div className="flex justify-between items-start mb-4">
                        <div className="flex items-center gap-3">
                            <div className="w-10 h-10 bg-input rounded-xl" />
                            <div>
                                <div className="h-3 w-32 bg-input rounded mb-2" />
                                <div className="h-2.5 w-24 bg-input rounded" />
                            </div>
                        </div>
                        <div className="h-5 w-20 bg-input rounded-lg" />
                    </div>
                    <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                        {[1, 2, 3, 4].map(j => (
                            <div key={j} className="bg-input rounded-xl p-3 border border-border">
                                <div className="h-2.5 w-12 bg-input rounded mb-2" />
                                <div className="h-3.5 w-20 bg-input rounded" />
                            </div>
                        ))}
                    </div>
                </div>
            ))}
        </div>
    );
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function RenterPenaltiesPage() {
    const t = useTranslations("RenterPenalties");

    const [activeTab, setActiveTab] = useState<TabKey>("open");
    const [penalties, setPenalties] = useState<PenaltyDTO[]>([]);
    const [loading, setLoading] = useState(true);

    const fetchPenalties = useCallback(async (tab: TabKey) => {
        setLoading(true);
        try {
            // RENTER role is auto-scoped on the backend — no leaseId filter needed here.
            // Status param: "open" → backend returns OPEN; "cleared" → returns both CLEARED and WAIVED.
            const res = await fetch(
                `/api/proxy/v1/penalties?status=${tab}&size=50`
            );
            if (res.ok) {
                const page = await res.json();
                // Spring Page<T> response
                const items: PenaltyDTO[] = Array.isArray(page)
                    ? page
                    : page.content ?? [];
                setPenalties(items);
            }
        } catch (err) {
            console.error(err);
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        fetchPenalties(activeTab);
    }, [fetchPenalties, activeTab]);

    const handleTabChange = (tab: TabKey) => {
        if (tab === activeTab) return;
        setPenalties([]);
        setActiveTab(tab);
    };

    const openCount = penalties.filter(p => derivePenaltyStatus(p) === "OPEN").length;
    const isEmpty = !loading && penalties.length === 0;

    return (
        <div className="p-8 max-w-5xl mx-auto">
            {/* ── Page header ──────────────────────────────────────────── */}
            <div className="mb-8">
                <h1 className="text-xl font-bold text-foreground tracking-tight mb-1">
                    {t("pageTitle")}
                </h1>
                <p className="text-xs text-muted font-medium">{t("pageSubtitle")}</p>
            </div>

            {/* ── Informational notice ─────────────────────────────────── */}
            <div className="flex items-start gap-3 bg-info/5 border border-info/20 rounded-xl p-4 mb-6">
                <Info size={15} className="text-info mt-0.5 shrink-0" />
                <p className="text-xs text-foreground/80 font-medium leading-relaxed">
                    {t("clearanceNotice")}
                </p>
            </div>

            {/* ── Tabs ─────────────────────────────────────────────────── */}
            <div className="flex items-center gap-1 p-1 bg-input/60 rounded-xl border border-border mb-6 w-fit">
                <button
                    onClick={() => handleTabChange("open")}
                    className={cn(
                        "px-4 py-2 rounded-lg text-xs font-semibold transition-all duration-200 cursor-pointer focus:outline-none focus:ring-2 focus:ring-primary/30",
                        activeTab === "open"
                            ? "bg-surface text-foreground shadow-sm border border-border"
                            : "text-muted hover:text-foreground"
                    )}
                >
                    {t("tabOpen")}
                    {openCount > 0 && (
                        <span className="ml-1.5 inline-flex items-center justify-center w-4 h-4 rounded-full bg-error text-white text-[9px] font-bold">
                            {openCount}
                        </span>
                    )}
                </button>
                <button
                    onClick={() => handleTabChange("cleared")}
                    className={cn(
                        "px-4 py-2 rounded-lg text-xs font-semibold transition-all duration-200 cursor-pointer focus:outline-none focus:ring-2 focus:ring-primary/30",
                        activeTab === "cleared"
                            ? "bg-surface text-foreground shadow-sm border border-border"
                            : "text-muted hover:text-foreground"
                    )}
                >
                    {t("tabCleared")}
                </button>
            </div>

            {/* ── List ─────────────────────────────────────────────────── */}
            {loading ? (
                <PenaltySkeleton />
            ) : isEmpty ? (
                <div className="text-center py-24 bg-background border border-dashed border-border rounded-xl flex flex-col items-center">
                    <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-success shadow-sm mb-6 border border-border">
                        {activeTab === "open" ? (
                            <CheckCircle size={32} />
                        ) : (
                            <AlertCircle size={32} />
                        )}
                    </div>
                    <p className="text-sm font-bold text-muted mb-2 uppercase tracking-widest">
                        {activeTab === "open" ? t("emptyOpen") : t("emptyCleared")}
                    </p>
                    {activeTab === "open" && (
                        <p className="text-xs text-muted">{t("emptyOpenSub")}</p>
                    )}
                </div>
            ) : (
                <div className="space-y-4">
                    {penalties.map(penalty => (
                        <PenaltyCard key={penalty.id} penalty={penalty} />
                    ))}
                </div>
            )}

            {/* ── How to pay panel (always visible on Open tab) ────────── */}
            {activeTab === "open" && (
                <div className="mt-8 bg-surface rounded-xl border border-border overflow-hidden">
                    <div className="px-5 py-4 bg-input/40 border-b border-border flex items-center gap-3">
                        <div className="w-8 h-8 bg-primary/10 rounded-lg flex items-center justify-center text-primary border border-primary/20">
                            <Building2 size={16} />
                        </div>
                        <div>
                            <h2 className="text-xs font-bold text-foreground tracking-tight">
                                {t("howToPayTitle")}
                            </h2>
                            <p className="text-[10px] text-muted font-medium">
                                {t("howToPaySub")}
                            </p>
                        </div>
                    </div>

                    <div className="p-5 space-y-4">
                        {/* Bank transfer */}
                        <div>
                            <h3 className="text-[10px] font-bold text-muted uppercase tracking-[0.15em] mb-2">
                                {t("bankTransferTitle")}
                            </h3>
                            <div className="bg-input/50 rounded-xl p-4 border border-border text-xs text-foreground space-y-1.5">
                                <p>
                                    <span className="text-muted font-semibold">{t("bankName")}: </span>
                                    {t("bankNameValue")}
                                </p>
                                <p>
                                    <span className="text-muted font-semibold">{t("accountName")}: </span>
                                    {t("accountNameValue")}
                                </p>
                                <p>
                                    <span className="text-muted font-semibold">{t("iban")}: </span>
                                    <span className="font-mono tracking-wider">{t("ibanValue")}</span>
                                </p>
                                <p className="text-[10px] text-muted pt-1">
                                    {t("bankTransferNote")}
                                </p>
                            </div>
                        </div>

                        {/* Office visit */}
                        <div>
                            <h3 className="text-[10px] font-bold text-muted uppercase tracking-[0.15em] mb-2">
                                {t("officeVisitTitle")}
                            </h3>
                            <div className="bg-input/50 rounded-xl p-4 border border-border text-xs text-foreground space-y-1.5">
                                <p>
                                    <span className="text-muted font-semibold">{t("address")}: </span>
                                    {t("addressValue")}
                                </p>
                                <p>
                                    <span className="text-muted font-semibold">{t("officeHours")}: </span>
                                    {t("officeHoursValue")}
                                </p>
                                <p>
                                    <span className="text-muted font-semibold">{t("phone")}: </span>
                                    {t("phoneValue")}
                                </p>
                            </div>
                        </div>

                        {/* TODO: integrate OrgSettings.penalty_payment_instructions endpoint
                            once a dedicated GET /api/proxy/v1/org/payment-instructions endpoint is built.
                            For now, copy is hardcoded in i18n bundles (RenterPenalties namespace). */}
                        <p className="text-[10px] text-muted italic">
                            {t("howToPayFooter")}
                        </p>
                    </div>
                </div>
            )}
        </div>
    );
}
