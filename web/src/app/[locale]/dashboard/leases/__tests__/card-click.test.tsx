import { cleanup, render, screen, fireEvent, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const push = vi.fn();

vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
    useRouter: () => ({ push }),
}));
vi.mock("next-intl", () => ({
    useTranslations: () => (key: string) => key,
    useLocale: () => "en",
}));
vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }),
}));
// Heavy children irrelevant to click behavior.
vi.mock("../LeaseWizard", () => ({ default: () => null }));
vi.mock("@/components/ui/Pagination", () => ({ Pagination: () => null }));
vi.mock("@/components/ui/confirm-dialog", () => ({ ConfirmDialog: () => null }));

import LeasesPage from "../page";

const LEASE = {
    id: "lease-123",
    unitIdentifier: "A-101",
    renterName: "Jane Tenant",
    propertyName: "Ocean Residencia",
    startDate: "2026-01-01",
    endDate: "2026-12-31",
    rentAmount: 60000,
    monthlyRent: 5000,
    paymentTerms: 12,
    status: "DRAFT",
    hasContract: false,
};
const DETAIL_PATH = "/dashboard/leases/lease-123";

beforeEach(() => {
    push.mockClear();
    global.fetch = vi.fn(async (url: unknown) => {
        const u = String(url);
        if (u.includes("/leases/paged")) {
            return { ok: true, json: async () => ({ content: [LEASE], totalElements: 1, totalPages: 1, number: 0 }) } as Response;
        }
        return { ok: true, json: async () => [] } as Response;
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("Leases list click-through", () => {
    it("navigates to the lease detail when a TABLE row is clicked", async () => {
        render(<LeasesPage />);
        // default view is table; wait for the row to render
        const cell = await screen.findByText("Jane Tenant");
        fireEvent.click(cell);
        expect(push).toHaveBeenCalledWith(DETAIL_PATH);
    });

    it("navigates to the lease detail when a CARD is clicked", async () => {
        render(<LeasesPage />);
        await screen.findByText("Jane Tenant");

        fireEvent.click(screen.getByRole("button", { name: /cards/i }));

        // card heading is `${t("unit")} A-101` -> "unit A-101" with passthrough t
        const cardHeading = await screen.findByText(/A-101/);
        push.mockClear();
        fireEvent.click(cardHeading);
        expect(push).toHaveBeenCalledWith(DETAIL_PATH);
    });

    it("does NOT navigate when an action button inside a card is clicked", async () => {
        render(<LeasesPage />);
        await screen.findByText("Jane Tenant");
        fireEvent.click(screen.getByRole("button", { name: /cards/i }));
        await screen.findByText(/A-101/);

        push.mockClear();
        // "Docs" opens a modal, never navigates — stopPropagation must keep the card from firing.
        fireEvent.click(screen.getByRole("button", { name: /docs/i }));
        expect(push).not.toHaveBeenCalled();
    });
});
