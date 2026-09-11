"use client";

import { useState, useEffect, useCallback, useRef } from "react";
import { useParams, useRouter } from "next/navigation";
import { Link } from "@/i18n/routing";
import { cn } from "@/lib/utils";
import { formatCurrency } from "@/lib/format";
import {
    ArrowLeft, Paperclip, Upload, Trash2, Image, Video, FileText, Plus,
    Loader2, CheckCircle2, Eye, Download, X,
} from "lucide-react";
import { NumberInput } from "@/components/ui/NumberInput";

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
    type?: string;          // "DEDUCTION" or "ADDITION"
    additionCategory?: string;
};

type Settlement = {
    id: string;
    leaseId: string;
    depositAmount: number;
    totalDeductions: number;
    totalAdditions: number;
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

const ADDITION_CATEGORIES = [
    { value: "PREPAID_RENT", label: "Prepaid Rent" },
    { value: "UTILITY_OVERPAYMENT", label: "Utility Overpayment" },
    { value: "DEPOSIT_INTEREST", label: "Deposit Interest" },
    { value: "LANDLORD_COMPENSATION", label: "Landlord Compensation" },
    { value: "OTHER", label: "Other" },
];

const ADDITION_CATEGORY_LABELS: Record<string, string> = {
    PREPAID_RENT: "Prepaid Rent",
    UTILITY_OVERPAYMENT: "Utility Overpayment",
    DEPOSIT_INTEREST: "Deposit Interest",
    LANDLORD_COMPENSATION: "Landlord Compensation",
    OTHER: "Other",
};

function AttachmentThumbnail({
    attachment,
    onDelete,
    onPreview,
    isDraft,
}: {
    attachment: AttachmentItem;
    onDelete: (id: string) => void;
    onPreview: (attachment: AttachmentItem) => void;
    isDraft: boolean;
}) {
    const isImage = attachment.fileType?.startsWith("image/");
    const isVideo = attachment.fileType?.startsWith("video/");

    return (
        <div className="relative group w-16 h-16 rounded-lg border border-border bg-input/50 overflow-hidden flex items-center justify-center cursor-pointer"
            onClick={() => onPreview(attachment)}
        >
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
            <div className="absolute inset-0 bg-black/0 group-hover:bg-black/40 transition-all flex items-center justify-center gap-1">
                <button
                    onClick={(e) => { e.stopPropagation(); onPreview(attachment); }}
                    className="opacity-0 group-hover:opacity-100 p-1 bg-white/90 text-neutral-700 rounded-full cursor-pointer transition-opacity"
                    title="Preview"
                >
                    <Eye size={10} />
                </button>
                {isDraft && (
                    <button
                        onClick={(e) => { e.stopPropagation(); onDelete(attachment.id); }}
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

function AttachmentPreviewDialog({
    attachment,
    onClose,
}: {
    attachment: AttachmentItem | null;
    onClose: () => void;
}) {
    if (!attachment) return null;
    const isImage = attachment.fileType?.startsWith("image/");
    const isPdf = attachment.fileType === "application/pdf";
    const downloadUrl = `/api/proxy/v1/settlements/attachments/${attachment.id}/download`;

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4" onClick={onClose}>
            <div className="bg-white rounded-2xl shadow-2xl w-full max-w-3xl max-h-[90vh] flex flex-col overflow-hidden" onClick={e => e.stopPropagation()}>
                {/* Header */}
                <div className="flex items-center justify-between px-5 py-3 border-b border-border shrink-0">
                    <div className="flex items-center gap-2 min-w-0">
                        <FileText size={16} className="text-muted shrink-0" />
                        <span className="text-sm font-semibold text-neutral-800 truncate">{attachment.name}</span>
                    </div>
                    <div className="flex items-center gap-2 shrink-0 ml-3">
                        <a
                            href={downloadUrl}
                            download={attachment.name}
                            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-primary text-white text-xs font-semibold hover:opacity-90 transition-opacity"
                            onClick={e => e.stopPropagation()}
                        >
                            <Download size={13} />
                            Download
                        </a>
                        <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-neutral-100 transition-colors cursor-pointer">
                            <X size={16} className="text-muted" />
                        </button>
                    </div>
                </div>
                {/* Body */}
                <div className="flex-1 overflow-auto bg-neutral-50 flex items-center justify-center p-4">
                    {isImage ? (
                        <img src={attachment.fileUrl} alt={attachment.name} className="max-w-full max-h-[70vh] object-contain rounded-lg shadow" />
                    ) : isPdf ? (
                        <iframe src={attachment.fileUrl} title={attachment.name} className="w-full h-[70vh] rounded-lg border border-border" />
                    ) : (
                        <div className="flex flex-col items-center gap-4 py-12 text-neutral-500">
                            <FileText size={48} className="text-neutral-300" />
                            <p className="text-sm">{attachment.name}</p>
                            <a
                                href={downloadUrl}
                                download={attachment.name}
                                className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-primary text-white text-sm font-semibold hover:opacity-90 transition-opacity"
                            >
                                <Download size={15} />
                                Download to view
                            </a>
                        </div>
                    )}
                </div>
            </div>
        </div>
    );
}

export default function SettlementPage() {
    const params = useParams();
    const router = useRouter();
    const leaseId = params.id as string;
    const locale = params.locale as string;

    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [settlement, setSettlement] = useState<Settlement | null>(null);
    const [depositAmount, setDepositAmount] = useState(0);
    const [autoDeductions, setAutoDeductions] = useState<DeductionItem[]>([]);
    const [manualDeductions, setManualDeductions] = useState<DeductionItem[]>([]);
    const [additions, setAdditions] = useState<DeductionItem[]>([]);
    const [notes, setNotes] = useState("");
    const [savingDraft, setSavingDraft] = useState(false);
    const [finalizing, setFinalizing] = useState(false);
    const [showFinalizeConfirm, setShowFinalizeConfirm] = useState(false);
    const [uploadingDeductionId, setUploadingDeductionId] = useState<string | null>(null);
    const [saveDraftError, setSaveDraftError] = useState<string | null>(null);
    const [finalizeError, setFinalizeError] = useState<string | null>(null);
    const [previewAttachment, setPreviewAttachment] = useState<AttachmentItem | null>(null);
    const fileInputRefs = useRef<Record<string, HTMLInputElement | null>>({});

    const isFinalized = settlement?.status === "FINALIZED";
    const isDraft = !isFinalized;
    const hasDraftId = !!settlement?.id;

    const totalDeductions = [
        ...autoDeductions,
        ...manualDeductions,
    ].reduce((sum, d) => sum + (d.amount || 0), 0);
    const totalAdditions = additions.reduce((sum, d) => sum + (d.amount || 0), 0);
    const refundAmount = depositAmount - totalDeductions + totalAdditions;

    const fetchAttachmentsForDeduction = useCallback(async (deductionId: string): Promise<AttachmentItem[]> => {
        try {
            const res = await fetch(`/api/proxy/v1/settlements/deductions/${deductionId}/attachments`);
            if (res.ok) return await res.json();
        } catch (e) { console.error("Failed to fetch attachments:", e); }
        return [];
    }, []);

    const loadSettlement = useCallback(async () => {
        setLoadError(null);
        try {
            const res = await fetch(`/api/proxy/v1/leases/${leaseId}/settlement`);
            if (res.ok) {
                const data = await res.json();
                const s: Settlement = {
                    ...data,
                    deductions: (data.deductions || []).map((d: DeductionItem) => ({
                        ...d,
                        attachments: d.attachments || [],
                    })),
                };
                setSettlement(s);
                setDepositAmount(s.depositAmount);
                setNotes(s.notes || "");

                const auto = s.deductions.filter((d: DeductionItem) => d.autoCalculated);
                const manual = s.deductions.filter((d: DeductionItem) => !d.autoCalculated && d.type !== "ADDITION");
                const additionItems = s.deductions.filter((d: DeductionItem) => d.type === "ADDITION");
                setAutoDeductions(auto);
                setManualDeductions(manual);
                setAdditions(additionItems);
                return;
            }
            if (res.status !== 404) {
                // Only a 404 means "no settlement yet". A 500/403 while a
                // draft exists must not silently swap in the blank preview
                // editor — saving that would duplicate the draft's rows.
                setLoadError("Failed to load settlement. Please try again.");
                return;
            }
        } catch (e) {
            console.error("Failed to load settlement:", e);
            setLoadError("Failed to load settlement. Please try again.");
            return;
        }

        // 404 — no existing settlement, load preview for suggestions
        try {
            const res = await fetch(`/api/proxy/v1/leases/${leaseId}/settlement/preview`);
            if (!res.ok) {
                setLoadError("Failed to load settlement. Please try again.");
                return;
            }
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
        } catch (e) {
            console.error("Failed to load settlement preview:", e);
            setLoadError("Failed to load settlement. Please try again.");
        }
    }, [leaseId]);

    useEffect(() => {
        loadSettlement().finally(() => setLoading(false));
    }, [loadSettlement]);

    const buildDeductionPayload = () => {
        return [
            ...autoDeductions.map(d => ({
                id: d.id || undefined,
                category: d.category,
                description: d.description,
                amount: d.amount,
                autoCalculated: true,
                type: "DEDUCTION",
            })),
            ...manualDeductions.map(d => ({
                id: d.id || undefined,
                category: d.category,
                description: d.description,
                amount: d.amount,
                autoCalculated: false,
                type: "DEDUCTION",
            })),
            ...additions.map(d => ({
                id: d.id || undefined,
                additionCategory: d.additionCategory || d.category,
                description: d.description,
                amount: d.amount,
                autoCalculated: false,
                type: "ADDITION",
            })),
        ].filter(d => d.amount > 0);
    };

    const handleSaveDraft = async () => {
        setSavingDraft(true);
        setSaveDraftError(null);
        try {
            const res = await fetch(`/api/proxy/v1/leases/${leaseId}/settlement/draft`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    notes: notes || undefined,
                    deductions: buildDeductionPayload(),
                }),
            });
            if (!res.ok) {
                setSaveDraftError("Failed to save draft. Please try again.");
                return;
            }
            await loadSettlement();
        } catch {
            setSaveDraftError("Failed to save draft. Please try again.");
        } finally {
            setSavingDraft(false);
        }
    };

    const handleFinalize = async () => {
        setFinalizing(true);
        setFinalizeError(null);
        try {
            const res = await fetch(`/api/proxy/v1/leases/${leaseId}/settlement/finalize`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({}),
            });
            if (!res.ok) {
                setFinalizeError("Failed to finalize settlement. Please try again.");
                return;
            }
            setShowFinalizeConfirm(false);
            router.push(`/${locale}/dashboard/leases/${leaseId}`);
        } catch {
            setFinalizeError("Failed to finalize settlement. Please try again.");
        } finally {
            setFinalizing(false);
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
                setAdditions(prev =>
                    prev.map(d => d.id === deductionId ? { ...d, attachments: newAttachments } : d)
                );
            }
        } catch (e) { console.error("Failed to upload file:", e); } finally {
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
                setAdditions(prev =>
                    prev.map(d => d.id === deductionId ? { ...d, attachments: newAttachments } : d)
                );
            }
        } catch (e) { console.error("Failed to delete attachment:", e); }
    };

    if (loading) {
        return (
            <div className="flex items-center justify-center py-24">
                <Loader2 className="w-6 h-6 animate-spin text-primary opacity-60" />
            </div>
        );
    }

    if (loadError) {
        return (
            <div className="max-w-2xl mx-auto flex flex-col items-center gap-4 py-24">
                <p className="text-sm text-error">{loadError}</p>
                <button
                    onClick={() => {
                        setLoading(true);
                        loadSettlement().finally(() => setLoading(false));
                    }}
                    className="px-4 py-2 rounded-lg bg-primary text-white text-sm font-semibold hover:opacity-90 transition-opacity cursor-pointer"
                >
                    Retry
                </button>
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
                                <div key={d.id ?? i} className="bg-input/30 rounded-lg border border-border p-3 space-y-3">
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
                                            <NumberInput
                                                value={d.amount}
                                                min={0}
                                                step={0.01}
                                                disabled={isFinalized}
                                                onChange={(v) => {
                                                    const val = v;
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
                                                        onPreview={setPreviewAttachment}
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
                                    <div key={d.id ?? i} className="bg-input/30 rounded-lg border border-border p-3 space-y-3">
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
                                                <NumberInput
                                                    value={d.amount}
                                                    min={0}
                                                    step={0.01}
                                                    placeholder="0"
                                                    disabled={isFinalized}
                                                    onChange={(v) => {
                                                        const val = v;
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
                                                            onPreview={setPreviewAttachment}
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

                {/* Additions (Repayments to Renter) */}
                <div className="bg-surface rounded-xl border border-success/30">
                    <div className="px-5 py-3.5 border-b border-success/20 flex items-center justify-between">
                        <p className="text-[10px] font-semibold text-success uppercase tracking-wider">Additions (Repayments)</p>
                        {!isFinalized && (
                            <button
                                onClick={() => setAdditions(prev => [
                                    ...prev,
                                    { category: "PREPAID_RENT", additionCategory: "PREPAID_RENT", description: "", amount: 0, autoCalculated: false, attachments: [], type: "ADDITION" },
                                ])}
                                className="flex items-center gap-1 text-[10px] font-semibold text-success hover:text-success/80 cursor-pointer"
                            >
                                <Plus size={12} /> Add Repayment
                            </button>
                        )}
                    </div>
                    <div className="p-4">
                        {additions.length > 0 ? (
                            <div className="space-y-3">
                                {additions.map((d, i) => (
                                    <div key={d.id ?? `add-${i}`} className="bg-success/5 rounded-lg border border-success/20 p-3 space-y-3">
                                        {/* Category, Amount, Delete */}
                                        <div className="flex items-center gap-2">
                                            <select
                                                value={d.additionCategory || d.category}
                                                disabled={isFinalized}
                                                onChange={(e) => setAdditions(prev =>
                                                    prev.map((dd, ii) => ii === i ? { ...dd, additionCategory: e.target.value, category: e.target.value } : dd)
                                                )}
                                                className="flex-1 border border-success/30 rounded-lg bg-surface px-2 py-1.5 text-xs text-foreground focus:ring-2 focus:ring-success/20 focus:border-success focus:outline-none disabled:opacity-60 disabled:cursor-not-allowed"
                                            >
                                                {ADDITION_CATEGORIES.map(c => (
                                                    <option key={c.value} value={c.value}>{c.label}</option>
                                                ))}
                                            </select>
                                            <div className="flex items-center gap-1.5 shrink-0">
                                                <span className="text-[10px] text-success font-semibold">+ AED</span>
                                                <NumberInput
                                                    value={d.amount}
                                                    min={0}
                                                    step={0.01}
                                                    placeholder="0"
                                                    disabled={isFinalized}
                                                    onChange={(v) => {
                                                        const val = v;
                                                        setAdditions(prev =>
                                                            prev.map((dd, ii) => ii === i ? { ...dd, amount: val } : dd)
                                                        );
                                                    }}
                                                    className="w-24 border border-success/30 rounded-lg bg-surface px-3 py-1.5 text-xs text-foreground text-end tabular-nums focus:ring-2 focus:ring-success/20 focus:border-success focus:outline-none disabled:opacity-60 disabled:cursor-not-allowed"
                                                />
                                            </div>
                                            {!isFinalized && (
                                                <button
                                                    onClick={() => setAdditions(prev => prev.filter((_, ii) => ii !== i))}
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
                                            onChange={(e) => setAdditions(prev =>
                                                prev.map((dd, ii) => ii === i ? { ...dd, description: e.target.value } : dd)
                                            )}
                                            className="w-full border border-success/20 rounded-lg bg-surface px-3 py-1.5 text-xs text-foreground placeholder:text-muted/50 focus:ring-2 focus:ring-success/20 focus:border-success focus:outline-none disabled:opacity-60 disabled:cursor-not-allowed"
                                        />

                                        {/* Attachments — same pattern as deductions */}
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
                                                                : "bg-success/10 text-success hover:bg-success/20"
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
                                                            onPreview={setPreviewAttachment}
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
                            <p className="text-xs text-muted text-center py-4 bg-success/5 rounded-lg border border-dashed border-success/20">
                                No additions. Add repayments owed to the renter.
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
                        {totalAdditions > 0 && (
                            <div className="flex justify-between items-center">
                                <span className="text-xs text-muted">Total Additions</span>
                                <span className="text-xs font-semibold text-success tabular-nums">
                                    + {formatCurrency(totalAdditions)}
                                </span>
                            </div>
                        )}
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
                    <div className="space-y-2 pb-8">
                        {saveDraftError && (
                            <p className="text-xs text-error text-end">{saveDraftError}</p>
                        )}
                    <div className="flex items-center justify-end gap-3">
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
                        <div className="px-5 py-4 space-y-3">
                            <p className="text-xs text-muted leading-relaxed">
                                This will terminate the lease and lock the settlement amounts.
                                Attachments can still be added after. Continue?
                            </p>
                            {finalizeError && (
                                <p className="text-xs text-error">{finalizeError}</p>
                            )}
                        </div>
                        <div className="px-5 py-3.5 border-t border-border flex items-center justify-end gap-3">
                            <button
                                onClick={() => { setShowFinalizeConfirm(false); setFinalizeError(null); }}
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

            <AttachmentPreviewDialog
                attachment={previewAttachment}
                onClose={() => setPreviewAttachment(null)}
            />
        </div>
    );
}
