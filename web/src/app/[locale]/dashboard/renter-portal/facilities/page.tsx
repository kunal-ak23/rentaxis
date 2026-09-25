"use client";

import { useState, useEffect, useCallback, useMemo } from "react";
import { useTranslations, useLocale } from "next-intl";
import { useSession } from "next-auth/react";
import { ArrowLeft, Dumbbell, Car, Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";
import { Link } from "@/i18n/routing";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import {
    fetchMyFacilities,
    fetchMyBookings,
    createBooking,
    cancelBooking,
    releaseBooking,
    throwIfNotOk,
    ApiError,
} from "@/lib/api/facilities";
import type {
    MyFacilitiesDTO,
    BookingRequestDTO,
    BookingRequestStatus,
    BookingResourceType,
} from "@/types/facility";

const STATUS_CLASSES: Record<BookingRequestStatus, string> = {
    PENDING: "bg-warning/10 text-warning border border-warning/20",
    APPROVED: "bg-success/10 text-success border border-success/20",
    REJECTED: "bg-error/10 text-error border border-error/20",
    CANCELLED: "bg-input text-muted border border-border",
    RELEASED: "bg-info/10 text-info border border-info/20",
};

type ActiveLease = {
    id: string;
    unitId: string;
    unitIdentifier: string;
    propertyId: string;
    propertyName: string;
    status: string;
};

type RequestTarget = {
    resourceType: BookingResourceType;
    resourceId: string;
    name: string;
    propertyId: string;
};

type PendingAction = { kind: "cancel" | "release"; booking: BookingRequestDTO };

/** Locale tag for toLocaleDateString — mirrors gatepass/page.tsx's ar-AE/en-GB split. */
function dateLocale(locale: string): string {
    return locale === "ar" ? "ar-AE" : "en-GB";
}

/**
 * Parses a `YYYY-MM-DD` date-only string (BookingRequestDTO.preferredDate is
 * a LocalDate) as a local calendar date rather than `new Date(str)`, which
 * the spec parses as UTC midnight — a renter picking the 9th would render as
 * the 8th for anyone west of UTC. Same fix as dashboard/bookings/page.tsx's
 * parseDateOnly; createdAt is an Instant and doesn't need it.
 */
function parseDateOnly(value: string): Date {
    const [y, m, d] = value.split("-").map(Number);
    return new Date(y, m - 1, d);
}

/** Formats a local `Date` as `YYYY-MM-DD` — mirrors gatepass/page.tsx's toDateInput. */
function toDateInput(date: Date): string {
    const pad = (n: number) => String(n).padStart(2, "0");
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

export default function RenterFacilitiesPage() {
    const t = useTranslations("Facilities");
    const tB = useTranslations("Bookings");
    const locale = useLocale();
    const { status: sessionStatus } = useSession();

    const [facilities, setFacilities] = useState<MyFacilitiesDTO | null>(null);
    const [bookings, setBookings] = useState<BookingRequestDTO[]>([]);
    const [activeLeases, setActiveLeases] = useState<ActiveLease[]>([]);
    // Distinguishes "leases fetch hasn't succeeded yet" (incl. failed) from
    // "fetch succeeded and the renter truly has zero active leases" — the
    // noActiveLease banner must only render for the latter.
    const [leasesOk, setLeasesOk] = useState(false);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    // Request dialog state
    const [target, setTarget] = useState<RequestTarget | null>(null);
    const [unitId, setUnitId] = useState("");
    const [preferredDate, setPreferredDate] = useState("");
    const [note, setNote] = useState("");
    const [submitting, setSubmitting] = useState(false);
    const [dialogError, setDialogError] = useState<string | null>(null);

    // Cancel / release confirmation
    const [pendingAction, setPendingAction] = useState<PendingAction | null>(null);
    const [actionLoading, setActionLoading] = useState(false);

    const loadFacilities = useCallback(async () => {
        setFacilities(await fetchMyFacilities());
    }, []);

    const loadBookings = useCallback(async () => {
        setBookings(await fetchMyBookings());
    }, []);

    const loadLeases = useCallback(async () => {
        const res = await fetch("/api/proxy/v1/leases/my-leases");
        await throwIfNotOk(res);
        const all: ActiveLease[] = await res.json();
        setActiveLeases(all.filter(l => l.status === "ACTIVE"));
        setLeasesOk(true);
    }, []);

    useEffect(() => {
        if (sessionStatus !== "authenticated") return;
        (async () => {
            setLoading(true);
            setError(null);
            try {
                await Promise.all([loadFacilities(), loadBookings(), loadLeases()]);
            } catch (err) {
                setError(err instanceof ApiError ? err.message : t("loadError"));
            } finally {
                setLoading(false);
            }
        })();
    }, [sessionStatus, loadFacilities, loadBookings, loadLeases, t]);

    const openRequest = (rt: RequestTarget) => {
        setTarget(rt);
        setPreferredDate("");
        setNote("");
        setDialogError(null);
        // Only leases against the resource's own property are valid — a
        // renter with units in two properties must not be able to submit
        // against a unit in the wrong one (the backend 404s with a raw
        // English "Amenity not found" if they do).
        const matching = activeLeases.filter(l => l.propertyId === rt.propertyId);
        setUnitId(matching[0]?.unitId ?? "");
    };

    // Escape closes the request dialog while it's open — document-level
    // listener (same pattern as BookingDetailDrawer.tsx) rather than a
    // backdrop onKeyDown, which only fires when focus is already inside it.
    useEffect(() => {
        if (!target) return;
        const handler = (e: KeyboardEvent) => { if (e.key === "Escape") setTarget(null); };
        document.addEventListener("keydown", handler);
        return () => document.removeEventListener("keydown", handler);
    }, [target]);

    const submitRequest = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!target || !unitId) return;
        setSubmitting(true);
        setDialogError(null);
        try {
            await createBooking({
                resourceType: target.resourceType,
                resourceId: target.resourceId,
                unitId,
                preferredDate: preferredDate || undefined,
                note: note || undefined,
            });
        } catch (err) {
            // 409 (spot already held elsewhere) gets the localized copy; 400
            // (e.g. requesting a non-bookable amenity) and any other ApiError
            // status surface the backend's own already-parsed message.
            if (err instanceof ApiError && err.status === 409) {
                setDialogError(tB("spotConflict"));
                // The spot became APPROVED for someone else after this page's
                // data was fetched, so the card behind the dialog still says
                // "Available" with an enabled Request button inviting another
                // guaranteed 409 — resync both lists so held/pendingCount
                // match the server (mirrors the mobile renter sheet's
                // invalidate-on-close in facilities_screen.dart).
                try {
                    await Promise.all([loadFacilities(), loadBookings()]);
                } catch { /* best-effort resync; the conflict message still shows */ }
            }
            else if (err instanceof ApiError) setDialogError(err.message);
            else setDialogError(t("requestError"));
            setSubmitting(false);
            return;
        }
        // The booking now exists — close the dialog regardless of what
        // happens next. A failure refreshing the lists below is a stale-UI
        // problem, not a failed request, so it must not be reported as one
        // (and dialogError can no longer be seen once the dialog is closed).
        setTarget(null);
        setSubmitting(false);
        try {
            await Promise.all([loadFacilities(), loadBookings()]);
        } catch {
            setError(t("refreshError"));
        }
    };

    const runPendingAction = async () => {
        if (!pendingAction) return;
        setActionLoading(true);
        try {
            if (pendingAction.kind === "cancel") await cancelBooking(pendingAction.booking.id);
            else await releaseBooking(pendingAction.booking.id);
        } catch (err) {
            setPendingAction(null);
            setActionLoading(false);
            // A 400/409 means the server state already moved (an admin
            // decided the request while this page sat open) — the row's
            // status, its Cancel/Release button, and the held/pendingCount
            // facts on the cards above are all stale, and retrying can only
            // re-fail. Resync both lists and say so in the active locale
            // instead of surfacing the raw backend English string — mirrors
            // the mobile renter app's 400 branch in my_requests_screen.dart.
            if (err instanceof ApiError && (err.status === 400 || err.status === 409)) {
                setError(t("requestChanged"));
                try {
                    await Promise.all([loadFacilities(), loadBookings()]);
                } catch {
                    setError(t("refreshError"));
                }
            } else {
                setError(err instanceof ApiError ? err.message : t("requestError"));
            }
            return;
        }
        setPendingAction(null);
        setActionLoading(false);
        try {
            await Promise.all([loadFacilities(), loadBookings()]);
        } catch {
            setError(t("refreshError"));
        }
    };

    const feeLabel = (type?: string, amount?: number | null) =>
        !type || type === "FREE" || !amount ? t("feeFree")
            : t(type === "PER_HOUR" ? "feePerHour" : "feePerBooking", { amount: amount.toFixed(2) });
    const amenityName = (nameEn: string, nameAr: string | null) =>
        locale === "ar" && nameAr ? nameAr : nameEn;

    /**
     * The caller's own PENDING/APPROVED request per resource id, keyed off
     * `bookings` (already fetched for the My Requests table below). Mirrors
     * mobile's `_openByResource` in facilities_screen.dart — terminal
     * statuses (REJECTED/CANCELLED/RELEASED) don't block a new request, so
     * only PENDING/APPROVED are indexed. `fetchMyBookings` is createdAt ASC,
     * so a later entry for the same resource overwrites an earlier one,
     * landing on the newest open request.
     */
    const openByResource = useMemo(() => {
        const map = new Map<string, BookingRequestDTO>();
        for (const b of bookings) {
            if (b.status !== "PENDING" && b.status !== "APPROVED") continue;
            const resourceId = b.amenityId ?? b.parkingSpotId;
            if (resourceId) map.set(resourceId, b);
        }
        return map;
    }, [bookings]);

    if (sessionStatus === "loading") {
        return (
            <div className="p-8 max-w-5xl mx-auto flex flex-col items-center justify-center h-64 gap-3">
                <Loader2 size={24} className="animate-spin text-muted" />
                <p className="text-xs text-muted">{t("loading")}</p>
            </div>
        );
    }

    if (loading) {
        return (
            <div className="p-8 max-w-5xl mx-auto flex flex-col items-center justify-center h-64 gap-3">
                <Loader2 size={24} className="animate-spin text-muted" />
                <p className="text-xs text-muted">{t("loading")}</p>
            </div>
        );
    }

    const amenities = facilities?.amenities ?? [];
    const parkingSpots = facilities?.parkingSpots ?? [];
    // Only true once the leases fetch has actually succeeded — a failed
    // fetch (see loadLeases/throwIfNotOk) must surface as the page-level
    // error banner above, not be misread as "you have zero active leases".
    const noActiveLease = leasesOk && activeLeases.length === 0;

    return (
        <div className="p-8 max-w-5xl mx-auto">
            <Link
                href="/dashboard/renter-portal"
                className="flex items-center gap-2 text-xs font-bold text-muted hover:text-foreground mb-6 transition-colors cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none rounded w-fit"
            >
                <ArrowLeft size={14} className="rtl:rotate-180" /> {t("backToPortal")}
            </Link>

            <div className="mb-8">
                <h1 className="text-xl font-bold text-foreground tracking-tight mb-1">{t("renterTitle")}</h1>
                <p className="text-xs text-muted font-medium">{t("renterSubtitle")}</p>
            </div>

            {error && (
                <div className="mb-4 px-4 py-3 rounded-xl bg-error/10 text-error border border-error/20 text-xs font-semibold">
                    {error}
                </div>
            )}

            {noActiveLease && (
                <div className="mb-6 px-4 py-3 rounded-xl bg-warning/10 text-warning border border-warning/20 text-xs font-semibold">
                    {t("noActiveLease")}
                </div>
            )}

            {amenities.length === 0 && parkingSpots.length === 0 && (
                <div className="text-center py-16 bg-background border border-dashed border-border rounded-xl text-xs font-medium text-muted">
                    {t("noFacilities")}
                </div>
            )}

            {/* ── Amenities section ─────────────────────────────────── */}
            {amenities.length > 0 && (
                <div className="mb-10">
                    <div className="flex items-center gap-2 mb-4">
                        <Dumbbell size={16} className="text-primary" />
                        <h2 className="text-sm font-bold text-foreground tracking-tight">{t("amenitiesSection")}</h2>
                    </div>
                    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
                        {amenities.map(a => {
                            const myOpen = openByResource.get(a.id);
                            // Only a PENDING own-request blocks a new one — mirrors
                            // facilities_screen.dart's `_AmenityCard`. There is no
                            // transition out of APPROVED for an amenity (unlike a
                            // parking spot, which `held` already covers), and
                            // create-idempotency only short-circuits a PENDING
                            // duplicate, so gating on `myOpen != null` would make a
                            // once-approved amenity unbookable forever. The
                            // APPROVED badge below is informational only.
                            const blocking = myOpen?.status === "PENDING";
                            return (
                            <div key={a.id} className="bg-surface rounded-xl p-4 border border-border hover:shadow-md transition-all duration-200 flex flex-col gap-2">
                                <div className="flex items-start justify-between gap-2">
                                    <div>
                                        <p className="text-sm font-bold text-foreground">{amenityName(a.nameEn, a.nameAr)}</p>
                                        {/* F14-50: the fee, shown before the renter books. */}
                                        <p className="text-[11px] text-muted" data-testid={`amenity-fee-${a.id}`}>{feeLabel(a.feeType, a.feeAmount)}</p>
                                    </div>
                                    {myOpen ? (
                                        <span className={cn(
                                            "px-2 py-0.5 text-[9px] font-bold uppercase tracking-widest rounded-md shrink-0",
                                            STATUS_CLASSES[myOpen.status]
                                        )}>
                                            {tB(`status${myOpen.status}`)}
                                        </span>
                                    ) : !a.bookable && (
                                        <span className="px-2 py-0.5 text-[9px] font-bold uppercase tracking-widest bg-input text-muted rounded-md shrink-0">
                                            {t("notBookable")}
                                        </span>
                                    )}
                                </div>
                                {a.propertyName && <p className="text-[10px] font-bold text-muted">{a.propertyName}</p>}
                                {a.description && <p className="text-xs text-muted line-clamp-2">{a.description}</p>}
                                {a.bookable && (
                                    <p className="text-[10px] text-muted">{t("pendingHint", { count: a.pendingCount })}</p>
                                )}
                                {a.bookable && !blocking && (
                                    <button
                                        onClick={() => openRequest({ resourceType: "AMENITY", resourceId: a.id, name: amenityName(a.nameEn, a.nameAr), propertyId: a.propertyId })}
                                        disabled={noActiveLease}
                                        className="mt-auto self-start px-4 py-2 bg-primary/10 text-primary hover:bg-primary/20 rounded-xl text-xs font-bold transition-all duration-200 cursor-pointer focus:outline-none focus:ring-2 focus:ring-primary/30 disabled:opacity-50 disabled:cursor-not-allowed"
                                    >
                                        {t("request")}
                                    </button>
                                )}
                            </div>
                            );
                        })}
                    </div>
                </div>
            )}

            {/* ── Parking section ───────────────────────────────────── */}
            {parkingSpots.length > 0 && (
                <div className="mb-10">
                    <div className="flex items-center gap-2 mb-4">
                        <Car size={16} className="text-primary" />
                        <h2 className="text-sm font-bold text-foreground tracking-tight">{t("parkingSection")}</h2>
                    </div>
                    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
                        {parkingSpots.map(s => {
                            const myOpen = openByResource.get(s.id);
                            return (
                            <div key={s.id} className="bg-surface rounded-xl p-4 border border-border hover:shadow-md transition-all duration-200 flex flex-col gap-2">
                                <div className="flex items-start justify-between gap-2">
                                    <div>
                                        <p className="text-sm font-bold text-foreground" dir="ltr">{s.spotNumber}</p>
                                        <p className="text-[11px] text-muted" data-testid={`spot-fee-${s.id}`}>{feeLabel(s.feeType, s.feeAmount)}</p>
                                    </div>
                                    {/* The held gate itself is unchanged (a PENDING
                                        own-request doesn't block a spot the way it
                                        blocks an amenity — see mobile's `_SpotCard`).
                                        Only when the caller is the actual holder
                                        (own APPROVED) does the badge swap from the
                                        generic Held/Available to their own status. */}
                                    {myOpen?.status === "APPROVED" ? (
                                        <span className={cn(
                                            "px-2 py-0.5 text-[9px] font-bold uppercase tracking-widest rounded-md shrink-0",
                                            STATUS_CLASSES[myOpen.status]
                                        )}>
                                            {tB(`status${myOpen.status}`)}
                                        </span>
                                    ) : (
                                        <span className={cn(
                                            "px-2 py-0.5 text-[9px] font-bold uppercase tracking-widest rounded-md shrink-0",
                                            s.held ? "bg-warning/10 text-warning" : "bg-success/10 text-success"
                                        )}>
                                            {s.held ? t("held") : t("available")}
                                        </span>
                                    )}
                                </div>
                                {s.propertyName && <p className="text-[10px] font-bold text-muted">{s.propertyName}</p>}
                                <p className="text-xs text-muted">
                                    {s.level ? `${t("level")}: ${s.level} · ` : ""}
                                    {s.covered ? t("covered") : t("uncovered")}
                                </p>
                                <p className="text-[10px] text-muted">{t("pendingHint", { count: s.pendingCount })}</p>
                                <button
                                    onClick={() => openRequest({ resourceType: "PARKING_SPOT", resourceId: s.id, name: s.spotNumber, propertyId: s.propertyId })}
                                    disabled={noActiveLease || s.held}
                                    className="mt-auto self-start px-4 py-2 bg-primary/10 text-primary hover:bg-primary/20 rounded-xl text-xs font-bold transition-all duration-200 cursor-pointer focus:outline-none focus:ring-2 focus:ring-primary/30 disabled:opacity-50 disabled:cursor-not-allowed"
                                >
                                    {t("request")}
                                </button>
                            </div>
                            );
                        })}
                    </div>
                </div>
            )}

            {/* ── My Requests ───────────────────────────────────────── */}
            <div className="mt-10">
                <h2 className="text-sm font-bold text-foreground tracking-tight mb-4">{t("myRequests")}</h2>
                {bookings.length === 0 ? (
                    <div className="text-center py-12 bg-background border border-dashed border-border rounded-xl text-xs font-medium text-muted">
                        {t("noRequests")}
                    </div>
                ) : (
                    <div className="bg-surface rounded-xl border border-border overflow-hidden">
                        <div className="overflow-x-auto">
                            <table className="w-full text-sm">
                                <thead className="bg-background text-muted text-xs font-semibold uppercase tracking-[0.15em]">
                                    <tr>
                                        <th className="px-6 py-4 text-start">{tB("colResource")}</th>
                                        <th className="px-6 py-4 text-start">{tB("colType")}</th>
                                        <th className="px-6 py-4 text-start">{tB("colRequested")}</th>
                                        <th className="px-6 py-4 text-start">{tB("colPreferred")}</th>
                                        <th className="px-6 py-4 text-start">{tB("colStatus")}</th>
                                        <th className="px-6 py-4" />
                                    </tr>
                                </thead>
                                <tbody className="divide-y divide-border">
                                    {bookings.map(b => (
                                        <tr key={b.id} className="hover:bg-background/50 transition-all duration-200">
                                            <td className="px-6 py-4">
                                                <p className="font-bold text-foreground">{b.resourceName}</p>
                                                {b.adminNote && (
                                                    <p className="text-[10px] text-muted italic mt-0.5">
                                                        {tB("adminNote")}: {b.adminNote}
                                                    </p>
                                                )}
                                            </td>
                                            <td className="px-6 py-4">
                                                <span className="px-2 py-1 text-[10px] font-bold uppercase tracking-widest bg-background rounded-md">
                                                    {tB(`type${b.resourceType}`)}
                                                </span>
                                            </td>
                                            <td className="px-6 py-4 text-muted text-xs tabular-nums">
                                                {new Date(b.createdAt).toLocaleDateString(dateLocale(locale))}
                                            </td>
                                            <td className="px-6 py-4 text-muted text-xs tabular-nums">
                                                {b.preferredDate ? parseDateOnly(b.preferredDate).toLocaleDateString(dateLocale(locale)) : "—"}
                                            </td>
                                            <td className="px-6 py-4">
                                                <span className={cn(
                                                    "inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold",
                                                    STATUS_CLASSES[b.status]
                                                )}>
                                                    {tB(`status${b.status}`)}
                                                </span>
                                            </td>
                                            <td className="px-6 py-4">
                                                <div className="flex justify-end">
                                                    {b.status === "PENDING" && (
                                                        <button
                                                            onClick={() => setPendingAction({ kind: "cancel", booking: b })}
                                                            className="px-3 py-1.5 bg-error/10 text-error hover:bg-error/20 rounded-lg text-[11px] font-bold transition-all duration-200 cursor-pointer focus:outline-none focus:ring-2 focus:ring-error/30"
                                                        >
                                                            {t("cancelRequest")}
                                                        </button>
                                                    )}
                                                    {b.status === "APPROVED" && b.resourceType === "PARKING_SPOT" && (
                                                        <button
                                                            onClick={() => setPendingAction({ kind: "release", booking: b })}
                                                            className="px-3 py-1.5 bg-info/10 text-info hover:bg-info/20 rounded-lg text-[11px] font-bold transition-all duration-200 cursor-pointer focus:outline-none focus:ring-2 focus:ring-info/30"
                                                        >
                                                            {t("releaseSpot")}
                                                        </button>
                                                    )}
                                                </div>
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    </div>
                )}
            </div>

            {/* ── Request dialog ────────────────────────────────────── */}
            {target && (() => {
                // Scoped to leases on the resource's own property — a renter
                // with units in two properties must never submit a request
                // against a unit that isn't served by this facility.
                const matchingLeases = activeLeases.filter(l => l.propertyId === target.propertyId);
                return (
                <div
                    className="fixed inset-0 z-50 flex items-center justify-center bg-black/40"
                    onClick={submitting ? undefined : () => setTarget(null)}
                >
                    <div
                        className="bg-surface rounded-xl border border-border shadow-xl w-full max-w-md mx-4 p-6"
                        onClick={(e) => e.stopPropagation()}
                        role="dialog"
                        aria-modal="true"
                        aria-labelledby="facility-request-dialog-title"
                    >
                        <h3 id="facility-request-dialog-title" className="text-lg font-bold text-foreground mb-4">
                            {t("requestTitle", { name: target.name })}
                        </h3>
                        <form onSubmit={submitRequest} className="space-y-4">
                            <div>
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">{t("unit")}</label>
                                {matchingLeases.length > 1 ? (
                                    <select
                                        required
                                        value={unitId}
                                        onChange={e => setUnitId(e.target.value)}
                                        className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200"
                                    >
                                        {matchingLeases.map(l => (
                                            <option key={l.id} value={l.unitId}>
                                                {l.unitIdentifier} — {l.propertyName}
                                            </option>
                                        ))}
                                    </select>
                                ) : matchingLeases.length === 1 ? (
                                    <p className="w-full bg-input border border-border rounded-lg p-2 text-xs text-foreground font-semibold">
                                        {matchingLeases[0].unitIdentifier} — {matchingLeases[0].propertyName}
                                    </p>
                                ) : (
                                    <p className="text-xs font-semibold text-error">{t("noMatchingUnit")}</p>
                                )}
                            </div>
                            <div>
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">{t("preferredDate")}</label>
                                <input
                                    type="date"
                                    min={toDateInput(new Date())}
                                    value={preferredDate}
                                    onChange={e => setPreferredDate(e.target.value)}
                                    className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200"
                                />
                            </div>
                            <div>
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">{t("note")}</label>
                                <textarea
                                    rows={2}
                                    maxLength={2000}
                                    placeholder={t("notePlaceholder")}
                                    value={note}
                                    onChange={e => setNote(e.target.value)}
                                    className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200 resize-none"
                                />
                            </div>
                            {dialogError && (
                                <p className="text-xs font-semibold text-error">{dialogError}</p>
                            )}
                            <div className="flex justify-end gap-2 pt-2">
                                <button
                                    type="button"
                                    onClick={() => setTarget(null)}
                                    className="px-4 py-2 text-xs font-bold text-muted cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none rounded-lg transition-all duration-200"
                                >
                                    {t("cancel")}
                                </button>
                                <button
                                    type="submit"
                                    disabled={submitting || !unitId}
                                    className="px-4 py-2 bg-primary text-white rounded-lg text-xs font-bold cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200 disabled:opacity-50 disabled:cursor-not-allowed"
                                >
                                    {submitting ? t("submitting") : t("submitRequest")}
                                </button>
                            </div>
                        </form>
                    </div>
                </div>
                );
            })()}

            <ConfirmDialog
                isOpen={pendingAction !== null}
                onClose={() => setPendingAction(null)}
                onConfirm={runPendingAction}
                title={pendingAction?.kind === "release" ? t("releaseSpot") : t("cancelRequest")}
                description={pendingAction?.kind === "release" ? t("releaseSpotConfirm") : t("cancelRequestConfirm")}
                confirmText={pendingAction?.kind === "release" ? t("releaseSpot") : t("cancelRequest")}
                isDestructive={pendingAction?.kind === "cancel"}
                isLoading={actionLoading}
            />
        </div>
    );
}
