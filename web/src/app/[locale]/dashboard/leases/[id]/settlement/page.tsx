"use client";

import { useState, useEffect, useCallback, useRef } from "react";
import { useParams, useRouter } from "next/navigation";
import { Link } from "@/i18n/routing";
import { cn } from "@/lib/utils";
import {
    ArrowLeft, Paperclip, Upload, Trash2, Image, Video, FileText, Plus,
    Loader2, CheckCircle2,
} from "lucide-react";

type AttachmentItem = {
    id: string;
    name: string;
    fileUrl: string;
    fileType: string;
    fileSize: number;
    uploadedAt: string;
};

type DeductionItem = {
    id?: string;
    category: string;
    description: string;
    amount: number;
    autoCalculated: boolean;
    attachments: AttachmentItem[];
};

type Settlement = {
    id: string;
    leaseId: string;
    depositAmount: number;
    totalDeductions: number;
    refundAmount: number;
    notes: string;
    status: string;
    settledBy: string;
    settledByName?: string;
    settledAt: string;
    createdAt: string;
    deductions: DeductionItem[];
};

type SettlementPreview = {
    depositAmount: number;
    unpaidRentTotal: number;
    penaltyTotal: number;
    suggestedRefund: number;
};

const MANUAL_CATEGORIES = [
    { value: "PROPERTY_DAMAGE", label: "Property Damage" },
    { value: "EARLY_TERMINATION_FEE", label: "Early Termination Fee" },
    { value: "CLEANING", label: "Cleaning" },
    { value: "UTILITY_ARREARS", label: "Utility Arrears" },
    { value: "KEY_REPLACEMENT", label: "Key Replacement" },
    { value: "OTHER", label: "Other" },
];

const DEDUCTION_CATEGORY_LABELS: Record<string, string> = {
    UNPAID_RENT: "Unpaid Rent",
    PENALTIES: "Late Penalties",
    PROPERTY_DAMAGE: "Property Damage",
    EARLY_TERMINATION_FEE: "Early Termination Fee",
    CLEANING: "Cleaning",
    UTILITY_ARREARS: "Utility Arrears",
    KEY_REPLACEMENT: "Key Replacement",
    OTHER: "Other",
};

const STATUS_COLORS: Record<string, string> = {
    DRAFT: "bg-input text-muted border-border",
    FINALIZED: "bg-success/10 text-success border-success/20",
};

function formatCurrency(amount: number): string {
    return new Intl.NumberFormat("en-AE", {
        style: "currency",
        currency: "AED",
        minimumFractionDigits: 0,
    }).format(amount);
}

function AttachmentThumbnail({
    attachment,
    onDelete,
    isDraft,
}: {
    attachment: AttachmentItem;
    onDelete: (id: string) => void;
    isDraft: boolean;
}) {
    const isImage = attachment.fileType?.startsWith("image/");
    const isVideo = attachment.fileType?.startsWith("video/");

    return (
        <div className="relative group w-16 h-16 rounded-lg border border-border bg-input/50 overflow-hidden flex items-center justify-center">
            {isImage ? (
                <img
                    src={attachment.fileUrl}
                    alt={attachment.name}
                    className="w-full h-full object-cover"
                />
            ) : isVideo ? (
                <Video size={20} className="text-muted" />
            ) : (
                <FileText size={20} className="text-muted" />
            )}
            <div className="absolute inset-0 bg-black/0 group-hover:bg-black/40 transition-all flex items-center justify-center">
                {isDraft && (
                    <button
                        onClick={() => onDelete(attachment.id)}
                        className="opacity-0 group-hover:opacity-100 p-1 bg-error text-white rounded-full cursor-pointer transition-opacity"
                        title="Delete attachment"
                    >
                        <Trash2 size={10} />
                    </button>
                )}
            </div>
            <p className="absolute bottom-0 left-0 right-0 bg-black/60 text-white text-[8px] truncate px-1 py-0.5 leading-tight">
                {attachment.name}
            </p>
        </div>
    );
}

