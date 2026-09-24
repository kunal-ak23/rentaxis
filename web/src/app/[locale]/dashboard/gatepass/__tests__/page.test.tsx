import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const locale = vi.hoisted(() => ({ current: "en" }));
const sessionRole = vi.hoisted(() => ({ current: "TENANT_ADMIN" as string | undefined }));

vi.mock("next-intl", () => ({
    useTranslations: () => (key: string, vars?: Record<string, string | number>) => {
        if (!vars) return key;
        let out = key;
        for (const [k, v] of Object.entries(vars)) out = out.replaceAll(`{${k}}`, String(v));
        return out;
    },
    useLocale: () => locale.current,
}));

vi.mock("next-auth/react", () => ({
    useSession: () => ({
        data: sessionRole.current ? { user: { role: sessionRole.current } } : null,
        status: "authenticated",
    }),
}));

import GatePassReportPage from "../page";

/** Shaped exactly like GatePassDtos.GatePassReportRow. */
const sampleRows = [
    {
        scanId: "s1",
        scannedAt: "2026-07-10T06:30:00Z",
        direction: "ENTRY",
        result: "ALLOWED",
        rejectionReason: null,
        scannedByUserId: "aaaaaaaa-1111-2222-3333-444444444444",
        scannedByName: "Ravi Kumar",
        gatePassId: "gp1",
        propertyId: "prop-1",
        unitNumber: "A-204",
        // The comma is the point: this is what a naive CSV export splits into two columns.
        guestName: "Khan, Ahmed",
        guestPhone: "+971501234567",
        vehicleNumber: "A-12345",
        purpose: 'Delivery — said "back in 5"',
        passType: "SINGLE_USE",
    },
    {
        scanId: "s2",
        scannedAt: "2026-07-11T09:00:00Z",
        direction: "EXIT",
        result: "REJECTED",
        rejectionReason: "Pass expired",
        scannedByUserId: "bbbbbbbb-1111-2222-3333-444444444444",
        // Null on purpose: the server could not resolve this scanner, so this row
        // exercises the id-fragment fallback the other one must never hit.
        scannedByName: null,
        gatePassId: "gp2",
        propertyId: "prop-2",
        unitNumber: null,
        guestName: "أحمد خان",
        guestPhone: "+971509999999",
        vehicleNumber: null,
        purpose: null,
        passType: "RECURRING",
    },
];

const sampleProperties = [
    { property: { id: "prop-1", nameEn: "Belle Vue", nameAr: "بيل فيو" } },
    { property: { id: "prop-2", nameEn: "Marina Heights", nameAr: null } },
];

let reportUrls: string[] = [];

/** Minimal slice of Response the page actually reads. */
type StubResponse = { ok: boolean; status?: number; json?: () => Promise<unknown> };

function stubFetch(handler: (url: string) => StubResponse) {
    global.fetch = vi.fn(
        async (input: RequestInfo | URL) => handler(String(input)) as unknown as Response,
    ) as unknown as typeof fetch;
}

