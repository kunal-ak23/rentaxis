"use client";

import { useState, useEffect, useCallback } from "react";
import { useTranslations, useLocale } from "next-intl";
import { useSession } from "next-auth/react";
import { CalendarCheck } from "lucide-react";
import { cn } from "@/lib/utils";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { Pagination } from "@/components/ui/Pagination";
import { fetchBookings } from "@/lib/api/facilities";
import type { BookingRequestDTO, BookingRequestStatus, BookingResourceType } from "@/types/facility";
import { BookingDetailDrawer, BOOKING_STATUS_CLASSES } from "./_components/BookingDetailDrawer";

const PAGE_SIZE = 10;
const STATUSES: BookingRequestStatus[] = ["PENDING", "APPROVED", "REJECTED", "CANCELLED", "RELEASED"];

type PropertyOption = { id: string; nameEn: string; nameAr: string | null };

export default function BookingsPage() {
    const t = useTranslations("Bookings");
    const locale = useLocale();
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const canView = hasPermission(userRole, "canManageFacilities");

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

    useEffect(() => {
        (async () => {
            try {
                const res = await fetch("/api/proxy/v1/properties");
                if (res.ok) {
                    const data: Array<{ property: PropertyOption }> = await res.json();
                    setProperties(data.map(s => s.property));
                }
            } catch (err) {
                console.error(err);
            }
        })();
    }, []);

    const load = useCallback(async (p: number) => {
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
            setRows(data.content);
            setTotalElements(data.totalElements);
            setPage(data.number);
        } catch {
            setError(t("loadError"));
        } finally {
            setLoading(false);
        }
    }, [propertyId, status, resourceType, t]);

    useEffect(() => { load(0); }, [load]);

    if (session && !canView) {
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
                    <option value="">{t("allProperties")}</option>
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
                    <button onClick={() => load(page)} className="font-bold underline cursor-pointer">{t("retry")}</button>
                </div>
            )}

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
                                        {r.preferredDate ? new Date(r.preferredDate).toLocaleDateString() : "—"}
                                    </td>
                                    <td className="px-6 py-4 text-muted text-xs tabular-nums">
                                        {new Date(r.createdAt).toLocaleDateString()}
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

            {openId && (
                <BookingDetailDrawer
                    bookingId={openId}
                    onClose={() => setOpenId(null)}
                    onChanged={(updated) => setRows(prev => prev.map(r => (r.id === updated.id ? updated : r)))}
                />
            )}
        </div>
    );
}
