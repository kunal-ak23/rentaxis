import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import ar from "../../../../../../../messages/ar.json";
import { leftoverLatinWords, visibleText } from "@/test/latinText";

/**
 * The project page's Buildings and Units tabs were English under /ar (headings,
 * Add Building, Add Unit, the form labels and placeholders, the units table),
 * while the Arabic help articles already name the buttons «إضافة مبنى» and «إضافة عقار».
 */
vi.mock("next/navigation", () => ({
    useParams: () => ({ id: "p1" }),
    useRouter: () => ({ push: vi.fn(), refresh: vi.fn() }),
    usePathname: () => "/ar/dashboard/properties/p1",
    useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
    useRouter: () => ({ push: vi.fn() }),
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN", name: "U", tenantId: "t1" } } }) }));

import PropertyDetailPage from "../page";

const building = { id: "b1", nameEn: "Tower A", nameAr: "البرج أ", floors: 12 };
const units = [
    { id: "u1", unitNumber: "101", type: "BHK1", status: "VACANT", sizeSqft: 850, expectedRent: 85000, building: { id: "b1" } },
    { id: "u2", unitNumber: "102", type: "STUDIO", status: "OCCUPIED", sizeSqft: 500, expectedRent: 60000, building: null },
];
const json = (body: unknown) => ({ ok: true, status: 200, json: async () => body }) as unknown as Response;

beforeEach(() => {
    global.fetch = vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.endsWith("/v1/properties/p1")) return json({ id: "p1", nameEn: "Sample Vista", nameAr: "سامبل فيستا", emirate: "DUBAI", type: "RESIDENTIAL" });
        if (url.includes("/v1/buildings")) return json([building]);
        if (url.includes("/v1/units")) return json(units);
        return json([]);
    }) as unknown as typeof fetch;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const renderAr = () => render(<NextIntlClientProvider locale="ar" messages={ar}><PropertyDetailPage /></NextIntlClientProvider>);
// Data: the building's English name sits beside its Arabic one on the card; amounts
// carry "AED" from the shared currency formatter; the CSV column headers and the
// format name are what the uploaded file must contain.
const DATA = ["Tower A", "AED", "CSV", "unitNumber, type, sizeSqft, expectedRent, status"];

const openTab = async (label: string) => {
    fireEvent.click(await screen.findByRole("button", { name: label }));
};
const tabPanel = (heading: HTMLElement) => heading.parentElement!.parentElement as HTMLElement;

describe("project page — Buildings and Units tabs in Arabic", () => {
    it("the Buildings tab and its add form carry no English, and the button reads «إضافة مبنى»", async () => {
        renderAr();
        await openTab(ar.MasterData.tabBuildings);
        const heading = await screen.findByRole("heading", { name: ar.MasterData.tabBuildings });
        await screen.findByText("البرج أ");
        fireEvent.click(screen.getByText(ar.MasterData.addBuilding));
        expect(ar.MasterData.addBuilding).toBe("إضافة مبنى");
        expect(screen.getByText(ar.MasterData.save)).toBeInTheDocument();
        expect(leftoverLatinWords(visibleText(tabPanel(heading)), DATA)).toEqual([]);
    });

    it("the Units tab, its add form and bulk upload carry no English, and the button reads «إضافة عقار»", async () => {
        renderAr();
        await openTab(ar.MasterData.tabUnits);
        const heading = await screen.findByRole("heading", { name: ar.MasterData.tabUnits });
        await screen.findByText("101");
        const panel = tabPanel(heading);
        expect(panel.textContent).toContain(ar.MasterData.vacant);
        expect(panel.textContent).toContain(ar.MasterData.occupied);
        expect(panel.textContent).toContain("البرج أ");

        fireEvent.click(screen.getByText(ar.MasterData.addUnit));
        expect(ar.MasterData.addUnit).toBe("إضافة عقار");
        const form = screen.getByText(ar.MasterData.saveUnit).closest("form") as HTMLElement;
        expect(form.textContent).toContain(ar.MasterData.expectedRent);
        expect(leftoverLatinWords(visibleText(panel), DATA)).toEqual([]);

        fireEvent.click(screen.getByText(ar.MasterData.bulkUploadCsv));
        expect(screen.getByText(ar.MasterData.assignToBuildingOptional)).toBeInTheDocument();
        expect(leftoverLatinWords(visibleText(panel), DATA)).toEqual([]);
    });
});
