"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { Car, Home } from "lucide-react";
import { cn } from "@/lib/utils";
import { isAbortError } from "@/lib/api/abort";

/** A unit as `GET /v1/units/property/{id}` returns it: `occupancy` is date-based (F14-01), `status` the stored one. */
type UnitRow = { occupancy?: string | null; status?: string | null };

export type UnitCounts = { total: number; occupied: number; vacant: number; reserved: number; maintenance: number };
export type ParkingSummary = { total: number; assigned: number; free: number; inactive: number };

/**
 * Demo feedback 2026-09-29: the Overview's unit tiles, from the units the page
 * already loads. A unit's date-based occupancy wins over its stored status (a
 * contract posted for next month reserves the unit; one running today occupies it),
 * the same rule the Units tab's badges follow.
 */
export function unitCounts(units: UnitRow[]): UnitCounts {
    const c: UnitCounts = { total: units.length, occupied: 0, vacant: 0, reserved: 0, maintenance: 0 };
    for (const u of units) {
        switch (u.occupancy ?? u.status) {
            case "OCCUPIED": c.occupied++; break;
            case "RESERVED": c.reserved++; break;
            case "MAINTENANCE": c.maintenance++; break;
            default: c.vacant++;
        }
    }
    return c;
}

function Tile({ label, value, testId, tone }: { label: string; value: number | string; testId: string; tone?: string }) {
    return (
        <div className="bg-surface p-4 rounded-xl border border-border flex flex-col gap-1 min-w-0">
            <p className="text-[11px] font-semibold text-muted truncate">{label}</p>
            <p data-testid={testId} className={cn("text-xl font-bold tabular-nums text-foreground", tone)}>{value}</p>
        </div>
    );
}

/**
 * Unit and parking counts for the property Overview. Parking comes from the
 * one-call summary endpoint (the spot list is paged); a role that cannot read
 * parking (`showParking` false) or a failed read leaves that card out.
 */
export function OverviewCounts({ units, propertyId, showParking }: { units: UnitRow[]; propertyId: string; showParking: boolean }) {
    const t = useTranslations("MasterData");
    const [parking, setParking] = useState<ParkingSummary | null>(null);

    useEffect(() => {
        if (!showParking) return;
        const ctrl = new AbortController();
        fetch(`/api/proxy/v1/parking-spots/summary?propertyId=${encodeURIComponent(propertyId)}`, { signal: ctrl.signal })
            .then(res => (res.ok ? res.json() : null))
            .then((body: ParkingSummary | null) => setParking(body))
            .catch(err => {
                if (!isAbortError(err)) setParking(null);
            });
        return () => ctrl.abort();
    }, [propertyId, showParking]);

    const u = unitCounts(units);

    return (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6 mb-6" data-testid="property-overview-counts">
            <div className={cn("bg-background rounded-xl p-6 border border-border", showParking && parking ? "lg:col-span-2" : "lg:col-span-3")}>
                <p className="text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-4 flex items-center gap-2">
                    <Home size={12} className="text-primary/40" />
                    {t("overviewUnits")}
                </p>
                <div className="grid grid-cols-2 sm:grid-cols-3 xl:grid-cols-5 gap-3">
                    <Tile label={t("unitsTotal")} value={u.total} testId="overview-units-total" />
                    <Tile label={t("unitsOccupied")} value={u.occupied} testId="overview-units-occupied" tone="text-success" />
                    <Tile label={t("unitsVacant")} value={u.vacant} testId="overview-units-vacant" tone="text-warning" />
                    <Tile label={t("unitsReserved")} value={u.reserved} testId="overview-units-reserved" tone="text-info" />
                    <Tile label={t("unitsMaintenance")} value={u.maintenance} testId="overview-units-maintenance" tone="text-muted" />
                </div>
            </div>
            {showParking && parking && (
                <div className="bg-background rounded-xl p-6 border border-border" data-testid="overview-parking">
                    <p className="text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-4 flex items-center gap-2">
                        <Car size={12} className="text-primary/40" />
                        {t("overviewParking")}
                    </p>
                    <div className="grid grid-cols-3 gap-3">
                        <Tile label={t("parkingTotal")} value={parking.total} testId="overview-parking-total" />
                        <Tile label={t("parkingAssigned")} value={parking.assigned} testId="overview-parking-assigned" tone="text-success" />
                        <Tile label={t("parkingFree")} value={parking.free} testId="overview-parking-free" tone="text-warning" />
                    </div>
                    {parking.inactive > 0 && (
                        <p className="text-[11px] text-muted mt-3" data-testid="overview-parking-inactive">
                            {t("parkingInactive", { count: parking.inactive })}
                        </p>
                    )}
                </div>
            )}
        </div>
    );
}
