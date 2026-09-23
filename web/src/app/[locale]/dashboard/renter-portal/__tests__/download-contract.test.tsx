import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";

// #38: a renter's current contract is downloadable even when no contract was
// generated for it (a renewal is posted without one). The page asks the
// renter-scoped `GET /leases/{id}/contract`; ended contracts offer it only
// when a signed contract was stored.

vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { name: "Ahmed", role: "RENTER" } } }),
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
    useRouter: () => ({ push: vi.fn() }),
}));
vi.mock("@/app/[locale]/dashboard/meetings/CreateMeetingModal", () => ({ default: () => null }));
vi.mock("@/components/renewals/RenewalBanner", () => ({ default: () => null }));

import RenterPortalPage from "../page";

const base = {
    unitId: "u1", renterId: "r1", renterName: "Ahmed", rentAmount: 60000, depositAmount: 5000,
    ejariNumber: "E-1", paymentTerms: 4, propertyName: "Tower",
};
const LEASES = [
    { ...base, id: "year2", unitIdentifier: "A-101", startDate: "2027-01-01", endDate: "2027-12-31", status: "ACTIVE", hasContract: false },
    { ...base, id: "year1", unitIdentifier: "A-101", startDate: "2026-01-01", endDate: "2026-12-31", status: "RENEWED", hasContract: true },
    { ...base, id: "old", unitIdentifier: "B-2", startDate: "2024-01-01", endDate: "2024-12-31", status: "EXPIRED", hasContract: false },
];

let contractUrls: string[];
let contractOk: boolean;

beforeEach(() => {
    contractUrls = [];
    contractOk = true;
    global.URL.createObjectURL = vi.fn(() => "blob:x");
    global.URL.revokeObjectURL = vi.fn();
    global.fetch = vi.fn(async (url: RequestInfo | URL) => {
        const href = String(url);
        if (href.includes("/contract")) {
            contractUrls.push(href);
            return { ok: contractOk, status: contractOk ? 200 : 404, blob: async () => new Blob(["%PDF-"]) };
        }
        const body = href.includes("/leases/my-leases")
            ? LEASES
            : href.includes("/meetings/my")
              ? { content: [], totalElements: 0, totalPages: 0, number: 0, size: 5 }
              : [];
        return { ok: true, status: 200, json: async () => body };
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

const renderPage = () => render(
    <NextIntlClientProvider locale="en" messages={en}>
        <RenterPortalPage />
    </NextIntlClientProvider>,
);

describe("Renter portal — download contract", () => {
    it("offers the current contract even when none was generated, and the stored one of a renewed term", async () => {
        renderPage();

        const current = await screen.findByTestId("download-contract-year2");
        expect(screen.getByTestId("download-contract-year1")).toBeTruthy();
        expect(screen.queryByTestId("download-contract-old")).toBeNull();

        fireEvent.click(current);
        await waitFor(() => expect(contractUrls).toEqual(["/api/proxy/v1/leases/year2/contract"]));
    });

    it("says so when the contract cannot be downloaded", async () => {
        contractOk = false;
        renderPage();

        fireEvent.click(await screen.findByTestId("download-contract-year2"));

        expect(await screen.findByText(en.MasterData.contractUnavailable)).toBeTruthy();
    });
});
