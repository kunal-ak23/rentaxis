import { cleanup, render, screen, fireEvent } from "@testing-library/react";
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

/**
 * QA-01 (prod hand test 2026-11-26): an active contract had no way to download it.
 * The list offered the PDF only while PENDING_SIGNATURE; the server renders a posted
 * contract on demand (ContractGenerationService.currentContractPdf), so a posted
 * contract offers it too.
 */
const ACTIVE = {
    id: "lease-123", unitIdentifier: "A-101", renterName: "Jane Tenant", propertyName: "Sample Oasis",
    startDate: "2026-01-01", endDate: "2026-12-31", rentAmount: 60000, monthlyRent: 5000, paymentTerms: 12,
    status: "ACTIVE", hasContract: false, postedAt: "2026-01-02T08:00:00Z",
};

beforeEach(() => {
    window.history.replaceState(null, "", "/");
    global.fetch = vi.fn(async (url: unknown) => {
        const u = String(url);
        if (u.includes("/leases/paged")) {
            return { ok: true, json: async () => ({ content: [ACTIVE], totalElements: 1, totalPages: 1, number: 0 }) } as Response;
        }
        return { ok: true, json: async () => [] } as Response;
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("Leases list: download contract on a posted contract", () => {
    it("offers Download contract on an active contract with no stored PDF", async () => {
        render(<LeasesPage />);
        await screen.findByText("Jane Tenant");
        fireEvent.click(screen.getByRole("button", { name: /cards/i }));
        await screen.findByText(/A-101/);
        fireEvent.click(screen.getByTestId("lease-card-menu-lease-123"));
        expect(await screen.findByTestId("lease-card-pdf-lease-123")).toBeInTheDocument();
    });
});