beforeEach(() => {
    locale.current = "en";
    sessionRole.current = "TENANT_ADMIN";
    reportUrls = [];
    stubFetch((u) => {
        if (u.includes("/gatepass/report")) {
            reportUrls.push(u);
            return { ok: true, json: async () => sampleRows };
        }
        if (u.includes("/v1/properties")) {
            return { ok: true, json: async () => sampleProperties };
        }
        return { ok: true, json: async () => null };
    });
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("GatePassReportPage", () => {
    it("renders a row per scan with guest details resolved against the property list", async () => {
        const { container } = render(<GatePassReportPage />);

        await waitFor(() => expect(screen.getByText("Khan, Ahmed")).toBeTruthy());

        // Scoped to tbody: the property names also appear as <option>s in the filter,
        // so an unscoped getByText matches twice and says nothing about the cell.
        const body = within(container.querySelector("tbody")!);
        expect(body.getAllByRole("row")).toHaveLength(2);
        // propertyId resolved to a display name via /v1/properties, not shown raw.
        expect(body.getByText("Belle Vue")).toBeTruthy();
        expect(body.queryByText("prop-1")).toBeNull();
        expect(body.getByText("A-204")).toBeTruthy();
        expect(body.getByText("أحمد خان")).toBeTruthy();
        expect(body.getByText("Pass expired")).toBeTruthy();

        // The property filter is populated from the same fetch.
        expect(screen.getByRole("option", { name: "Belle Vue" })).toBeTruthy();
    });

    it("names the scanning guard rather than showing a UUID fragment", async () => {
        // "Who scanned this" is what the report is for, and PROPERTY_MANAGER cannot
        // resolve the id client-side (/api/admin/users excludes the role), so a
        // fragment here is unreadable by design, not just ugly.
        const { container } = render(<GatePassReportPage />);
        await waitFor(() => expect(screen.getByText("Khan, Ahmed")).toBeTruthy());

        const body = within(container.querySelector("tbody")!);
        expect(body.getByText("Ravi Kumar")).toBeTruthy();
        expect(body.queryByText("aaaaaaaa")).toBeNull();
        // The second row's name is null — it falls back to the id fragment rather
        // than rendering an empty cell or the string "null".
        expect(body.getByText("bbbbbbbb")).toBeTruthy();
    });

    it("calls the report through the Next proxy with a Z-suffixed instant range", async () => {
        render(<GatePassReportPage />);
        await waitFor(() => expect(reportUrls.length).toBeGreaterThan(0));

        const url = new URL(reportUrls[0], "http://localhost");
        // CLAUDE.md rule 6: proxy rewrite, never a hardcoded backend origin.
        expect(url.pathname).toBe("/api/proxy/v1/gatepass/report");
        // The controller binds these as Instants; a non-Z local string would 400.
        expect(url.searchParams.get("from")).toMatch(/Z$/);
        expect(url.searchParams.get("to")).toMatch(/Z$/);
        expect(new Date(url.searchParams.get("to")!).getTime())
            .toBeGreaterThan(new Date(url.searchParams.get("from")!).getTime());
        // Default window is last 7 days, unfiltered by property.
        expect(url.searchParams.has("propertyId")).toBe(false);
    });

    it("never renders pass credentials", async () => {
        // qrToken / numericCode are creator-only and absent from the report row.
        // If they ever appear in this DOM, the server contract has regressed.
        const { container } = render(<GatePassReportPage />);
        await waitFor(() => expect(screen.getByText("Khan, Ahmed")).toBeTruthy());
        expect(container.innerHTML).not.toMatch(/qrToken|numericCode/i);
    });

    it("renders Arabic property names under the ar locale", async () => {
        locale.current = "ar";
        const { container } = render(<GatePassReportPage />);

        await waitFor(() => expect(screen.getByText("Khan, Ahmed")).toBeTruthy());
        const body = within(container.querySelector("tbody")!);
        expect(body.getByText("بيل فيو")).toBeTruthy();
        // nameAr is null for Marina Heights — must fall back to nameEn, not
        // render an empty cell or the string "null".
        expect(body.getByText("Marina Heights")).toBeTruthy();
    });

    it("shows the empty state when the period has no scans", async () => {
        stubFetch((u) =>
            u.includes("/gatepass/report")
                ? { ok: true, json: async () => [] }
                : { ok: true, json: async () => sampleProperties },
        );

        render(<GatePassReportPage />);
        await waitFor(() => expect(screen.getByText("noRows")).toBeTruthy());
    });

    it("shows an error state with a retry affordance when the report fails", async () => {
        stubFetch((u) =>
            u.includes("/gatepass/report")
                ? { ok: false, status: 500 }
                : { ok: true, json: async () => sampleProperties },
        );

        render(<GatePassReportPage />);
        await waitFor(() => expect(screen.getByText("loadError")).toBeTruthy());
        expect(screen.getByText("retry")).toBeTruthy();
    });

    it("denies roles the report's @PreAuthorize excludes, without calling the API", async () => {
        for (const role of ["ACCOUNTANT", "RENTER", undefined]) {
            sessionRole.current = role;
            render(<GatePassReportPage />);
            await waitFor(() => expect(screen.getByText("noAccess")).toBeTruthy());
            expect(reportUrls).toEqual([]);
            cleanup();
        }
    });

    it("allows SUPER_ADMIN acting in an organisation (#88)", async () => {
        sessionRole.current = "SUPER_ADMIN";
        render(<GatePassReportPage />);
        await waitFor(() => expect(screen.getByText("Khan, Ahmed")).toBeTruthy());
    });

    it("allows PROPERTY_MANAGER", async () => {
        sessionRole.current = "PROPERTY_MANAGER";
        render(<GatePassReportPage />);
        await waitFor(() => expect(screen.getByText("Khan, Ahmed")).toBeTruthy());
    });

    it("exports a CSV whose header matches the visible columns and whose free text is escaped", async () => {
        // toCsv is unit-tested on its own; this pins the wiring — that the page hands
        // it the translated headers and the raw (unescaped, untruncated) values.
        // jsdom's Blob has no .text(), so capture the parts at construction —
        // which also pins the exact strings the page hands to the Blob.
        const blobParts: string[][] = [];
        const RealBlob = globalThis.Blob;
        vi.stubGlobal("Blob", class extends RealBlob {
            constructor(parts: BlobPart[], options?: BlobPropertyBag) {
                blobParts.push(parts.map(String));
                super(parts, options);
            }
        });
        vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:mock");
        vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => {});
        vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});

        render(<GatePassReportPage />);
        await waitFor(() => expect(screen.getByText("Khan, Ahmed")).toBeTruthy());
        screen.getByText("exportCsv").click();

        await waitFor(() => expect(blobParts).toHaveLength(1));
        const text = blobParts[0].join("");
        const lines = text.split("\r\n");

        // BOM first — without it Excel renders the Arabic row as mojibake.
        expect(text.startsWith("﻿")).toBe(true);
        // Header row == the table's columns in order, plus the CSV-only guard id.
        expect(lines[0]).toBe(
            '﻿"colScannedAt","colDirection","colResult","colProperty","colUnit",' +
            '"colGuestName","colGuestPhone","colVehicle","colPurpose","colPassType",' +
            '"colReason","colGuard","colGuardId"',
        );
        // Every fetched row, not just the visible page.
        expect(lines).toHaveLength(1 + sampleRows.length);
        // The comma'd name stays one field; the embedded quotes are doubled.
        expect(lines[1]).toContain('"Khan, Ahmed"');
        expect(lines[1]).toContain('"Delivery — said ""back in 5"""');
        // Both guard columns: the name a reader acts on, and the full id the row can
        // still be joined by — names are not unique, so neither replaces the other.
        expect(lines[1]).toContain('"Ravi Kumar","aaaaaaaa-1111-2222-3333-444444444444"');
        // Nullable columns render empty, not "null" — including an unresolved guard
        // name, whose id must still be exported.
        expect(lines[2]).not.toContain("null");
        expect(lines[2]).toContain('"","bbbbbbbb-1111-2222-3333-444444444444"');
        expect(lines[2]).toContain('"أحمد خان"');
    });
});
