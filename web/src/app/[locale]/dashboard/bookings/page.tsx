"use client";

import { useState, useEffect, useCallback, useRef } from "react";
import { useTranslations, useLocale } from "next-intl";
import { useSession } from "next-auth/react";
import { CalendarCheck, Loader2, Building2 } from "lucide-react";
import { cn } from "@/lib/utils";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { Pagination } from "@/components/ui/Pagination";
import { fetchBookings, ApiError } from "@/lib/api/facilities";
import type { BookingRequestDTO, BookingRequestStatus, BookingResourceType } from "@/types/facility";
import { BookingDetailDrawer, BOOKING_STATUS_CLASSES } from "./_components/BookingDetailDrawer";

const PAGE_SIZE = 10;
const STATUSES: BookingRequestStatus[] = ["PENDING", "APPROVED", "REJECTED", "CANCELLED", "RELEASED"];

type PropertyOption = { id: string; nameEn: string; nameAr: string | null };

/** Locale tag for toLocaleDateString — mirrors gatepass/page.tsx's ar-AE/en-GB split. */
function dateLocale(locale: string): string {
    return locale === "ar" ? "ar-AE" : "en-GB";
}

/**
 * Parses a `YYYY-MM-DD` date-only string (BookingRequestDTO.preferredDate is
 * a LocalDate) as a local calendar date rather than `new Date(str)`, which
 * the spec parses as UTC midnight — a renter picking the 9th would render as
 * the 8th for anyone west of UTC. Same fix as gatepass/page.tsx's
 * dayBoundsIso; createdAt is an Instant and doesn't need it.
 */
function parseDateOnly(value: string): Date {
    const [y, m, d] = value.split("-").map(Number);
    return new Date(y, m - 1, d);
}

/**
 * Throws an ApiError (parsed from the `{error, message}` shape where
 * possible, same convention as facilities.ts's parseErrorMessage) when a raw
 * fetch response isn't ok. The properties list uses a plain fetch (it isn't
 * wrapped by lib/api/facilities), so without this a 4xx/5xx here would be
 * silently swallowed — same fix as renter-portal/facilities/page.tsx's
 * throwIfNotOk.
 */
async function throwIfNotOk(res: Response): Promise<void> {
    if (res.ok) return;
    const text = await res.text().catch(() => "");
    let message = `Request failed (status ${res.status})`;
    if (text) {
        try {
            const parsed: unknown = JSON.parse(text);
            if (parsed && typeof parsed === "object") {
                const body = parsed as { message?: unknown; error?: unknown };
                if (typeof body.message === "string") message = body.message;
                else if (typeof body.error === "string") message = body.error;
            }
        } catch {
            // Not JSON — keep the generic message.
        }
    }
    throw new ApiError(res.status, message, text);
}

