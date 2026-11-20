"use client";

import { useEffect, useState } from "react";
import { useTranslations, useLocale } from "next-intl";
import SideDrawer from "@/components/ui/SideDrawer";
import {
    ApiError,
    createBookingOnBehalf,
    fetchAmenities,
    fetchBookingQuote,
    fetchParkingSpots,
    fetchPropertyUnits,
    throwIfNotOk,
    type PropertyUnitRow,
} from "@/lib/api/facilities";
import type { BookingQuoteDTO, BookingRequestDTO, BookingResourceType } from "@/types/facility";

type PropertyOption = { id: string; nameEn: string; nameAr: string | null };
type LeaseRow = { unitId: string; renterId: string; renterName: string | null; status: string };
type Resource = { id: string; label: string; feeType?: string };

/** A contract that still holds the unit; the server takes the same set (BookingFeeService.LIVE). */
const LIVE = new Set(["ACTIVE", "NOTICE_GIVEN", "EXPIRED", "ON_HOLD", "RENEWED"]);

const INPUT = "w-full bg-input border border-border rounded-lg px-3 py-2 text-xs font-semibold text-foreground focus:ring-2 focus:ring-primary/30 focus:outline-none";
const LABEL = "block text-[10px] font-bold uppercase tracking-widest text-muted mb-1";

/**
 * Staff book a bay or an amenity for a renter (spec 2026-11-19 section 4): property, unit, the
 * renter from the unit's live contract, facility and dates; the fee shown is the server's quote.
 * The booking is approved on the spot, so the fee is charged as soon as it is made.
 */
