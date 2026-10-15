import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fetchRouter } from "@/test/fetchRouter";

const translate = vi.hoisted(() => (key: string, vars?: Record<string, string | number>) =>
    vars ? `${key}:${JSON.stringify(vars)}` : key);
vi.mock("next-intl", () => ({ useTranslations: () => translate, useLocale: () => "en" }));
vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { id: "u-1", role: "TENANT_ADMIN" } }, status: "authenticated" }),
}));

import BookingsPage from "../page";

const page = (content: unknown[]) => ({ content, totalElements: content.length, number: 0, size: 10, totalPages: 1 });
let api: ReturnType<typeof fetchRouter>;

beforeEach(() => {
    api = fetchRouter();
    api.on("GET", "/v1/properties", { body: [] });
    api.on("GET", "/v1/bookings", { body: page([]) });
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const bookingCalls = () => api.callsTo("GET", "/v1/bookings?");

describe("Bookings status filter says what it loads", () => {
    it("opens on the Pending queue, and the select shows Pending", async () => {
        render(<BookingsPage />);
        await waitFor(() => expect(bookingCalls()).toHaveLength(1));
        expect(bookingCalls()[0].url).toContain("status=PENDING");
        expect((screen.getByTestId("bookings-status-filter") as HTMLSelectElement).value).toBe("PENDING");
    });

    it("All statuses loads every status (no status parameter)", async () => {
        render(<BookingsPage />);
        await waitFor(() => expect(bookingCalls()).toHaveLength(1));
        fireEvent.change(screen.getByTestId("bookings-status-filter"), { target: { value: "" } });
        await waitFor(() => expect(bookingCalls()).toHaveLength(2));
        expect(bookingCalls()[1].url).not.toContain("status=");
    });

    it("an empty Pending queue names the filter and offers every status", async () => {
        render(<BookingsPage />);
        const empty = await screen.findByTestId("bookings-empty");
        expect(empty.textContent).toContain("noBookingsWithStatus");
        fireEvent.click(screen.getByTestId("bookings-show-all"));
        await waitFor(() => expect(bookingCalls()).toHaveLength(2));
        expect(bookingCalls()[1].url).not.toContain("status=");
        expect((screen.getByTestId("bookings-status-filter") as HTMLSelectElement).value).toBe("");
    });
});
