import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";

import ar from "../../../../../../messages/ar.json";
import { leftoverLatinWords, visibleText } from "@/test/latinText";

/**
 * #81: the notifications page chrome was English, and so was every
 * notification, which the server writes in English. Structured rows now read
 * in Arabic; a row from before the change keeps its stored English, which is
 * the only English left on the page.
 */

const sessionData = vi.hoisted(() => ({ user: { role: "TENANT_ADMIN" } }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: sessionData }) }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));

import NotificationsPage from "../page";

const LEGACY = { title: "Ticket Assigned to You", message: "Ticket: Old row" };

beforeEach(() => {
    const minutesAgo = (m: number) => new Date(Date.now() - m * 60000).toISOString();
    global.fetch = vi.fn(async () => ({
        ok: true,
        status: 200,
        json: async () => [
            { id: "n1", type: "TICKET_ASSIGNED", title: "Ticket Assigned to You", message: "Ticket: Leaking tap",
              messageKey: "TICKET_ASSIGNED", params: { ticketTitle: "Leaking tap", ticketRef: "TKT-26/14" },
              referenceType: "TICKET", referenceId: "t1", isRead: false, createdAt: minutesAgo(5) },
            { id: "n2", type: "PAYMENT_OVERDUE", title: "Payment Overdue", message: "Installment #2 of 31500.00 is 4 day(s) overdue.",
              messageKey: "PAYMENT_OVERDUE", params: { seq: "2", amount: "31500.00", days: "4", chequeNo: "700102" },
              referenceType: "CHEQUE", referenceId: "c1", isRead: true, createdAt: minutesAgo(180) },
            { id: "n3", type: "TICKET_ASSIGNED", ...LEGACY,
              referenceType: "TICKET", referenceId: "t2", isRead: true, createdAt: minutesAgo(60 * 24 * 2) },
        ],
    }) as unknown as Response) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("notifications page in Arabic", () => {
    it("renders its chrome and structured notifications in Arabic; only an older row keeps its English", async () => {
        const { container } = render(
            <NextIntlClientProvider locale="ar" messages={ar}>
                <NotificationsPage />
            </NextIntlClientProvider>,
        );

        await screen.findByText(ar.Notifications.messages.PAYMENT_OVERDUE.title);
        expect(screen.getByText(ar.Notifications.title)).toBeInTheDocument();
        expect(screen.getByText(ar.Notifications.markAllRead)).toBeInTheDocument();
        expect(screen.getAllByText(ar.Notifications.referenceType.TICKET).length).toBe(2);
        expect(screen.getByText("منذ 5 دقائق")).toBeInTheDocument();
        expect(container.textContent).toContain("31,500.00 درهم");
        // The legacy row, shown as stored.
        expect(screen.getByText(LEGACY.title)).toBeInTheDocument();

        expect(leftoverLatinWords(visibleText(container), ["Leaking tap", LEGACY.title, LEGACY.message])).toEqual([]);
    });
});
