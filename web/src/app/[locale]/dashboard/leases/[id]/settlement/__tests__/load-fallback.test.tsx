import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("next/navigation", () => ({
    useParams: () => ({ id: "lease-1", locale: "en" }),
    useRouter: () => ({ push: vi.fn() }),
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>
            {children}
        </a>
    ),
}));

import SettlementPage from "../page";

type Route = { status: number; body?: unknown };

let settlementRoute: Route;
let previewRoute: Route;

beforeEach(() => {
    settlementRoute = { status: 500 };
    previewRoute = { status: 200, body: { depositAmount: 10000, unpaidRentTotal: 2000, penaltyTotal: 0, suggestedRefund: 8000 } };
    global.fetch = vi.fn(async (url: unknown) => {
        const u = String(url);
        const route = u.includes("/settlement/preview") ? previewRoute : settlementRoute;
        return {
            ok: route.status >= 200 && route.status < 300,
            status: route.status,
            json: async () => route.body ?? {},
        } as Response;
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("SettlementPage load fallback", () => {
    it("surfaces a non-404 failure as an error state instead of the blank preview editor", async () => {
        settlementRoute = { status: 500 };

        render(<SettlementPage />);

        // A 500 while a DRAFT may exist must never silently swap in the
        // preview flow — saving that would duplicate the draft's rows.
        await screen.findByText("Failed to load settlement. Please try again.");
        expect(screen.getByRole("button", { name: "Retry" })).toBeInTheDocument();
        const calls = (global.fetch as ReturnType<typeof vi.fn>).mock.calls.map(c => String(c[0]));
        expect(calls.some(u => u.includes("/settlement/preview"))).toBe(false);
        expect(screen.queryByText("Security Deposit")).not.toBeInTheDocument();
    });

    it("falls back to the preview flow only on a 404", async () => {
        settlementRoute = { status: 404 };

        render(<SettlementPage />);

        await screen.findByText("Auto-Calculated Deductions");
        expect(screen.getByText("Outstanding rent")).toBeInTheDocument();
        const calls = (global.fetch as ReturnType<typeof vi.fn>).mock.calls.map(c => String(c[0]));
        expect(calls.some(u => u.includes("/settlement/preview"))).toBe(true);
        expect(screen.queryByText("Failed to load settlement. Please try again.")).not.toBeInTheDocument();
    });

    it("renders the existing settlement when the fetch succeeds", async () => {
        settlementRoute = {
            status: 200,
            body: {
                id: "s-1",
                leaseId: "lease-1",
                depositAmount: 5000,
                totalDeductions: 0,
                totalAdditions: 0,
                refundAmount: 5000,
                notes: "Existing draft notes",
                status: "DRAFT",
                settledBy: "u1",
                settledAt: "",
                createdAt: "",
                deductions: [],
            },
        };

        render(<SettlementPage />);

        await screen.findByText("DRAFT");
        await waitFor(() => {
            const calls = (global.fetch as ReturnType<typeof vi.fn>).mock.calls.map(c => String(c[0]));
            expect(calls.some(u => u.includes("/settlement/preview"))).toBe(false);
        });
    });
});
