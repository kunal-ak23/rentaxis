import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import ar from "../../../../../../../messages/ar.json";
import type { RenterCheque } from "@/lib/api/leasing";

/**
 * Restored from the pre-v2 portal: each banked cheque says when it was deposited
 * and when it cleared (money taken another way says when it was collected), and an
 * attached scan opens through the app — never the private blob URL.
 */

const api = vi.hoisted(() => ({ myPayments: vi.fn() }));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, onlinePayApi: { ...m.onlinePayApi, ...api } };
});

import RenterPaymentsPage from "../page";

function row(over: Partial<RenterCheque> = {}): RenterCheque {
    return {
        id: "c1", leaseId: "l1", installmentNumber: 1, dueDate: "2026-06-01",
        amount: 12500, status: "CLEARED", mode: "PDC", chequeNumber: "CHQ-1",
        bankName: "ENBD", narration: null, propertyName: "Belle Vue", unitIdentifier: "A-204",
        renterName: "Tenant", due: false, overdue: false, daysOverdue: 0, gracePeriodDays: 5,
        penaltyOutstanding: 0, payable: 0, payableOnline: false, onlineEnabled: true, penaltyAssessmentId: null,
        failureReason: null, clearedAt: null, statusChangedAt: null,
        ...over,
    };
}

function renderPage(locale: "en" | "ar" = "en") {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}>
            <RenterPaymentsPage />
        </NextIntlClientProvider>,
    );
}

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("RenterPaymentsPage — bank dates and scans", () => {
    it("says when each cheque was deposited, cleared or collected", async () => {
        api.myPayments.mockResolvedValue([
            row({ id: "cleared", depositedAt: "2026-06-02", clearedAt: "2026-06-04" }),
            row({ id: "at-bank", status: "DEPOSITED", depositedAt: "2026-07-01", dueDate: "2026-07-01" }),
            row({ id: "cash", mode: "CASH", clearedAt: "2026-05-03", dueDate: "2026-05-01" }),
            row({ id: "bounced", status: "BOUNCED", depositedAt: "2026-04-02", dueDate: "2026-04-01", statusChangedAt: "2026-04-05T08:00:00Z" }),
            row({ id: "old-server", depositedAt: undefined, clearedAt: null, dueDate: "2026-03-01" }),
        ]);
        renderPage();
        await waitFor(() => expect(screen.getByTestId("history-row-cleared")).toBeInTheDocument());

        expect(screen.getByTestId("bank-dates-cleared").textContent).toBe("Deposited on 02/06/2026 · Cleared on 04/06/2026");
        expect(screen.getByTestId("bank-dates-at-bank").textContent).toBe("Deposited on 01/07/2026");
        expect(screen.getByTestId("at-bank-at-bank")).toBeInTheDocument();
        expect(screen.getByTestId("bank-dates-cash").textContent).toBe("Collected on 03/05/2026");
        expect(screen.getByTestId("bank-dates-bounced").textContent).toBe("Deposited on 02/04/2026");
        expect(screen.queryByTestId("bank-dates-old-server")).toBeNull();
    });

    it("opens an attached scan through the app, and only where there is one", async () => {
        api.myPayments.mockResolvedValue([
            row({ id: "scanned", hasImage: true }),
            row({ id: "plain", hasImage: false, dueDate: "2026-07-01" }),
        ]);
        renderPage();
        await waitFor(() => expect(screen.getByTestId("history-row-scanned")).toBeInTheDocument());
        expect(screen.getByTestId("scan-link-scanned")).toHaveAttribute("href", "/api/proxy/v1/cheques/scanned/image");
        expect(screen.queryByTestId("scan-link-plain")).toBeNull();
    });

    it("reads in Arabic", async () => {
        api.myPayments.mockResolvedValue([row({ id: "cleared", depositedAt: "2026-06-02", clearedAt: "2026-06-04", hasImage: true })]);
        renderPage("ar");
        await waitFor(() => expect(screen.getByTestId("bank-dates-cleared")).toBeInTheDocument());
        expect(screen.getByTestId("bank-dates-cleared").textContent).toContain("تم الإيداع في");
        expect(screen.getByTestId("bank-dates-cleared").textContent).toContain("تم صرف الشيك في");
        expect(screen.getByTestId("scan-link-cleared").textContent).toContain("عرض صورة الشيك");
    });
});
