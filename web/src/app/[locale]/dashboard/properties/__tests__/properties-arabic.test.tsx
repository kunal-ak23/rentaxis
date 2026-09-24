import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";

import ar from "../../../../../../messages/ar.json";
import { leftoverLatinWords, visibleText } from "@/test/latinText";

/**
 * #81: the properties page kept Import Property / Import Portfolio, the
 * Revenue at Capacity / Actual Revenue headers and the RESIDENTIAL badge in
 * English under /ar, and both import dialogs and the portfolio result screen
 * were English end to end.
 */

vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));
vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }),
}));

import PropertiesPage from "../page";

const STATS = [{
    property: { id: "p1", nameEn: "Marina Tower", nameAr: "برج المارينا", emirate: "DUBAI", address: null,
        makaniNumber: "12345-67890", type: "RESIDENTIAL", fixedExpenses: 0 },
    propertyCount: 4, revenueAtCapacity: 100000, actualRevenue: 80000, vacancies: 1, assignedManagers: [],
}];

// Data and format names: amounts carry "AED" from the shared currency
// formatter; the portfolio template's sheet names and "Excel"/"xlsx"/"CSV"
// are file-format and template names a user must match.
const DATA = ["AED", "Excel", "xlsx", "CSV", "Properties وUnits وRenters وLeases", "Marina Tower", "Units sheet row is bad"];
// The workbook's own sheet name, as the server reports it.
const SHEET = "Units";

let statusBody: unknown;

beforeEach(() => {
    statusBody = { status: "PROCESSING" };
    global.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
        const u = String(url);
        const json = (body: unknown) => ({ ok: true, status: 200, json: async () => body, text: async () => JSON.stringify(body) });
        if (u.endsWith("/v1/import/portfolio") && init?.method === "POST") return json({ jobId: "job-1" }) as unknown as Response;
        if (u.includes("/v1/import/portfolio/job-1/status")) return json(statusBody) as unknown as Response;
        return json(STATS) as unknown as Response;
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.restoreAllMocks();
});

const renderAr = () =>
    render(<NextIntlClientProvider locale="ar" messages={ar}><PropertiesPage /></NextIntlClientProvider>);

describe("properties page in Arabic", () => {
    it("table: buttons, headers and the type badge carry no English", async () => {
        const { container } = renderAr();
        await screen.findByText("برج المارينا");

        expect(screen.getByText(ar.MasterData.importPortfolio)).toBeInTheDocument();
        expect(screen.getByText(ar.MasterData.actualRevenue)).toBeInTheDocument();
        expect(screen.getByText(ar.MasterData.propertyTypeRESIDENTIAL)).toBeInTheDocument();
        expect(leftoverLatinWords(visibleText(container), DATA)).toEqual([]);
    });

    it("the Import Property dialog carries no English", async () => {
        const { container } = renderAr();
        await screen.findByText("برج المارينا");
        fireEvent.click(screen.getByText(ar.MasterData.importProperty));

        expect(screen.getByText(ar.MasterData.importPropertyDesc)).toBeInTheDocument();
        expect(leftoverLatinWords(visibleText(container), DATA)).toEqual([]);
    });

    it("the Import Portfolio dialog and its failure result carry no English", async () => {
        const { container } = renderAr();
        await screen.findByText("برج المارينا");
        fireEvent.click(screen.getByText(ar.MasterData.importPortfolio));
        expect(leftoverLatinWords(visibleText(container), DATA)).toEqual([]);

        vi.useFakeTimers({ shouldAdvanceTime: true });
        statusBody = {
            status: "VALIDATION_FAILED",
            errors: [
                { sheet: "Units", row: 4, field: "", message: "Units sheet row is bad" },
                { sheet: "General", row: null, field: "", message: "Units sheet row is bad" },
            ],
        };
        const input = container.querySelector('input[type="file"]') as HTMLInputElement;
        fireEvent.change(input, { target: { files: [new File(["x"], "p.xlsx")] } });
        await act(async () => { fireEvent.click(screen.getByText(ar.MasterData.uploadAndImport)); });
        await act(async () => { await vi.advanceTimersByTimeAsync(2100); });

        expect(await screen.findByText(ar.MasterData.validationFailed)).toBeInTheDocument();
        expect(screen.getByText("الصف 4")).toBeInTheDocument();
        expect(screen.getByText(ar.MasterData.rowFile)).toBeInTheDocument();
        expect(leftoverLatinWords(visibleText(container), [...DATA, "p.xlsx", SHEET])).toEqual([]);
    });

    it("the portfolio success result carries no English", async () => {
        const { container } = renderAr();
        await screen.findByText("برج المارينا");
        fireEvent.click(screen.getByText(ar.MasterData.importPortfolio));

        vi.useFakeTimers({ shouldAdvanceTime: true });
        statusBody = {
            status: "COMPLETED", propertiesCreated: 1, buildingsCreated: 1, unitsCreated: 4, rentersCreated: 3,
            leasesCreated: 3, leasesPosted: 2, chequesCreated: 12, chequesFromSheet: 4, bookingDepositsCreated: 1,
            warnings: [{ sheet: "Units", row: 2, field: "", message: "Units sheet row is bad" }],
        };
        const input = container.querySelector('input[type="file"]') as HTMLInputElement;
        fireEvent.change(input, { target: { files: [new File(["x"], "p.xlsx")] } });
        await act(async () => { fireEvent.click(screen.getByText(ar.MasterData.uploadAndImport)); });
        await act(async () => { await vi.advanceTimersByTimeAsync(2100); });

        expect(await screen.findByText(ar.MasterData.importSuccessful)).toBeInTheDocument();
        expect(screen.getByText(ar.MasterData.resultLeasesPosted)).toBeInTheDocument();
        expect(leftoverLatinWords(visibleText(container), [...DATA, "p.xlsx", SHEET])).toEqual([]);
    });
});
