import { describe, expect, it } from "vitest";
import type { LeaseDetail } from "@/lib/api/leasing";
import { buildBoard, classifyUnit, countByStatus, floorOf, leaseForUnit, type BoardUnit } from "../unitBoard";

const TODAY = "2026-09-25";
const U = (id: string, unitNumber: string, occupancy: string, building: string | null = null, status = occupancy === "MAINTENANCE" ? "MAINTENANCE" : "VACANT"): BoardUnit =>
    ({ id, unitNumber, occupancy, status, building: building ? { id: building } : null });
const L = (id: string, unitId: string, startDate: string, endDate: string) => ({ id, unitId, startDate, endDate, status: "ACTIVE", renterName: `T ${id}` }) as LeaseDetail;

describe("floorOf", () => {
    it.each([
        ["07-01", "07"], ["M-1501", "15"], ["A-302", "3"], ["302", "3"], ["1205", "12"], ["B1204", "12"],
        ["G-01", "G"], ["Shop 5", "Shop"], ["SW-abc12", "SW"], ["PH", ""], ["", ""],
    ])("%s → %s", (n, f) => expect(floorOf(n)).toBe(f));
});

describe("classifyUnit", () => {
    it("reads the server's occupancy and marks a contract ending within 60 days as Expiring", () => {
        expect(classifyUnit(U("u", "101", "VACANT"), null, TODAY)).toBe("VACANT");
        expect(classifyUnit(U("u", "101", "RESERVED"), null, TODAY)).toBe("RESERVED");
        expect(classifyUnit(U("u", "101", "MAINTENANCE"), null, TODAY)).toBe("MAINTENANCE");
        expect(classifyUnit(U("u", "101", "OCCUPIED"), L("l", "u", "2026-01-01", "2026-12-31"), TODAY)).toBe("OCCUPIED");
        expect(classifyUnit(U("u", "101", "OCCUPIED"), L("l", "u", "2025-11-24", "2026-11-24"), TODAY)).toBe("EXPIRING");
        expect(classifyUnit(U("u", "101", "OCCUPIED"), L("l", "u", "2025-11-25", "2026-11-25"), TODAY)).toBe("OCCUPIED");
        expect(classifyUnit(U("u", "101", "OCCUPIED"), null, TODAY)).toBe("OCCUPIED");
    });
    it("treats a unit held for maintenance as Maintenance whatever else it says", () => {
        expect(classifyUnit({ id: "u", unitNumber: "1", occupancy: "VACANT", status: "MAINTENANCE" }, null, TODAY)).toBe("MAINTENANCE");
    });
});

describe("leaseForUnit", () => {
    it("prefers the contract in force, else the next to start", () => {
        const now = L("now", "u", "2026-01-01", "2026-12-31");
        const next = L("next", "u", "2027-01-01", "2027-12-31");
        expect(leaseForUnit([next, now], TODAY)?.id).toBe("now");
        expect(leaseForUnit([next], TODAY)?.id).toBe("next");
        expect(leaseForUnit([], TODAY)).toBeNull();
    });
});

describe("buildBoard", () => {
    const units = [
        U("a1", "A-1502", "OCCUPIED", "bA"), U("a2", "A-201", "VACANT", "bA"), U("a3", "A-1501", "RESERVED", "bA"),
        U("b1", "B-101", "MAINTENANCE", "bB"), U("x1", "Kiosk", "VACANT", null),
    ];
    const leases = [L("l1", "a1", "2025-10-01", "2026-10-31"), L("l3", "a3", "2026-11-01", "2027-10-31")];

    it("groups by building, then floor (natural order), then unit number", () => {
        const g = buildBoard(units, leases, [{ id: "bA", nameEn: "Tower A" }, { id: "bB", nameEn: "Tower B" }], TODAY);
        expect(g.map(x => x.buildingId)).toEqual(["bA", "bB", null]);
        expect(g[0].floors.map(f => f.floor)).toEqual(["2", "15"]);
        expect(g[0].floors[1].cells.map(c => c.unit.unitNumber)).toEqual(["A-1501", "A-1502"]);
        expect(g[0].floors[1].cells.map(c => c.status)).toEqual(["RESERVED", "EXPIRING"]);
        expect(g[0].floors[1].cells[0].lease?.id).toBe("l3");
        expect(g[2].floors).toEqual([{ floor: "", cells: [expect.objectContaining({ status: "VACANT" })] }]);
        expect(countByStatus(g)).toEqual({ OCCUPIED: 0, RESERVED: 1, EXPIRING: 1, VACANT: 2, MAINTENANCE: 1 });
    });

    it("does not group by building when the property has none", () => {
        const g = buildBoard(units, leases, [], TODAY);
        expect(g.map(x => x.buildingId)).toEqual([null]);
        expect(g[0].floors.map(f => f.floor)).toEqual(["1", "2", "15", ""]);
    });
});
