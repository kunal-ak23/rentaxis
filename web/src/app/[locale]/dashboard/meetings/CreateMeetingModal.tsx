"use client";

import { useState, useEffect, useCallback } from "react";
import { X, Loader2, ChevronLeft, ChevronRight, CalendarDays, Building2, MapPin } from "lucide-react";
import { useTranslations } from "next-intl";
import { cn } from "@/lib/utils";

// ── Types ──────────────────────────────────────────────────────────────────

interface Props {
    isOpen: boolean;
    onClose: () => void;
    onSuccess: () => void;
    session: any; // NextAuth session
}

type MeetingType = "OFFICE_VISIT" | "PROPERTY_VISIT";
type OfficePurpose = "CHEQUE_REPLACEMENT" | "LEASE_RENEWAL" | "OTHER";

type Lease = {
    id: string;
    propertyId?: string;
    propertyName: string;
    unitIdentifier: string;
    propertyManagerId?: string;
    status: string;
};

type Property = {
    id: string;
    nameEn: string;
    managerId?: string;
};

type Unit = {
    id: string;
    unitNumber: string;
};

type Slot = {
    start: string;
    end: string;
    available: boolean;
};

type PMUser = {
    id: string;
    fullName: string;
    email: string;
};

// ── Helpers ────────────────────────────────────────────────────────────────

const STEP_LABELS = ["Type", "Context", "Date & Slot", "Details", "Review"];

function formatTime(iso: string): string {
    return new Date(iso).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}

function buildSlotGrid(slotsFromApi: Slot[]): Slot[] {
    // Build a full 9AM–9PM grid of 30-min slots for the selected date
    // Overlay availability from the API response
    const apiMap: Record<string, boolean> = {};
    for (const s of slotsFromApi) {
        const key = new Date(s.start).toISOString();
        apiMap[key] = s.available;
    }

    const grid: Slot[] = [];
    for (let h = 9; h < 21; h++) {
        for (const m of [0, 30]) {
            const pad = (n: number) => String(n).padStart(2, "0");
            // We'll store as time strings; actual date will be known from context
            grid.push({
                start: `${pad(h)}:${pad(m)}`,
                end: h === 20 && m === 30 ? "21:00" : `${pad(m === 0 ? h : h + 1)}:${pad(m === 0 ? 30 : 0)}`,
                available: true, // default available; overridden by API
            });
        }
    }
    return grid;
}

// ── Main Component ─────────────────────────────────────────────────────────