export default function SettlementPage() {
    const params = useParams();
    const router = useRouter();
    const leaseId = params.id as string;
    const locale = params.locale as string;

    const [loading, setLoading] = useState(true);
    const [settlement, setSettlement] = useState<Settlement | null>(null);
    const [depositAmount, setDepositAmount] = useState(0);
    const [autoDeductions, setAutoDeductions] = useState<DeductionItem[]>([]);
    const [manualDeductions, setManualDeductions] = useState<DeductionItem[]>([]);
    const [notes, setNotes] = useState("");
    const [savingDraft, setSavingDraft] = useState(false);
    const [finalizing, setFinalizing] = useState(false);
    const [showFinalizeConfirm, setShowFinalizeConfirm] = useState(false);
    const [uploadingDeductionId, setUploadingDeductionId] = useState<string | null>(null);
    const fileInputRefs = useRef<Record<string, HTMLInputElement | null>>({});

    const isFinalized = settlement?.status === "FINALIZED";
    const isDraft = !isFinalized;
    const hasDraftId = !!settlement?.id;

    const totalDeductions = [
        ...autoDeductions,
        ...manualDeductions,
    ].reduce((sum, d) => sum + (d.amount || 0), 0);
    const refundAmount = depositAmount - totalDeductions;

    const fetchAttachmentsForDeduction = useCallback(async (deductionId: string): Promise<AttachmentItem[]> => {
        try {
            const res = await fetch(`/api/proxy/v1/settlements/deductions/${deductionId}/attachments`);
            if (res.ok) return await res.json();
        } catch {}
        return [];
    }, []);

    const loadSettlement = useCallback(async () => {
        try {
            const res = await fetch(`/api/proxy/v1/leases/${leaseId}/settlement`);
            if (res.ok) {
                const data = await res.json();
                const s: Settlement = {
                    ...data.settlement,
                    deductions: (data.deductions || []).map((d: DeductionItem) => ({
                        ...d,
                        attachments: d.attachments || [],
                    })),
                };
                setSettlement(s);
                setDepositAmount(s.depositAmount);
                setNotes(s.notes || "");

                const auto = s.deductions.filter((d: DeductionItem) => d.autoCalculated);
                const manual = s.deductions.filter((d: DeductionItem) => !d.autoCalculated);
                setAutoDeductions(auto);
                setManualDeductions(manual);
                return;
            }
        } catch {}

        // No existing settlement — load preview for suggestions
        try {
            const res = await fetch(`/api/proxy/v1/leases/${leaseId}/settlement/preview`);
            if (res.ok) {
                const preview: SettlementPreview = await res.json();
                setDepositAmount(preview.depositAmount);
                const auto: DeductionItem[] = [];
                if (preview.unpaidRentTotal > 0) {
                    auto.push({
                        category: "UNPAID_RENT",
                        description: "Outstanding rent",
                        amount: preview.unpaidRentTotal,
                        autoCalculated: true,
                        attachments: [],
                    });
                }
                if (preview.penaltyTotal > 0) {
                    auto.push({
                        category: "PENALTIES",
                        description: "Late payment penalties",
                        amount: preview.penaltyTotal,
                        autoCalculated: true,
                        attachments: [],
                    });
                }
                setAutoDeductions(auto);
            }
        } catch {}
    }, [leaseId]);

    useEffect(() => {
        loadSettlement().finally(() => setLoading(false));
    }, [loadSettlement]);

    const buildDeductionPayload = () => {
        return [
            ...autoDeductions.map(d => ({
                category: d.category,
                description: d.description,
                amount: d.amount,
                autoCalculated: true,
            })),
            ...manualDeductions.map(d => ({
                category: d.category,
                description: d.description,
                amount: d.amount,
                autoCalculated: false,
            })),
        ].filter(d => d.amount > 0);
    };

    const handleSaveDraft = async () => {
        setSavingDraft(true);
        try {
            const res = await fetch(`/api/proxy/v1/leases/${leaseId}/settlement/draft`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    notes: notes || undefined,
                    deductions: buildDeductionPayload(),
                }),
            });
            if (res.ok) {
                await loadSettlement();
            }
        } catch {} finally {
            setSavingDraft(false);
        }
    };

    const handleFinalize = async () => {
        setFinalizing(true);
        try {
            const res = await fetch(`/api/proxy/v1/leases/${leaseId}/settlement/finalize`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({}),
            });
            if (res.ok) {
                router.push(`/${locale}/dashboard/leases/${leaseId}`);
            }
        } catch {} finally {
            setFinalizing(false);
            setShowFinalizeConfirm(false);
        }
    };

    const handleFileUpload = async (deductionId: string, file: File) => {
        setUploadingDeductionId(deductionId);
        try {
            const formData = new FormData();
            formData.append("file", file);
            formData.append("name", file.name);
            const res = await fetch(
                `/api/upload?path=/api/v1/settlements/deductions/${deductionId}/attachments`,
                { method: "POST", body: formData }
            );
            if (res.ok) {
                const newAttachments = await fetchAttachmentsForDeduction(deductionId);
                // Update the deduction's attachments in both auto and manual lists
                setAutoDeductions(prev =>
                    prev.map(d => d.id === deductionId ? { ...d, attachments: newAttachments } : d)
                );
                setManualDeductions(prev =>
                    prev.map(d => d.id === deductionId ? { ...d, attachments: newAttachments } : d)
                );
            }
        } catch {} finally {
            setUploadingDeductionId(null);
        }
    };

    const handleDeleteAttachment = async (attachmentId: string, deductionId: string) => {
        try {
            const res = await fetch(`/api/proxy/v1/settlements/attachments/${attachmentId}`, {
                method: "DELETE",
            });
            if (res.ok) {
                const newAttachments = await fetchAttachmentsForDeduction(deductionId);
                setAutoDeductions(prev =>
                    prev.map(d => d.id === deductionId ? { ...d, attachments: newAttachments } : d)
                );
                setManualDeductions(prev =>
                    prev.map(d => d.id === deductionId ? { ...d, attachments: newAttachments } : d)
                );
            }
        } catch {}
    };

    if (loading) {
        return (
            <div className="flex items-center justify-center py-24">
                <Loader2 className="w-6 h-6 animate-spin text-primary opacity-60" />
            </div>
        );
    }

    return (
        <div className="max-w-2xl mx-auto">
            {/* Header */}
            <div className="flex items-center gap-4 mb-8">
                <Link
                    href={`/dashboard/leases/${leaseId}`}
                    className="p-2 rounded-lg hover:bg-input transition-colors text-muted hover:text-foreground"
                >
                    <ArrowLeft size={18} />
                </Link>
                <div className="flex-1">
                    <div className="flex items-center gap-3">
                        <h1 className="mb-0">Settlement</h1>
                        {settlement && (
                            <span className={cn(
                                "px-2.5 py-1 rounded-lg text-[10px] font-semibold border",
                                STATUS_COLORS[settlement.status] || "bg-input text-muted border-border"
                            )}>
                                {settlement.status}
                            </span>
                        )}
                    </div>
                    <p className="text-sm text-muted">Deposit settlement &amp; deductions</p>
                </div>
            </div>

            {/* Security Deposit */}
            <div className="bg-primary/5 border border-primary/20 rounded-xl px-5 py-4 mb-6">
                <p className="text-[10px] font-semibold text-muted uppercase tracking-wider mb-1">Security Deposit</p>
                <p className="text-2xl font-bold text-foreground tabular-nums">{formatCurrency(depositAmount)}</p>
            </div>

            <div className="space-y-6">
                {/* Auto-Calculated Deductions */}
                {autoDeductions.length > 0 && (
                    <div className="bg-surface rounded-xl border border-border">
                        <div className="px-5 py-3.5 border-b border-border">
                            <p className="text-[10px] font-semibold text-muted uppercase tracking-wider">Auto-Calculated Deductions</p>
                        </div>
                        <div className="p-4 space-y-3">
                            {autoDeductions.map((d, i) => (
                                <div key={i} className="bg-input/30 rounded-lg border border-border p-3 space-y-3">
                                    <div className="flex items-center gap-3">
                                        <div className="flex-1 min-w-0">
                                            <div className="flex items-center gap-2">
                                                <span className="text-xs font-medium text-foreground">
                                                    {DEDUCTION_CATEGORY_LABELS[d.category] || d.category}
                                                </span>
                                                <span className="px-1.5 py-0.5 rounded text-[8px] font-bold bg-info/10 text-info uppercase">Auto</span>
                                            </div>
                                            {d.description && (
                                                <p className="text-[10px] text-muted mt-0.5">{d.description}</p>
                                            )}
                                        </div>
                                        <div className="flex items-center gap-1.5 shrink-0">
                                            <span className="text-[10px] text-muted">AED</span>
                                            <input
                                                type="number"
                                                value={d.amount}
                                                min={0}
                                                step={0.01}
                                                disabled={isFinalized}
                                                onChange={(e) => {
                                                    const val = parseFloat(e.target.value) || 0;
                                                    setAutoDeductions(prev => prev.map((dd, ii) => ii === i ? { ...dd, amount: val } : dd));
                                                }}
                                                className="w-28 border border-border rounded-lg bg-surface px-3 py-1.5 text-xs text-foreground text-end tabular-nums focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none disabled:opacity-60 disabled:cursor-not-allowed"
                                            />
                                        </div>
                                    </div>

                                    {/* Attachments for auto deduction */}
                                    <div>
                                        <div className="flex items-center justify-between mb-2">
                                            <p className="text-[10px] font-semibold text-muted uppercase tracking-wider flex items-center gap-1">
                                                <Paperclip size={9} /> Attachments ({d.attachments.length}/10)
                                            </p>
                                            {d.id ? (
                                                d.attachments.length < 10 && (
                                                    <label className={cn(
                                                        "flex items-center gap-1 px-2 py-1 rounded-lg text-[9px] font-semibold transition-colors cursor-pointer",
                                                        uploadingDeductionId === d.id
                                                            ? "bg-input text-muted cursor-not-allowed"
                                                            : "bg-primary/10 text-primary hover:bg-primary/20"
                                                    )}>
                                                        {uploadingDeductionId === d.id
                                                            ? <Loader2 size={9} className="animate-spin" />
                                                            : <Upload size={9} />
                                                        }
                                                        Add Files
                                                        <input
                                                            ref={el => { fileInputRefs.current[d.id!] = el; }}
                                                            type="file"
                                                            accept="image/*,video/*,.pdf"
                                                            className="hidden"
                                                            disabled={uploadingDeductionId === d.id}
                                                            onChange={(e) => {
                                                                const file = e.target.files?.[0];
                                                                if (file && d.id) handleFileUpload(d.id, file);
                                                                if (e.target) e.target.value = "";
                                                            }}
                                                        />
                                                    </label>
                                                )
                                            ) : (
                                                <p className="text-[9px] text-muted italic">Save draft to enable file attachments</p>
                                            )}
                                        </div>
                                        {d.attachments.length > 0 && (
                                            <div className="flex flex-wrap gap-2">
                                                {d.attachments.map(att => (
                                                    <AttachmentThumbnail
                                                        key={att.id}
                                                        attachment={att}
                                                        onDelete={(id) => d.id && handleDeleteAttachment(id, d.id)}
                                                        isDraft={isDraft}
                                                    />
                                                ))}
                                            </div>
                                        )}
                                    </div>
                                </div>
                            ))}
                        </div>
                    </div>
                )}

                {/* Manual Deductions */}
                <div className="bg-surface rounded-xl border border-border">
                    <div className="px-5 py-3.5 border-b border-border flex items-center justify-between">
                        <p className="text-[10px] font-semibold text-muted uppercase tracking-wider">Manual Deductions</p>
                        {!isFinalized && (
                            <button
                                onClick={() => setManualDeductions(prev => [
                                    ...prev,
                                    { category: "PROPERTY_DAMAGE", description: "", amount: 0, autoCalculated: false, attachments: [] },
                                ])}
                                className="flex items-center gap-1 text-[10px] font-semibold text-primary hover:text-primary/80 cursor-pointer"
                            >
                                <Plus size={12} /> Add Deduction
                            </button>
                        )}
                    </div>
                    <div className="p-4">
                        {manualDeductions.length > 0 ? (
                            <div className="space-y-3">
                                {manualDeductions.map((d, i) => (
                                    <div key={i} className="bg-input/30 rounded-lg border border-border p-3 space-y-3">
                                        {/* Category, Amount, Delete */}
                                        <div className="flex items-center gap-2">
                                            <select
                                                value={d.category}
                                                disabled={isFinalized}
                                                onChange={(e) => setManualDeductions(prev =>
                                                    prev.map((dd, ii) => ii === i ? { ...dd, category: e.target.value } : dd)
                                                )}
                                                className="flex-1 border border-border rounded-lg bg-surface px-2 py-1.5 text-xs text-foreground focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none disabled:opacity-60 disabled:cursor-not-allowed"
                                            >
                                                {MANUAL_CATEGORIES.map(c => (
                                                    <option key={c.value} value={c.value}>{c.label}</option>
                                                ))}
                                            </select>
                                            <div className="flex items-center gap-1.5 shrink-0">
                                                <span className="text-[10px] text-muted">AED</span>
                                                <input
                                                    type="number"
                                                    value={d.amount}
                                                    min={0}
                                                    step={0.01}
                                                    placeholder="0"
                                                    disabled={isFinalized}
                                                    onChange={(e) => {
                                                        const val = parseFloat(e.target.value) || 0;
                                                        setManualDeductions(prev =>
                                                            prev.map((dd, ii) => ii === i ? { ...dd, amount: val } : dd)
                                                        );
                                                    }}
                                                    className="w-24 border border-border rounded-lg bg-surface px-3 py-1.5 text-xs text-foreground text-end tabular-nums focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none disabled:opacity-60 disabled:cursor-not-allowed"
                                                />
                                            </div>
                                            {!isFinalized && (
                                                <button
                                                    onClick={() => setManualDeductions(prev => prev.filter((_, ii) => ii !== i))}
                                                    className="p-1 text-error hover:text-error/80 cursor-pointer shrink-0"
                                                >
                                                    <Trash2 size={13} />
                                                </button>
                                            )}
                                        </div>

                                        {/* Description */}
                                        <input
                                            type="text"
                                            value={d.description}
                                            placeholder="Description (optional)"
                                            disabled={isFinalized}
                                            onChange={(e) => setManualDeductions(prev =>
                                                prev.map((dd, ii) => ii === i ? { ...dd, description: e.target.value } : dd)
                                            )}
                                            className="w-full border border-border rounded-lg bg-surface px-3 py-1.5 text-xs text-foreground placeholder:text-muted/50 focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none disabled:opacity-60 disabled:cursor-not-allowed"
                                        />

                                        {/* Attachments */}
                                        <div>
                                            <div className="flex items-center justify-between mb-2">
                                                <p className="text-[10px] font-semibold text-muted uppercase tracking-wider flex items-center gap-1">
                                                    <Paperclip size={9} /> Attachments ({d.attachments.length}/10)
                                                </p>
                                                {d.id ? (
                                                    d.attachments.length < 10 && (
                                                        <label className={cn(
                                                            "flex items-center gap-1 px-2 py-1 rounded-lg text-[9px] font-semibold transition-colors cursor-pointer",
                                                            uploadingDeductionId === d.id
                                                                ? "bg-input text-muted cursor-not-allowed"
                                                                : "bg-primary/10 text-primary hover:bg-primary/20"
                                                        )}>
                                                            {uploadingDeductionId === d.id
                                                                ? <Loader2 size={9} className="animate-spin" />
                                                                : <Upload size={9} />
                                                            }
                                                            Add Files
                                                            <input
                                                                ref={el => { fileInputRefs.current[d.id!] = el; }}
                                                                type="file"
                                                                accept="image/*,video/*,.pdf"
                                                                className="hidden"
                                                                disabled={uploadingDeductionId === d.id}
                                                                onChange={(e) => {
                                                                    const file = e.target.files?.[0];
                                                                    if (file && d.id) handleFileUpload(d.id, file);
                                                                    if (e.target) e.target.value = "";
                                                                }}
                                                            />
                                                        </label>
                                                    )
                                                ) : (
                                                    <p className="text-[9px] text-muted italic">Save draft to enable file attachments</p>
                                                )}
                                            </div>
                                            {d.attachments.length > 0 && (
                                                <div className="flex flex-wrap gap-2">
                                                    {d.attachments.map(att => (
                                                        <AttachmentThumbnail
                                                            key={att.id}
                                                            attachment={att}
                                                            onDelete={(id) => d.id && handleDeleteAttachment(id, d.id)}
                                                            isDraft={isDraft}
                                                        />
                                                    ))}
                                                </div>
                                            )}
                                        </div>
                                    </div>
                                ))}
                            </div>
                        ) : (
                            <p className="text-xs text-muted text-center py-4 bg-input/20 rounded-lg border border-dashed border-border">
                                No manual deductions added.
                            </p>
                        )}
                    </div>
                </div>

                {/* Notes */}
                <div className="bg-surface rounded-xl border border-border">
                    <div className="px-5 py-3.5 border-b border-border">
                        <p className="text-[10px] font-semibold text-muted uppercase tracking-wider">Notes</p>
                    </div>
                    <div className="p-4">
                        <textarea
                            value={notes}
                            disabled={isFinalized}
                            onChange={(e) => setNotes(e.target.value)}
                            placeholder="Settlement notes (optional)..."
                            rows={3}
                            className="w-full border border-border rounded-lg bg-surface px-3 py-2 text-xs text-foreground placeholder:text-muted/50 focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none resize-none disabled:opacity-60 disabled:cursor-not-allowed"
                        />
                    </div>
                </div>

                {/* Summary */}
                <div className="bg-surface rounded-xl border border-border">
                    <div className="px-5 py-3.5 border-b border-border">
                        <p className="text-[10px] font-semibold text-muted uppercase tracking-wider">Summary</p>
                    </div>
                    <div className="px-5 py-4 space-y-3">
                        <div className="flex justify-between items-center">
                            <span className="text-xs text-muted">Security Deposit</span>
                            <span className="text-xs font-semibold text-foreground tabular-nums">{formatCurrency(depositAmount)}</span>
                        </div>
                        <div className="flex justify-between items-center">
                            <span className="text-xs text-muted">Total Deductions</span>
                            <span className="text-xs font-semibold text-error tabular-nums">
                                {totalDeductions > 0 ? `- ${formatCurrency(totalDeductions)}` : formatCurrency(0)}
                            </span>
                        </div>
                        <div className="border-t-2 border-border pt-3 flex justify-between items-center">
                            <span className="text-sm font-bold text-foreground">Refund to Renter</span>
                            <span className={cn(
                                "text-sm font-bold tabular-nums",
                                refundAmount >= 0 ? "text-success" : "text-error"
                            )}>
                                {formatCurrency(refundAmount)}
                            </span>
                        </div>
                    </div>
                </div>

                {/* Action Buttons */}
                {!isFinalized && (
                    <div className="flex items-center justify-end gap-3 pb-8">
                        <button
                            onClick={handleSaveDraft}
                            disabled={savingDraft}
                            className={cn(
                                "flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold border transition-colors cursor-pointer",
                                savingDraft
                                    ? "border-border text-muted bg-input cursor-not-allowed"
                                    : "border-primary text-primary hover:bg-primary/5"
                            )}
                        >
                            {savingDraft ? <Loader2 size={12} className="animate-spin" /> : <CheckCircle2 size={12} />}
                            Save Draft
                        </button>
                        <button
                            onClick={() => setShowFinalizeConfirm(true)}
                            disabled={finalizing}
                            className={cn(
                                "flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold transition-colors cursor-pointer",
                                finalizing
                                    ? "bg-error/50 text-white cursor-not-allowed"
                                    : "bg-error text-white hover:bg-error/90"
                            )}
                        >
                            {finalizing ? <Loader2 size={12} className="animate-spin" /> : null}
                            Finalize &amp; Terminate
                        </button>
                    </div>
                )}

                {/* Finalized info */}
                {isFinalized && settlement && (
                    <div className="bg-success/5 border border-success/20 rounded-xl px-5 py-4 flex items-start gap-3 mb-8">
                        <CheckCircle2 size={16} className="text-success shrink-0 mt-0.5" />
                        <div>
                            <p className="text-xs font-semibold text-success">Settlement Finalized</p>
                            <p className="text-[10px] text-muted mt-0.5">
                                Settled by {settlement.settledByName || settlement.settledBy} on{" "}
                                {new Date(settlement.settledAt).toLocaleDateString()}.
                                Amounts are locked. Attachments can still be added.
                            </p>
                        </div>
                    </div>
                )}
            </div>

            {/* Finalize Confirmation Modal */}
            {showFinalizeConfirm && (
                <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
                    <div className="bg-surface rounded-xl border border-border shadow-xl w-full max-w-sm mx-4">
                        <div className="px-5 py-4 border-b border-border">
                            <h3 className="text-sm font-bold text-foreground">Finalize Settlement?</h3>
                        </div>
                        <div className="px-5 py-4">
                            <p className="text-xs text-muted leading-relaxed">
                                This will terminate the lease and lock the settlement amounts.
                                Attachments can still be added after. Continue?
                            </p>
                        </div>
                        <div className="px-5 py-3.5 border-t border-border flex items-center justify-end gap-3">
                            <button
                                onClick={() => setShowFinalizeConfirm(false)}
                                className="px-3 py-1.5 rounded-lg text-xs font-semibold text-muted hover:text-foreground border border-border hover:bg-input transition-colors cursor-pointer"
                            >
                                Cancel
                            </button>
                            <button
                                onClick={handleFinalize}
                                disabled={finalizing}
                                className={cn(
                                    "flex items-center gap-2 px-3 py-1.5 rounded-lg text-xs font-semibold transition-colors cursor-pointer",
                                    finalizing
                                        ? "bg-error/50 text-white cursor-not-allowed"
                                        : "bg-error text-white hover:bg-error/90"
                                )}
                            >
                                {finalizing && <Loader2 size={12} className="animate-spin" />}
                                Yes, Finalize &amp; Terminate
                            </button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
}
