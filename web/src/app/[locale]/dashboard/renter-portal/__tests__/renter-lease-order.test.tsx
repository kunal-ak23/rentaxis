import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";
import ar from "../../../../../../messages/ar.json";

// #76: the renter's live contract comes first; ended ones sit folded under
// "Past contracts", newest first. The API lists leases oldest first, which put
// the ACTIVE lease below two RENEWED cards.

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
    { ...base, id: "old", unitIdentifier: "B-2", startDate: "2024-01-01", endDate: "2024-12-31", status: "EXPIRED", hasContract: false },
    { ...base, id: "year1", unitIdentifier: "A-101", startDate: "2026-01-01", endDate: "2026-12-31", status: "RENEWED", hasContract: true },
    { ...base, id: "next", unitIdentifier: "C-3", startDate: "2028-01-01", endDate: "2028-12-31", status: "PENDING_SIGNATURE", hasContract: false },
    { ...base, id: "year2", unitIdentifier: "A-101", startDate: "2027-01-01", endDate: "2027-12-31", status: "ACTIVE", hasContract: false },
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

const renderIn = (locale: "en" | "ar") => render(
    <NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}>
        <RenterPortalPage />
    </NextIntlClientProvider>,
);

const order = () => Array.from(document.querySelectorAll('[data-testid^="download-contract-"], [data-testid="past-contracts"]'))
    .map(el => el.getAttribute("data-testid"));

describe("Renter portal — lease order (#76)", () => {
    it("puts the active lease first, then upcoming, and folds ended contracts under Past contracts", async () => {
        renderIn("en");
        await screen.findByTestId("download-contract-year2");

        const past = screen.getByTestId("past-contracts");
        expect(past.tagName).toBe("DETAILS");
        expect(past.hasAttribute("open")).toBe(false);
        expect(past.textContent).toContain("Past contracts (2)");

        // Current cards come before the Past contracts section; RENEWED sits inside it.
        const ids = order();
        expect(ids.indexOf("download-contract-year2")).toBeLessThan(ids.indexOf("past-contracts"));
        expect(past.querySelector('[data-testid="download-contract-year1"]')).not.toBeNull();
        expect(past.querySelector('[data-testid="download-contract-year2"]')).toBeNull();

        // The ACTIVE card is above the PENDING one, whatever order the API used.
        const cards = Array.from(document.querySelectorAll("h3")).map(h => h.textContent ?? "");
        const active = cards.findIndex(c => c.includes("A-101"));
        const upcoming = cards.findIndex(c => c.includes("C-3"));
        expect(active).toBeGreaterThanOrEqual(0);
        expect(upcoming).toBeGreaterThan(active);
    });

    it("labels the section in Arabic", async () => {
        renderIn("ar");
        await screen.findByTestId("download-contract-year2");
        expect(screen.getByTestId("past-contracts").textContent).toContain("العقود السابقة (2)");
    });
});