export default function CreateMeetingModal({ isOpen, onClose, onSuccess, session }: Props) {
    const t = useTranslations("Meetings");
    const isRenter = session?.user?.role === "RENTER";

    // Step
    const [step, setStep] = useState(1);

    // Step 1
    const [meetingType, setMeetingType] = useState<MeetingType | "">("");

    // Step 2 — Office Visit
    const [officePurpose, setOfficePurpose] = useState<OfficePurpose | "">("");
    const [selectedLeaseId, setSelectedLeaseId] = useState("");
    const [leases, setLeases] = useState<Lease[]>([]);

    // Step 2 — Property Visit
    const [selectedPropertyId, setSelectedPropertyId] = useState("");
    const [selectedUnitId, setSelectedUnitId] = useState("");
    const [properties, setProperties] = useState<Property[]>([]);
    const [units, setUnits] = useState<Unit[]>([]);

    // Step 3
    const [selectedDate, setSelectedDate] = useState("");
    const [slots, setSlots] = useState<Slot[]>([]);
    const [slotsLoading, setSlotsLoading] = useState(false);
    const [selectedSlot, setSelectedSlot] = useState<Slot | null>(null);
    const [pmUsers, setPmUsers] = useState<PMUser[]>([]);
    const [selectedPmId, setSelectedPmId] = useState("");
    const [defaultHostId, setDefaultHostId] = useState<string>("");

    // Step 4 — Details
    const [notes, setNotes] = useState("");
    const [chequeNotes, setChequeNotes] = useState("");
    const [renewalStartDate, setRenewalStartDate] = useState("");
    const [renewalEndDate, setRenewalEndDate] = useState("");
    const [renewalRent, setRenewalRent] = useState("");

    // Submit
    const [submitting, setSubmitting] = useState(false);
    const [conflictMessage, setConflictMessage] = useState<string | null>(null);
    const [suggestedSlot, setSuggestedSlot] = useState<string | null>(null);

    // ── Reset on open ───────────────────────────────────────────────────

    useEffect(() => {
        if (isOpen) {
            setStep(1);
            setMeetingType("");
            setOfficePurpose("");
            setSelectedLeaseId("");
            setSelectedPropertyId("");
            setSelectedUnitId("");
            setSelectedDate("");
            setSlots([]);
            setSelectedSlot(null);
            setPmUsers([]);
            setSelectedPmId("");
            setDefaultHostId("");
            setNotes("");
            setChequeNotes("");
            setRenewalStartDate("");
            setRenewalEndDate("");
            setRenewalRent("");
            setConflictMessage(null);
            setSuggestedSlot(null);
        }
    }, [isOpen]);

    // ── Data fetching ───────────────────────────────────────────────────

    const fetchLeases = useCallback(async () => {
        try {
            const endpoint = isRenter ? "/api/proxy/v1/leases/my-leases" : "/api/proxy/v1/leases";
            const res = await fetch(endpoint);
            if (res.ok) {
                const data = await res.json();
                const arr = Array.isArray(data) ? data : data.content ?? [];
                const active = arr.filter((l: any) => l.status === "ACTIVE");
                setLeases(active.map((l: any) => ({
                    id: l.id,
                    propertyId: l.propertyId,
                    propertyName: l.propertyName ?? "—",
                    unitIdentifier: l.unitIdentifier ?? l.unitNumber ?? "—",
                    propertyManagerId: l.propertyManagerId,
                    status: l.status,
                })));
            }
        } catch { /* ignore */ }
    }, [isRenter]);

    const fetchProperties = useCallback(async () => {
        try {
            const res = await fetch("/api/proxy/v1/properties");
            if (res.ok) {
                const data = await res.json();
                const arr = Array.isArray(data) ? data : data.content ?? [];
                setProperties(arr.map((p: any) => ({
                    id: p.id,
                    nameEn: p.nameEn ?? p.name ?? "—",
                    managerId: p.managerId ?? p.propertyManagerId,
                })));
            }
        } catch { /* ignore */ }
    }, []);

    const fetchUnits = useCallback(async (propertyId: string) => {
        try {
            const res = await fetch(`/api/proxy/v1/units?propertyId=${propertyId}`);
            if (res.ok) {
                const data = await res.json();
                const arr = Array.isArray(data) ? data : data.content ?? [];
                setUnits(arr.map((u: any) => ({ id: u.id, unitNumber: u.unitNumber ?? u.number ?? "—" })));
            }
        } catch { /* ignore */ }
    }, []);

    const fetchPmUsers = useCallback(async (propertyId: string) => {
        if (!propertyId) return;
        try {
            const res = await fetch(`/api/proxy/v1/properties/${propertyId}/managers`);
            if (res.ok) {
                const arr: any[] = await res.json();
                setPmUsers(arr.map((u: any) => ({
                    id: u.id,
                    fullName: u.fullName ?? `${u.firstName ?? ""} ${u.lastName ?? ""}`.trim(),
                    email: u.email ?? "",
                })));
            }
        } catch { /* ignore */ }
    }, []);

    const fetchSlots = useCallback(async (date: string, hostUserId: string) => {
        if (!date || !hostUserId) return;
        setSlotsLoading(true);
        setSlots([]);
        setSelectedSlot(null);
        try {
            const res = await fetch(`/api/proxy/v1/meetings/slots?hostUserId=${hostUserId}&date=${date}`);
            if (res.ok) {
                const data = await res.json();
                setSlots(Array.isArray(data) ? data : data.slots ?? []);
            }
        } catch { /* ignore */ } finally {
            setSlotsLoading(false);
        }
    }, []);

    // Fetch leases when entering step 2 (office visit) or on open
    useEffect(() => {
        if (isOpen && meetingType === "OFFICE_VISIT") fetchLeases();
    }, [isOpen, meetingType, fetchLeases]);

    useEffect(() => {
        if (isOpen && meetingType === "PROPERTY_VISIT") fetchProperties();
    }, [isOpen, meetingType, fetchProperties]);

    useEffect(() => {
        if (selectedPropertyId) fetchUnits(selectedPropertyId);
        else setUnits([]);
        setPmUsers([]);
        setSelectedPmId("");
    }, [selectedPropertyId, fetchUnits]);

    // Derive hostUserId for slot fetching
    const deriveHostUserId = useCallback((): string => {
        if (meetingType === "OFFICE_VISIT") {
            const lease = leases.find((l) => l.id === selectedLeaseId);
            return lease?.propertyManagerId ?? selectedPmId ?? defaultHostId;
        }
        if (meetingType === "PROPERTY_VISIT") {
            const prop = properties.find((p) => p.id === selectedPropertyId);
            return prop?.managerId ?? selectedPmId ?? defaultHostId;
        }
        return selectedPmId ?? defaultHostId;
    }, [meetingType, leases, selectedLeaseId, properties, selectedPropertyId, selectedPmId, defaultHostId]);

    // Determine if we need to show PM picker (can't derive host)
    const needsPmPicker = useCallback((): boolean => {
        if (isRenter) return false;
        if (meetingType === "OFFICE_VISIT") {
            const lease = leases.find((l) => l.id === selectedLeaseId);
            return !lease?.propertyManagerId;
        }
        if (meetingType === "PROPERTY_VISIT") {
            const prop = properties.find((p) => p.id === selectedPropertyId);
            return !prop?.managerId;
        }
        return true;
    }, [isRenter, meetingType, leases, selectedLeaseId, properties, selectedPropertyId]);

    useEffect(() => {
        if (step !== 3 || !needsPmPicker()) return;
        const propertyId = meetingType === "PROPERTY_VISIT"
            ? selectedPropertyId
            : leases.find(l => l.id === selectedLeaseId)?.propertyId ?? "";
        if (propertyId) fetchPmUsers(propertyId);
    }, [step, meetingType, selectedPropertyId, selectedLeaseId, leases, needsPmPicker, fetchPmUsers]);

    // For renters: fetch the default host when entering step 3 (no PM picker shown)
    useEffect(() => {
        if (step === 3 && isRenter && !defaultHostId) {
            fetch("/api/proxy/v1/meetings/default-host")
                .then(r => r.ok ? r.json() : null)
                .then(data => { if (data?.userId) setDefaultHostId(data.userId); })
                .catch(() => {});
        }
    }, [step, isRenter, defaultHostId]);

    useEffect(() => {
        const hostId = deriveHostUserId();
        if (step === 3 && selectedDate && hostId) {
            fetchSlots(selectedDate, hostId);
        }
    }, [step, selectedDate, deriveHostUserId, fetchSlots]);

    // ── Navigation ──────────────────────────────────────────────────────

    const canGoNext = (): boolean => {
        if (step === 1) return meetingType !== "";
        if (step === 2) {
            if (meetingType === "OFFICE_VISIT") return officePurpose !== "" && selectedLeaseId !== "";
            if (meetingType === "PROPERTY_VISIT") return selectedPropertyId !== "";
        }
        if (step === 3) return selectedSlot !== null;
        if (step === 4) return true;
        return false;
    };

    const handleNext = () => {
        if (canGoNext() && step < 5) setStep((s) => s + 1);
    };

    const handleBack = () => {
        if (step > 1) setStep((s) => s - 1);
        setConflictMessage(null);
    };

    // ── Submit ──────────────────────────────────────────────────────────

    const handleSubmit = async () => {
        if (!selectedSlot) return;
        setSubmitting(true);
        setConflictMessage(null);

        // Build slot start/end ISO strings from selectedDate + slot times
        const toISO = (date: string, time: string) => `${date}T${time}:00`;

        const hostUserId = deriveHostUserId();

        const body: Record<string, any> = {
            type: meetingType,
            purpose: meetingType === "OFFICE_VISIT" ? officePurpose : "PROPERTY_VIEWING",
            slotStart: typeof selectedSlot.start === "string" && selectedSlot.start.includes("T")
                ? selectedSlot.start
                : toISO(selectedDate, selectedSlot.start),
            slotEnd: typeof selectedSlot.end === "string" && selectedSlot.end.includes("T")
                ? selectedSlot.end
                : toISO(selectedDate, selectedSlot.end),
            notes: notes || undefined,
        };

        if (hostUserId) body.hostUserId = hostUserId;
        if (meetingType === "OFFICE_VISIT") body.leaseId = selectedLeaseId;
        if (meetingType === "PROPERTY_VISIT") {
            body.propertyId = selectedPropertyId;
            if (selectedUnitId) body.unitId = selectedUnitId;
        }

        // Details step extras
        if (officePurpose === "CHEQUE_REPLACEMENT" && chequeNotes) {
            body.notes = chequeNotes;
        }
        if (officePurpose === "LEASE_RENEWAL") {
            if (renewalStartDate) body.proposedStartDate = renewalStartDate;
            if (renewalEndDate) body.proposedEndDate = renewalEndDate;
            if (renewalRent) body.proposedRentAmount = parseFloat(renewalRent);
        }

        try {
            const res = await fetch("/api/proxy/v1/meetings", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body),
            });

            if (res.status === 409) {
                const err = await res.json().catch(() => ({}));
                setConflictMessage(err.error ?? "This slot is already booked.");
                setSuggestedSlot(err.nextAvailableSlot ?? null);
                setSubmitting(false);
                return;
            }

            if (res.ok) {
                onSuccess();
                onClose();
            } else {
                const err = await res.json().catch(() => ({}));
                setConflictMessage(err.message ?? "Failed to create meeting. Please try again.");
            }
        } catch {
            setConflictMessage("An unexpected error occurred.");
        } finally {
            setSubmitting(false);
        }
    };

    // ── Slot grid ───────────────────────────────────────────────────────

    const slotGrid = (() => {
        if (slots.length > 0) return slots;
        // Generate default full grid when no API slots yet
        return buildSlotGrid([]).map((s) => ({ ...s, available: false }));
    })();

    // ── Render ──────────────────────────────────────────────────────────

    if (!isOpen) return null;

    const selectedLease = leases.find((l) => l.id === selectedLeaseId);
    const selectedProperty = properties.find((p) => p.id === selectedPropertyId);
    const selectedUnit = units.find((u) => u.id === selectedUnitId);
    const showPmPicker = step === 3 && needsPmPicker();

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
            {/* Backdrop */}
            <div className="absolute inset-0 bg-black/40" onClick={onClose} />

            {/* Modal */}
            <div className="relative bg-surface rounded-xl border border-border shadow-xl w-full max-w-lg mx-4 max-h-[90vh] flex flex-col">
                {/* Header */}
                <div className="flex items-center justify-between px-6 py-4 border-b border-border shrink-0">
                    <div>
                        <h2 className="text-sm font-bold text-foreground">{t("newMeeting")}</h2>
                        <p className="text-[10px] text-muted mt-0.5">Step {step} of 5 — {STEP_LABELS[step - 1]}</p>
                    </div>
                    <button onClick={onClose} className="p-1 text-muted hover:text-foreground cursor-pointer">
                        <X size={16} />
                    </button>
                </div>

                {/* Step progress bar */}
                <div className="flex px-6 pt-4 gap-1 shrink-0">
                    {STEP_LABELS.map((label, i) => (
                        <div
                            key={label}
                            className={cn(
                                "h-1 rounded-full flex-1 transition-colors",
                                i + 1 <= step ? "bg-primary" : "bg-border",
                            )}
                        />
                    ))}
                </div>

                {/* Body */}
                <div className="flex-1 overflow-y-auto px-6 py-5 space-y-4">

                    {/* ── Step 1: Meeting Type ─────────────────────────────── */}
                    {step === 1 && (
                        <div className="space-y-3">
                            <p className="text-xs font-semibold text-foreground">What type of meeting would you like to schedule?</p>
                            <div className="grid grid-cols-2 gap-3">
                                {(
                                    [
                                        { value: "OFFICE_VISIT", label: t("officeVisit"), description: "Visit the management office", icon: Building2 },
                                        { value: "PROPERTY_VISIT", label: t("propertyVisit"), description: "Visit at the property/unit", icon: MapPin },
                                    ] as const
                                ).map(({ value, label, description, icon: Icon }) => (
                                    <button
                                        key={value}
                                        onClick={() => setMeetingType(value)}
                                        className={cn(
                                            "flex flex-col items-center gap-2 p-4 rounded-xl border-2 text-center transition-all cursor-pointer",
                                            meetingType === value
                                                ? "border-primary bg-primary/5 text-primary"
                                                : "border-border bg-surface text-muted hover:border-primary/40 hover:bg-input/40",
                                        )}
                                    >
                                        <Icon size={24} />
                                        <span className="text-xs font-semibold text-foreground">{label}</span>
                                        <span className="text-[10px] text-muted">{description}</span>
                                    </button>
                                ))}
                            </div>
                        </div>
                    )}

                    {/* ── Step 2: Purpose + Context ────────────────────────── */}
                    {step === 2 && meetingType === "OFFICE_VISIT" && (
                        <div className="space-y-4">
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5">Purpose *</label>
                                <select
                                    value={officePurpose}
                                    onChange={(e) => setOfficePurpose(e.target.value as OfficePurpose)}
                                    className="w-full border border-border rounded-lg bg-surface px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:outline-none cursor-pointer"
                                >
                                    <option value="">Select purpose…</option>
                                    <option value="CHEQUE_REPLACEMENT">{t("chequeReplacement")}</option>
                                    <option value="LEASE_RENEWAL">{t("leaseRenewal")}</option>
                                    <option value="OTHER">{t("other")}</option>
                                </select>
                            </div>
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5">Related Lease *</label>
                                <select
                                    value={selectedLeaseId}
                                    onChange={(e) => setSelectedLeaseId(e.target.value)}
                                    className="w-full border border-border rounded-lg bg-surface px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:outline-none cursor-pointer"
                                >
                                    <option value="">Select lease…</option>
                                    {leases.map((l) => (
                                        <option key={l.id} value={l.id}>
                                            {l.propertyName} — Unit {l.unitIdentifier}
                                        </option>
                                    ))}
                                </select>
                                {leases.length === 0 && (
                                    <p className="text-[10px] text-muted mt-1">No active leases found.</p>
                                )}
                            </div>
                        </div>
                    )}

                    {step === 2 && meetingType === "PROPERTY_VISIT" && (
                        <div className="space-y-4">
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5">Property *</label>
                                <select
                                    value={selectedPropertyId}
                                    onChange={(e) => { setSelectedPropertyId(e.target.value); setSelectedUnitId(""); }}
                                    className="w-full border border-border rounded-lg bg-surface px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:outline-none cursor-pointer"
                                >
                                    <option value="">Select property…</option>
                                    {properties.map((p) => (
                                        <option key={p.id} value={p.id}>{p.nameEn}</option>
                                    ))}
                                </select>
                            </div>
                            {selectedPropertyId && (
                                <div>
                                    <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5">Unit (optional)</label>
                                    <select
                                        value={selectedUnitId}
                                        onChange={(e) => setSelectedUnitId(e.target.value)}
                                        className="w-full border border-border rounded-lg bg-surface px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:outline-none cursor-pointer"
                                    >
                                        <option value="">No specific unit</option>
                                        {units.map((u) => (
                                            <option key={u.id} value={u.id}>{u.unitNumber}</option>
                                        ))}
                                    </select>
                                </div>
                            )}
                        </div>
                    )}

                    {/* ── Step 3: Date + Slot Picker ───────────────────────── */}
                    {step === 3 && (
                        <div className="space-y-4">
                            {/* PM Picker — shown when we can't derive host */}
                            {showPmPicker && (
                                <div>
                                    <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5">Property Manager *</label>
                                    <select
                                        value={selectedPmId}
                                        onChange={(e) => setSelectedPmId(e.target.value)}
                                        className="w-full border border-border rounded-lg bg-surface px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:outline-none cursor-pointer"
                                    >
                                        <option value="">Select a property manager…</option>
                                        {pmUsers.map((u) => (
                                            <option key={u.id} value={u.id}>{u.fullName} ({u.email})</option>
                                        ))}
                                    </select>
                                </div>
                            )}

                            {/* Date */}
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5">Preferred Date *</label>
                                <input
                                    type="date"
                                    value={selectedDate}
                                    min={new Date().toISOString().split("T")[0]}
                                    onChange={(e) => { setSelectedDate(e.target.value); setSelectedSlot(null); }}
                                    className="w-full border border-border rounded-lg bg-surface px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:outline-none cursor-pointer"
                                />
                            </div>

                            {/* Slot Grid */}
                            {selectedDate && (
                                <div>
                                    <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-2">
                                        Available Slots
                                        {slotsLoading && <Loader2 size={10} className="inline ml-2 animate-spin" />}
                                    </label>
                                    {slotsLoading ? (
                                        <div className="flex items-center justify-center py-6">
                                            <Loader2 className="w-5 h-5 animate-spin text-primary opacity-60" />
                                        </div>
                                    ) : slotGrid.length === 0 ? (
                                        <p className="text-xs text-muted text-center py-4">No slots returned. Please pick a different date.</p>
                                    ) : (
                                        <div className="grid grid-cols-4 gap-1.5">
                                            {slotGrid.map((slot, idx) => {
                                                const timeLabel = typeof slot.start === "string" && slot.start.includes("T")
                                                    ? formatTime(slot.start)
                                                    : slot.start;
                                                const isSelected = selectedSlot === slot ||
                                                    (selectedSlot?.start === slot.start && selectedSlot?.end === slot.end);
                                                return (
                                                    <button
                                                        key={idx}
                                                        disabled={!slot.available}
                                                        onClick={() => setSelectedSlot(slot)}
                                                        className={cn(
                                                            "px-2 py-1.5 rounded-lg text-[10px] font-semibold border transition-all cursor-pointer",
                                                            !slot.available
                                                                ? "bg-input/50 text-muted/50 border-transparent cursor-not-allowed"
                                                                : isSelected
                                                                    ? "bg-primary text-primary-foreground border-primary"
                                                                    : "bg-success/10 text-success border-success/30 hover:bg-success/20",
                                                        )}
                                                    >
                                                        {timeLabel}
                                                    </button>
                                                );
                                            })}
                                        </div>
                                    )}
                                    {!deriveHostUserId() && !showPmPicker && (
                                        <p className="text-[10px] text-warning mt-2">
                                            Could not determine host. Please go back and verify your selection.
                                        </p>
                                    )}
                                </div>
                            )}
                        </div>
                    )}

                    {/* ── Step 4: Details ──────────────────────────────────── */}
                    {step === 4 && (
                        <div className="space-y-4">
                            {officePurpose === "CHEQUE_REPLACEMENT" ? (
                                <div>
                                    <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5">Cheque Details</label>
                                    <textarea
                                        value={chequeNotes}
                                        onChange={(e) => setChequeNotes(e.target.value)}
                                        placeholder="Which cheques are being replaced? (e.g. cheque #5, #6 — dated Jan, Feb)"
                                        rows={4}
                                        className="w-full border border-border rounded-lg bg-surface px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:outline-none resize-none"
                                    />
                                </div>
                            ) : officePurpose === "LEASE_RENEWAL" ? (
                                <div className="space-y-3">
                                    <div className="grid grid-cols-2 gap-3">
                                        <div>
                                            <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5">Proposed Start Date</label>
                                            <input
                                                type="date"
                                                value={renewalStartDate}
                                                onChange={(e) => setRenewalStartDate(e.target.value)}
                                                className="w-full border border-border rounded-lg bg-surface px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:outline-none"
                                            />
                                        </div>
                                        <div>
                                            <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5">Proposed End Date</label>
                                            <input
                                                type="date"
                                                value={renewalEndDate}
                                                onChange={(e) => setRenewalEndDate(e.target.value)}
                                                className="w-full border border-border rounded-lg bg-surface px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:outline-none"
                                            />
                                        </div>
                                    </div>
                                    <div>
                                        <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5">Proposed Rent Amount (AED)</label>
                                        <input
                                            type="number"
                                            value={renewalRent}
                                            onChange={(e) => setRenewalRent(e.target.value)}
                                            placeholder="e.g. 60000"
                                            min="0"
                                            className="w-full border border-border rounded-lg bg-surface px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:outline-none"
                                        />
                                    </div>
                                    <div>
                                        <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5">Additional Notes</label>
                                        <textarea
                                            value={notes}
                                            onChange={(e) => setNotes(e.target.value)}
                                            placeholder="Any additional context for the renewal discussion…"
                                            rows={3}
                                            className="w-full border border-border rounded-lg bg-surface px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:outline-none resize-none"
                                        />
                                    </div>
                                </div>
                            ) : (
                                <div>
                                    <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5">{t("notes")} (optional)</label>
                                    <textarea
                                        value={notes}
                                        onChange={(e) => setNotes(e.target.value)}
                                        placeholder="Any additional notes or requests for the meeting…"
                                        rows={5}
                                        className="w-full border border-border rounded-lg bg-surface px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:outline-none resize-none"
                                    />
                                </div>
                            )}
                        </div>
                    )}

                    {/* ── Step 5: Review ───────────────────────────────────── */}
                    {step === 5 && (
                        <div className="space-y-3">
                            <p className="text-xs font-semibold text-foreground mb-1">Review your meeting request</p>

                            <div className="bg-input/40 rounded-xl border border-border divide-y divide-border">
                                <ReviewRow label="Type" value={meetingType === "OFFICE_VISIT" ? t("officeVisit") : t("propertyVisit")} />
                                {meetingType === "OFFICE_VISIT" && (
                                    <>
                                        <ReviewRow label="Purpose" value={
                                            officePurpose === "CHEQUE_REPLACEMENT" ? t("chequeReplacement")
                                                : officePurpose === "LEASE_RENEWAL" ? t("leaseRenewal")
                                                    : t("other")
                                        } />
                                        <ReviewRow label="Lease" value={
                                            selectedLease
                                                ? `${selectedLease.propertyName} — Unit ${selectedLease.unitIdentifier}`
                                                : "—"
                                        } />
                                    </>
                                )}
                                {meetingType === "PROPERTY_VISIT" && (
                                    <>
                                        <ReviewRow label="Property" value={selectedProperty?.nameEn ?? "—"} />
                                        {selectedUnit && <ReviewRow label="Unit" value={selectedUnit.unitNumber} />}
                                    </>
                                )}
                                <ReviewRow label="Date" value={selectedDate} />
                                <ReviewRow label="Time Slot" value={
                                    selectedSlot
                                        ? `${typeof selectedSlot.start === "string" && selectedSlot.start.includes("T") ? formatTime(selectedSlot.start) : selectedSlot.start}` +
                                        ` – ${typeof selectedSlot.end === "string" && selectedSlot.end.includes("T") ? formatTime(selectedSlot.end) : selectedSlot.end}`
                                        : "—"
                                } />
                                {officePurpose === "CHEQUE_REPLACEMENT" && chequeNotes && (
                                    <ReviewRow label="Cheque Notes" value={chequeNotes} />
                                )}
                                {officePurpose === "LEASE_RENEWAL" && (
                                    <>
                                        {renewalStartDate && <ReviewRow label="Proposed Start" value={renewalStartDate} />}
                                        {renewalEndDate && <ReviewRow label="Proposed End" value={renewalEndDate} />}
                                        {renewalRent && <ReviewRow label="Proposed Rent" value={`AED ${renewalRent}`} />}
                                    </>
                                )}
                                {notes && <ReviewRow label="Notes" value={notes} />}
                            </div>

                            {/* Conflict error */}
                            {conflictMessage && (
                                <div className="bg-error/10 border border-error/30 rounded-lg px-4 py-3">
                                    <p className="text-xs font-semibold text-error mb-1">Scheduling Conflict</p>
                                    <p className="text-[10px] text-error/80">{conflictMessage}</p>
                                    {suggestedSlot && (
                                        <p className="text-[10px] text-muted mt-1">Suggested next available slot: <span className="font-semibold text-foreground">{suggestedSlot}</span></p>
                                    )}
                                </div>
                            )}
                        </div>
                    )}
                </div>

                {/* Footer */}
                <div className="flex items-center justify-between gap-3 px-6 py-4 border-t border-border shrink-0">
                    <button
                        onClick={step === 1 ? onClose : handleBack}
                        className="flex items-center gap-1 px-4 py-2 rounded-lg text-xs font-semibold text-muted hover:text-foreground hover:bg-input transition-colors cursor-pointer"
                    >
                        {step > 1 && <ChevronLeft size={14} />}
                        {step === 1 ? t("cancel") : "Back"}
                    </button>

                    {step < 5 ? (
                        <button
                            onClick={handleNext}
                            disabled={!canGoNext()}
                            className={cn(
                                "flex items-center gap-1 px-4 py-2 rounded-lg text-xs font-semibold transition-all cursor-pointer",
                                canGoNext()
                                    ? "bg-primary text-primary-foreground hover:bg-primary/90"
                                    : "bg-input text-muted cursor-not-allowed",
                            )}
                        >
                            Next <ChevronRight size={14} />
                        </button>
                    ) : (
                        <button
                            onClick={handleSubmit}
                            disabled={submitting}
                            className={cn(
                                "flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold transition-all cursor-pointer",
                                submitting
                                    ? "bg-input text-muted cursor-not-allowed"
                                    : "bg-primary text-primary-foreground hover:bg-primary/90",
                            )}
                        >
                            {submitting && <Loader2 size={12} className="animate-spin" />}
                            <CalendarDays size={13} />
                            {t("submit")}
                        </button>
                    )}
                </div>
            </div>
        </div>
    );
}

// ── Review Row Helper ──────────────────────────────────────────────────────

function ReviewRow({ label, value }: { label: string; value: string }) {
    return (
        <div className="flex items-start justify-between px-4 py-2.5 gap-4">
            <span className="text-[10px] font-semibold text-muted uppercase tracking-wider shrink-0">{label}</span>
            <span className="text-xs text-foreground text-end">{value}</span>
        </div>
    );
}
