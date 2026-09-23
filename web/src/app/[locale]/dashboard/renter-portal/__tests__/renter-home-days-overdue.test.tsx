import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";

/**
 * Gap #37: the home card counted "days overdue" from the cheque date, while
 * My Payments shows the server's grace-aware `ChequeDueRules.daysOverdue`, so
 * one cheque read 630 on one screen and 625 on the other. The card now shows
 * the server's `overdue` / `daysOverdue`, the single source of truth.
 */

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

function isoDaysAgo(n: number): string {
    const d = new Date();
    d.setDate(d.getDate() - n);
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

function cheque(over: Record<string, unknown>) {
    return {
        id: "c2", leaseId: "l1", installmentNumber: 2, dueDate: isoDaysAgo(630),
        amount: 31500, status: "REGISTERED", mode: "PDC", chequeNumber: "700102",
        bankName: "ENBD", narration: null, propertyName: "Miftah Residences", unitIdentifier: "A-102",
        renterName: "Ahmed", due: true, overdue: true, daysOverdue: 625,
        gracePeriodDays: 5, penaltyOutstanding: 0, payable: 31500, payableOnline: false,
        onlineEnabled: false, penaltyAssessmentId: null, failureReason: null,
        clearedAt: null, statusChangedAt: null,
        ...over,
    };
}

function serve(payments: unknown[]) {
    global.fetch = vi.fn(async (url: RequestInfo | URL) => {
        const href = String(url);
        const body = href.includes("/online-payments/my-payments")
            ? payments
            : href.includes("/meetings/my")
              ? { content: [], totalElements: 0, totalPages: 0, number: 0, size: 5 }
              : [];
        return { ok: true, status: 200, json: async () => body };
    }) as unknown as typeof fetch;
}

function renderPage() {
    render(
        <NextIntlClientProvider locale="en" messages={en}>
            <RenterPortalPage />
        </NextIntlClientProvider>,
    );
}

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("Renter home next-payment card (#37)", () => {
    it("shows the server's grace-aware day count, not its own date arithmetic", async () => {
        serve([cheque({})]);
        renderPage();
        expect(await screen.findByText("625 days overdue")).toBeInTheDocument();
        expect(screen.queryByText("630 days overdue")).not.toBeInTheDocument();
    });

    it("does not call a cheque inside its grace period overdue", async () => {
        serve([cheque({ dueDate: isoDaysAgo(2), overdue: false, daysOverdue: 0 })]);
        renderPage();
        expect(await screen.findByText(en.RenterHome.dueInGrace)).toBeInTheDocument();
        expect(screen.queryByText(/overdue/)).not.toBeInTheDocument();
    });
});