export function NewBookingDialog({ open, properties, defaultPropertyId, onClose, onCreated }: {
    open: boolean;
    properties: PropertyOption[];
    defaultPropertyId?: string;
    onClose: () => void;
    onCreated: (booking: BookingRequestDTO) => void;
}) {
    const t = useTranslations("Bookings");
    const locale = useLocale();

    const [propertyId, setPropertyId] = useState(defaultPropertyId ?? "");
    const [units, setUnits] = useState<PropertyUnitRow[]>([]);
    const [leases, setLeases] = useState<LeaseRow[]>([]);
    const [unitId, setUnitId] = useState("");
    const [renter, setRenter] = useState<{ userId: string; name: string } | null>(null);
    const [renterLoading, setRenterLoading] = useState(false);
    const [resourceType, setResourceType] = useState<"" | BookingResourceType>("");
    const [resources, setResources] = useState<Resource[]>([]);
    const [resourceId, setResourceId] = useState("");
    const [start, setStart] = useState("");
    const [end, setEnd] = useState("");
    const [from, setFrom] = useState("");
    const [to, setTo] = useState("");
    const [quote, setQuote] = useState<BookingQuoteDTO | null>(null);
    const [quoting, setQuoting] = useState(false);
    const [submitting, setSubmitting] = useState(false);
    const [error, setError] = useState<string | null>(null);

    // Units and contracts of the property.
    useEffect(() => {
        if (!open || !propertyId) { setUnits([]); setLeases([]); return; }
        let cancelled = false;
        (async () => {
            try {
                const [u, lres] = await Promise.all([
                    fetchPropertyUnits(propertyId),
                    fetch(`/api/proxy/v1/leases/property/${encodeURIComponent(propertyId)}`),
                ]);
                await throwIfNotOk(lres);
                const l: LeaseRow[] = await lres.json();
                if (cancelled) return;
                setUnits(u);
                setLeases(l);
            } catch {
                if (!cancelled) setError(t("onBehalfLoadError"));
            }
        })();
        return () => { cancelled = true; };
    }, [open, propertyId, t]);

    // The renter of the unit's live contract; the booking needs their user id.
    useEffect(() => {
        setRenter(null);
        const lease = leases.find(l => l.unitId === unitId && LIVE.has(l.status));
        if (!unitId || !lease) { setRenterLoading(false); return; }
        let cancelled = false;
        setRenterLoading(true);
        (async () => {
            try {
                const res = await fetch(`/api/proxy/v1/renters/${encodeURIComponent(lease.renterId)}`);
                await throwIfNotOk(res);
                const r: { userId?: string | null; nameEn?: string | null } = await res.json();
                if (cancelled) return;
                // A renter with no portal login cannot hold a booking: treated as no renter.
                if (r.userId) setRenter({ userId: r.userId, name: r.nameEn ?? lease.renterName ?? "" });
            } catch {
                if (!cancelled) setError(t("onBehalfLoadError"));
            } finally {
                if (!cancelled) setRenterLoading(false);
            }
        })();
        return () => { cancelled = true; };
    }, [unitId, leases, t]);

    // The property's bays or amenities.
    useEffect(() => {
        setResources([]);
        setResourceId("");
        if (!open || !propertyId || !resourceType) return;
        let cancelled = false;
        (async () => {
            try {
                const list: Resource[] = resourceType === "AMENITY"
                    ? (await fetchAmenities(propertyId, 0, 200)).content
                        .filter(a => a.active && a.bookable)
                        .map(a => ({ id: a.id, label: locale === "ar" && a.nameAr ? a.nameAr : a.nameEn }))
                    : (await fetchParkingSpots(propertyId, 0, 200)).content
                        .filter(s => s.active && !s.attachedUnitId)
                        .map(s => ({ id: s.id, label: s.level ? `${s.spotNumber} (${s.level})` : s.spotNumber, feeType: s.feeType }));
                if (!cancelled) setResources(list);
            } catch {
                if (!cancelled) setError(t("onBehalfLoadError"));
            }
        })();
        return () => { cancelled = true; };
    }, [open, propertyId, resourceType, locale, t]);

    // A bay charged by the day or the month is quoted for its dates, so it needs an end date (the
    // server refuses one without: booking.endDateRequired) and shows no quote until both are set.
    const feeType = resources.find(r => r.id === resourceId)?.feeType;
    const periodic = resourceType === "PARKING_SPOT" && (feeType === "PER_DAY" || feeType === "PER_MONTH");
    // An amenity is booked for one day (and an optional time slot); only a bay has an end date. A
    // flat-fee bay with no end date is booked for its start day.
    const effectiveEnd = resourceType === "PARKING_SPOT" ? (periodic ? end || undefined : end || start) : undefined;
    const needsEnd = periodic && !end;
    const renterUserId = renter?.userId;

    // The server's quote for the chosen facility and dates, with VAT per the renter's lease.
    useEffect(() => {
        setQuote(null);
        setQuoting(false);
        if (!resourceType || !resourceId || !start || needsEnd) return;
        let cancelled = false;
        setQuoting(true);
        fetchBookingQuote({
            resourceType, resourceId, start, end: effectiveEnd,
            from: resourceType === "AMENITY" ? from || undefined : undefined,
            to: resourceType === "AMENITY" ? to || undefined : undefined,
            ...(unitId && renterUserId ? { unitId, renterUserId } : {}),
        })
            .then(q => { if (!cancelled) setQuote(q); })
            .catch(() => { /* the booking still goes through: the fee is charged on approval */ })
            .finally(() => { if (!cancelled) setQuoting(false); });
        return () => { cancelled = true; };
    }, [resourceType, resourceId, start, effectiveEnd, needsEnd, from, to, unitId, renterUserId]);

    const ready = !!(renter && resourceType && resourceId && start && !needsEnd && !submitting);

    async function submit() {
        if (!renter || !resourceType) return;
        setSubmitting(true);
        setError(null);
        try {
            const created = await createBookingOnBehalf({
                booking: {
                    resourceType, resourceId, unitId,
                    preferredDate: start,
                    ...(effectiveEnd ? { preferredEndDate: effectiveEnd } : {}),
                    ...(resourceType === "AMENITY" && from && to ? { preferredStartTime: from, preferredEndTime: to } : {}),
                },
                renterUserId: renter.userId,
            });
            onCreated(created);
            onClose();
        } catch (err) {
            setError(err instanceof ApiError ? err.message : t("onBehalfError"));
        } finally {
            setSubmitting(false);
        }
    }

    const propertyName = (p: PropertyOption) => (locale === "ar" && p.nameAr ? p.nameAr : p.nameEn);

    return (
        <SideDrawer open={open} onClose={onClose} title={t("onBehalfTitle")} testId="onbehalf-dialog" closeLabel={t("onBehalfClose")}>
            <div className="space-y-4">
                <p className="text-xs text-muted font-medium">{t("onBehalfHint")}</p>

                <div>
                    <label className={LABEL} htmlFor="onbehalf-property">{t("onBehalfProperty")}</label>
                    <select id="onbehalf-property" data-testid="onbehalf-property" className={INPUT} value={propertyId}
                        onChange={e => { setPropertyId(e.target.value); setUnitId(""); setResourceType(""); setError(null); }}>
                        <option value="">{t("onBehalfChoose")}</option>
                        {properties.map(p => <option key={p.id} value={p.id}>{propertyName(p)}</option>)}
                    </select>
                </div>

                <div>
                    <label className={LABEL} htmlFor="onbehalf-unit">{t("onBehalfUnit")}</label>
                    <select id="onbehalf-unit" data-testid="onbehalf-unit" className={INPUT} value={unitId}
                        onChange={e => { setUnitId(e.target.value); setError(null); }}>
                        <option value="">{t("onBehalfChoose")}</option>
                        {units.map(u => <option key={u.id} value={u.id}>{u.unitNumber}</option>)}
                    </select>
                </div>

                {unitId && (
                    <div>
                        <span className={LABEL}>{t("onBehalfRenter")}</span>
                        <p data-testid="onbehalf-renter" className="text-xs font-semibold text-foreground">
                            {renter ? renter.name : renterLoading ? "..." : t("onBehalfNoRenter")}
                        </p>
                    </div>
                )}

                <div>
                    <label className={LABEL} htmlFor="onbehalf-type">{t("onBehalfType")}</label>
                    <select id="onbehalf-type" data-testid="onbehalf-type" className={INPUT} value={resourceType}
                        onChange={e => { setResourceType(e.target.value as "" | BookingResourceType); setError(null); }}>
                        <option value="">{t("onBehalfChoose")}</option>
                        <option value="AMENITY">{t("typeAMENITY")}</option>
                        <option value="PARKING_SPOT">{t("typePARKING_SPOT")}</option>
                    </select>
                </div>

                {resourceType && (
                    <div>
                        <label className={LABEL} htmlFor="onbehalf-resource">{t("onBehalfResource")}</label>
                        <select id="onbehalf-resource" data-testid="onbehalf-resource" className={INPUT} value={resourceId}
                            onChange={e => { setResourceId(e.target.value); setError(null); }}>
                            <option value="">{t("onBehalfChoose")}</option>
                            {resources.map(r => <option key={r.id} value={r.id}>{r.label}</option>)}
                        </select>
                    </div>
                )}

                <div className="grid grid-cols-2 gap-3">
                    <div>
                        <label className={LABEL} htmlFor="onbehalf-start">{t("onBehalfStart")}</label>
                        <input id="onbehalf-start" data-testid="onbehalf-start" type="date" className={INPUT} value={start}
                            onChange={e => setStart(e.target.value)} />
                    </div>
                    {resourceType === "PARKING_SPOT" && <div>
                        <label className={LABEL} htmlFor="onbehalf-end">{t(periodic ? "onBehalfEndRequired" : "onBehalfEnd")}</label>
                        <input id="onbehalf-end" data-testid="onbehalf-end" type="date" className={INPUT} value={end} min={start || undefined}
                            required={periodic} onChange={e => setEnd(e.target.value)} />
                    </div>}
                </div>

                {resourceType === "AMENITY" && (
                    <div className="grid grid-cols-2 gap-3">
                        <div>
                            <label className={LABEL} htmlFor="onbehalf-from">{t("onBehalfFrom")}</label>
                            <input id="onbehalf-from" data-testid="onbehalf-from" type="time" className={INPUT} value={from}
                                onChange={e => setFrom(e.target.value)} />
                        </div>
                        <div>
                            <label className={LABEL} htmlFor="onbehalf-to">{t("onBehalfTo")}</label>
                            <input id="onbehalf-to" data-testid="onbehalf-to" type="time" className={INPUT} value={to}
                                onChange={e => setTo(e.target.value)} />
                        </div>
                    </div>
                )}

                {periodic && start && needsEnd && (
                    <p data-testid="onbehalf-needs-end" className="text-xs text-muted font-medium">{t("onBehalfNeedsEnd")}</p>
                )}

                {(quote || quoting) && (
                    <div className="rounded-lg border border-border bg-background px-3 py-2">
                        <span className={LABEL}>{t("onBehalfQuote")}</span>
                        <p data-testid="onbehalf-quote" className="text-xs font-semibold text-foreground tabular-nums">
                            {quote
                                ? t("onBehalfQuoteVat", { net: quote.net, vat: quote.vat, total: quote.total })
                                : t("onBehalfQuoteLoading")}
                        </p>
                    </div>
                )}

                {error && (
                    <div data-testid="onbehalf-error" className="px-3 py-2 rounded-lg bg-error/10 text-error border border-error/20 text-xs font-semibold">
                        {error}
                    </div>
                )}

                <button type="button" data-testid="onbehalf-submit" disabled={!ready} onClick={submit}
                    className="w-full rounded-lg bg-primary text-white px-4 py-2.5 text-xs font-bold cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed">
                    {submitting ? t("onBehalfSubmitting") : t("onBehalfSubmit")}
                </button>
            </div>
        </SideDrawer>
    );
}