export default function BookingsPage() {
    const t = useTranslations("Bookings");
    const locale = useLocale();
    const { data: session, status: sessionStatus } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const canView = hasPermission(userRole, "canManageFacilities");
    const isPropertyManager = userRole === "PROPERTY_MANAGER";

    const [rows, setRows] = useState<BookingRequestDTO[]>([]);
    const [totalElements, setTotalElements] = useState(0);
    const [page, setPage] = useState(0);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [properties, setProperties] = useState<PropertyOption[]>([]);
    const [propertyId, setPropertyId] = useState("");
    const [status, setStatus] = useState<"" | BookingRequestStatus>("PENDING");
    const [resourceType, setResourceType] = useState<"" | BookingResourceType>("");
    const [openId, setOpenId] = useState<string | null>(null);

    // The backend 403s a PROPERTY_MANAGER's list request without a propertyId
    // (BookingController.checkPropertyManagerAccess) — the request must never
    // fire until one is picked, and "All properties" isn't a valid choice for
    // this role in the first place.
    const needsPropertySelection = isPropertyManager && !propertyId;

    // Guards every fetch below against a slow response landing after a newer
    // one already has (filters changed mid-flight, or the drawer closing
    // triggers a resync while a previous load() is still in flight) and
    // clobbering fresher state with stale data.
    const requestIdRef = useRef(0);

    // A failed load here must surface, not just silently leave the property
    // filter empty — the caller has no other way to know why "All properties"
    // (or, for a PM, every property) is missing from the dropdown. Errors
    // share the page-level banner/retry with `load` below.
    const loadProperties = useCallback(async () => {
        const res = await fetch("/api/proxy/v1/properties");
        await throwIfNotOk(res);
        const data: Array<{ property: PropertyOption }> = await res.json();
        setProperties(data.map(s => s.property));
    }, []);

    useEffect(() => {
        if (sessionStatus !== "authenticated" || !canView) return;
        loadProperties().catch(err => {
            setError(err instanceof ApiError ? err.message : t("loadError"));
        });
    }, [sessionStatus, canView, loadProperties, t]);

    const load = useCallback(async (p: number) => {
        if (needsPropertySelection) {
            setRows([]);
            setTotalElements(0);
            setError(null);
            setLoading(false);
            return;
        }
        const requestId = ++requestIdRef.current;
        setLoading(true);
        setError(null);
        try {
            const data = await fetchBookings({
                propertyId: propertyId || undefined,
                status: status || undefined,
                resourceType: resourceType || undefined,
                page: p,
                size: PAGE_SIZE,
            });
            if (requestId !== requestIdRef.current) return; // a newer request already landed
            setRows(data.content);
            setTotalElements(data.totalElements);
            setPage(data.number);
        } catch (err) {
            if (requestId !== requestIdRef.current) return;
            setError(err instanceof ApiError ? err.message : t("loadError"));
        } finally {
            if (requestId === requestIdRef.current) setLoading(false);
        }
    }, [propertyId, status, resourceType, t, needsPropertySelection]);

    useEffect(() => {
        if (sessionStatus !== "authenticated" || !canView) return;
        load(0);
    }, [sessionStatus, canView, load]);

    if (sessionStatus === "loading") {
        return (
            <div className="flex items-center justify-center py-24">
                <Loader2 className="w-6 h-6 animate-spin text-primary opacity-60" />
            </div>
        );
    }

    if (!canView) {
        return (
            <div className="p-8 max-w-7xl mx-auto">
                <p className="text-sm font-semibold text-muted">{t("noAccess")}</p>
            </div>
        );
    }

    const propertyName = (p: PropertyOption) =>
        locale === "ar" && p.nameAr ? p.nameAr : p.nameEn;

    return (
        <div className="p-8 max-w-7xl mx-auto">
            <div className="flex items-center gap-3 mb-1">
                <div className="w-9 h-9 bg-primary/10 rounded-xl flex items-center justify-center text-primary border border-primary/20">
                    <CalendarCheck size={18} />
                </div>
                <h1 className="text-xl font-bold text-foreground tracking-tight">{t("title")}</h1>
            </div>
            <p className="text-xs text-muted font-medium mb-6">{t("subtitle")}</p>

            {/* Filters */}
            <div className="flex flex-wrap items-center gap-3 mb-6">
                <select
                    value={propertyId}
                    onChange={e => setPropertyId(e.target.value)}
                    className="bg-input border border-border rounded-lg px-3 py-2 text-xs font-semibold text-foreground cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                >
                    {/* PMs are scoped to their assigned properties by the backend — "all" isn't a valid choice for
                        them. A disabled placeholder (rather than just omitting the blank option) keeps the select
                        showing "nothing chosen" instead of the browser silently defaulting to the first <option> in
                        the list while propertyId (and the prompt below) still say otherwise. */}
                    {isPropertyManager
                        ? (!propertyId && <option value="" disabled>{t("selectPropertyPrompt")}</option>)
                        : <option value="">{t("allProperties")}</option>}
                    {properties.map(p => (
                        <option key={p.id} value={p.id}>{propertyName(p)}</option>
                    ))}
                </select>
                <select
                    value={status}
                    onChange={e => setStatus(e.target.value as "" | BookingRequestStatus)}
                    className="bg-input border border-border rounded-lg px-3 py-2 text-xs font-semibold text-foreground cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                >
                    <option value="">{t("allStatuses")}</option>
                    {STATUSES.map(s => (
                        <option key={s} value={s}>{t(`status${s}`)}</option>
                    ))}
                </select>
                <select
                    value={resourceType}
                    onChange={e => setResourceType(e.target.value as "" | BookingResourceType)}
                    className="bg-input border border-border rounded-lg px-3 py-2 text-xs font-semibold text-foreground cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                >
                    <option value="">{t("allTypes")}</option>
                    <option value="AMENITY">{t("typeAMENITY")}</option>
                    <option value="PARKING_SPOT">{t("typePARKING_SPOT")}</option>
                </select>
            </div>

            {error && (
                <div className="mb-4 px-4 py-3 rounded-xl bg-error/10 text-error border border-error/20 text-xs font-semibold flex items-center justify-between">
                    <span>{error}</span>
                    <button
                        onClick={() => {
                            load(page);
                            loadProperties().catch(err => {
                                setError(err instanceof ApiError ? err.message : t("loadError"));
                            });
                        }}
                        className="font-bold underline cursor-pointer"
                    >
                        {t("retry")}
                    </button>
                </div>
            )}

            {needsPropertySelection ? (
                <div className="bg-surface border border-border rounded-xl px-6 py-16 text-center">
                    <Building2 size={28} className="mx-auto mb-3 text-muted opacity-40" />
                    <p className="text-sm text-muted font-medium">{t("selectPropertyPrompt")}</p>
                </div>
            ) : (
            <div className="bg-surface border border-border rounded-xl overflow-hidden">
                <div className="overflow-x-auto">
                    <table className="w-full text-sm">
                        <thead className="bg-background text-muted text-xs font-semibold uppercase tracking-[0.15em]">
                            <tr>
                                <th className="px-6 py-4 text-start">{t("colResource")}</th>
                                <th className="px-6 py-4 text-start">{t("colType")}</th>
                                <th className="px-6 py-4 text-start">{t("colRenter")}</th>
                                <th className="px-6 py-4 text-start">{t("colUnit")}</th>
                                <th className="px-6 py-4 text-start">{t("colPreferred")}</th>
                                <th className="px-6 py-4 text-start">{t("colRequested")}</th>
                                <th className="px-6 py-4 text-start">{t("colStatus")}</th>
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-border">
                            {rows.map(r => (
                                <tr
                                    key={r.id}
                                    onClick={() => setOpenId(r.id)}
                                    className="hover:bg-background/50 transition-all duration-200 cursor-pointer"
                                >
                                    <td className="px-6 py-4 font-bold text-foreground">{r.resourceName}</td>
                                    <td className="px-6 py-4">
                                        <span className="px-2 py-1 text-[10px] font-bold uppercase tracking-widest bg-background rounded-md">
                                            {t(`type${r.resourceType}`)}
                                        </span>
                                    </td>
                                    <td className="px-6 py-4 text-muted font-medium">{r.renterName ?? "—"}</td>
                                    <td className="px-6 py-4 text-muted text-xs">{r.unitNumber ?? "—"}</td>
                                    <td className="px-6 py-4 text-muted text-xs tabular-nums">
                                        {r.preferredDate ? parseDateOnly(r.preferredDate).toLocaleDateString(dateLocale(locale)) : "—"}
                                    </td>
                                    <td className="px-6 py-4 text-muted text-xs tabular-nums">
                                        {new Date(r.createdAt).toLocaleDateString(dateLocale(locale))}
                                    </td>
                                    <td className="px-6 py-4">
                                        <span className={cn(
                                            "inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold",
                                            BOOKING_STATUS_CLASSES[r.status]
                                        )}>
                                            {t(`status${r.status}`)}
                                        </span>
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
                {loading && (
                    <div className="text-center py-12 text-muted font-medium text-xs">{t("loading")}</div>
                )}
                {!loading && rows.length === 0 && !error && (
                    <div className="text-center py-12 text-muted font-medium text-xs">{t("noBookings")}</div>
                )}
                {!loading && totalElements > PAGE_SIZE && (
                    <div className="px-6 pb-4">
                        <Pagination
                            currentPage={page + 1}
                            totalItems={totalElements}
                            itemsPerPage={PAGE_SIZE}
                            onPageChange={(p) => load(p - 1)}
                        />
                    </div>
                )}
            </div>
            )}

            {openId && (
                <BookingDetailDrawer
                    bookingId={openId}
                    // The drawer patches its row in place while open (using the
                    // returned DTO from approve/reject/release), but that can drift
                    // from the backend's actual paged/filtered state — e.g. a row
                    // decided out of the current status filter is still shown, and
                    // totalElements doesn't reflect it. Resync from the server once
                    // the user is done looking, which also refreshes otherRequests
                    // staleness for the next row opened.
                    onClose={() => { setOpenId(null); load(page); }}
                    onChanged={(updated) => setRows(prev => prev.map(r => (r.id === updated.id ? updated : r)))}
                />
            )}
        </div>
    );
}
