import type { LeaseDetail } from "@/lib/api/leasing";
import { EXPIRING_DAYS, plusDays } from "@/lib/dashboard/pipeline";

/** The board's five states (scale spec #20; PACT "Floor Wise Expiry" / "Daily Vacant Flat"). */
export type BoardStatus = "OCCUPIED" | "RESERVED" | "EXPIRING" | "VACANT" | "MAINTENANCE";
export const BOARD_STATUSES: BoardStatus[] = ["OCCUPIED", "RESERVED", "EXPIRING", "VACANT", "MAINTENANCE"];

/** The fields of `GET /units/paged` rows the board reads (the Unit entity, with F14-01's derived occupancy). */
export interface BoardUnit {
    id: string;
    unitNumber: string;
    type?: string | null;
    sizeSqft?: number | null;
    expectedRent?: number | null;
    status?: string | null;
    occupancy?: string | null;
    currentTenantName?: string | null;
    nextLeaseStart?: string | null;
    nextTenantName?: string | null;
    building?: { id?: string | null } | null;
}
export interface BoardBuilding { id: string; nameEn?: string | null; nameAr?: string | null }

export interface BoardCell {
    unit: BoardUnit;
    status: BoardStatus;
    /** The contract in force today (or the one reserving the unit), when the board read one. */
    lease: LeaseDetail | null;
}
export interface BoardFloor { floor: string; cells: BoardCell[] }
export interface BoardGroup { buildingId: string | null; floors: BoardFloor[] }

/**
 * The floor a unit sits on, read from its number the way the backend's
 * `?floor=` filter reads it (a shared prefix): "07-01" → "07", "M-1501" → "15",
 * "302" → "3", "B1204" → "12". A number with no floor in it gives "".
 */
export function floorOf(unitNumber: string): string {
    const n = (unitNumber ?? "").trim();
    const sep = n.match(/^([^\s\-/_.]+)[\s\-/_.]+(.+)$/);
    if (sep) {
        const [, head, rest] = sep;
        const restDigits = rest.match(/^(\d+)/)?.[1] ?? "";
        if (/^[A-Za-z]+$/.test(head) && restDigits.length >= 3) return String(Number(restDigits.slice(0, -2)));
        return head;
    }
    const lettersThenDigits = n.match(/^[A-Za-z]*(\d{3,})[A-Za-z]?$/);
    if (lettersThenDigits) return String(Number(lettersThenDigits[1].slice(0, -2)));
    return "";
}

const floorOrder = (a: string, b: string) => {
    if (a === "" || b === "") return a === b ? 0 : a === "" ? 1 : -1;
    const na = Number(a), nb = Number(b);
    if (Number.isFinite(na) && Number.isFinite(nb)) return na - nb;
    return a.localeCompare(b, undefined, { numeric: true });
};

/** The lease that explains the unit today: one covering `today`, else the next to start. */
export function leaseForUnit(leases: LeaseDetail[], today: string): LeaseDetail | null {
    const current = leases.filter(l => l.startDate <= today && l.endDate >= today).sort((a, b) => a.endDate.localeCompare(b.endDate));
    if (current.length) return current[current.length - 1];
    const next = leases.filter(l => l.startDate > today).sort((a, b) => a.startDate.localeCompare(b.startDate));
    return next[0] ?? null;
}

/**
 * Occupancy comes from the server (`occupancy`, F14-01: OCCUPIED, RESERVED,
 * VACANT, MAINTENANCE); an occupied unit whose contract ends within 60 days
 * is shown as Expiring.
 */
export function classifyUnit(unit: BoardUnit, lease: LeaseDetail | null, today: string): BoardStatus {
    const occ = (unit.occupancy ?? unit.status ?? "VACANT").toUpperCase();
    if (occ === "MAINTENANCE" || unit.status === "MAINTENANCE") return "MAINTENANCE";
    if (occ === "RESERVED") return "RESERVED";
    if (occ === "OCCUPIED") {
        if (lease && lease.startDate <= today && lease.endDate <= plusDays(today, EXPIRING_DAYS)) return "EXPIRING";
        return "OCCUPIED";
    }
    return "VACANT";
}

/** Units → buildings (only when the property has buildings) → floors, each sorted naturally. */
export function buildBoard(units: BoardUnit[], leases: LeaseDetail[], buildings: BoardBuilding[], today: string): BoardGroup[] {
    const byUnit = new Map<string, LeaseDetail[]>();
    for (const l of leases) {
        if (!l.unitId) continue;
        const list = byUnit.get(l.unitId) ?? [];
        list.push(l);
        byUnit.set(l.unitId, list);
    }
    const known = new Set(buildings.map(b => b.id));
    const groups = new Map<string | null, Map<string, BoardCell[]>>();
    for (const unit of units) {
        const lease = leaseForUnit(byUnit.get(unit.id) ?? [], today);
        const cell: BoardCell = { unit, lease, status: classifyUnit(unit, lease, today) };
        const bid = buildings.length > 0 && unit.building?.id && known.has(unit.building.id) ? unit.building.id : null;
        const floors = groups.get(bid) ?? new Map<string, BoardCell[]>();
        const f = floorOf(unit.unitNumber);
        floors.set(f, [...(floors.get(f) ?? []), cell]);
        groups.set(bid, floors);
    }
    const order = [...buildings.map(b => b.id as string | null), null];
    return order.filter(id => groups.has(id)).map(buildingId => ({
        buildingId,
        floors: [...groups.get(buildingId)!.entries()]
            .sort(([a], [b]) => floorOrder(a, b))
            .map(([floor, cells]) => ({
                floor,
                cells: cells.sort((x, y) => x.unit.unitNumber.localeCompare(y.unit.unitNumber, undefined, { numeric: true })),
            })),
    }));
}

export function countByStatus(groups: BoardGroup[]): Record<BoardStatus, number> {
    const out = { OCCUPIED: 0, RESERVED: 0, EXPIRING: 0, VACANT: 0, MAINTENANCE: 0 } as Record<BoardStatus, number>;
    for (const g of groups) for (const f of g.floors) for (const c of f.cells) out[c.status] += 1;
    return out;
}
