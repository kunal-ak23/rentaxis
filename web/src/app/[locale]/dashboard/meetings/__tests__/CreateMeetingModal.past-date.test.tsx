import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import CreateMeetingModal from "../CreateMeetingModal";
import { businessTodayIso } from "@/lib/businessDate";

// Break round 1: the meeting wizard let a user pick 1900-01-01, showed a grid
// of "available" slots for it and walked all five steps before the final
// submit said "Cannot book a slot in the past". The date step now refuses a
// date before today in Asia/Dubai: `min` on the input, a message, no slot
// lookup, and Next stays disabled.

vi.mock("next-intl", () => ({
    useTranslations: () => (key: string) => key,
    useLocale: () => "en",
}));

const staffSession = { user: { role: "TENANT_ADMIN" } };
let slotCalls: string[];

beforeEach(() => {
    slotCalls = [];
    global.fetch = vi.fn().mockImplementation((input: RequestInfo | URL) => {
        const url = String(input);
        const json = (body: unknown) => Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) });
        if (url.startsWith("/api/proxy/v1/meetings/slots")) {
            slotCalls.push(url);
            return json([{ start: "2030-01-01T09:00:00Z", end: "2030-01-01T09:30:00Z", available: true }]);
        }
        if (url.startsWith("/api/proxy/v1/properties")) {
            return json([{ property: { id: "p1", nameEn: "Marina Tower" }, assignedManagers: [{ id: "mgr-1", name: "Alice", email: "a@x.com" }] }]);
        }
        return json([]);
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

const modal = () => <CreateMeetingModal isOpen onClose={() => {}} onSuccess={() => {}} session={staffSession} />;
let rerenderModal: () => void;

async function toDateStep() {
    const r = render(modal());
    rerenderModal = () => r.rerender(modal());
    fireEvent.click(screen.getByText("propertyVisit"));
    fireEvent.click(screen.getByRole("button", { name: /create\.next/ }));
    await screen.findByText("Marina Tower");
    fireEvent.change(screen.getAllByRole("combobox")[0], { target: { value: "p1" } });
    fireEvent.click(screen.getByRole("button", { name: /create\.next/ }));
    await screen.findByText("create.preferredDate *");
    return document.querySelector('input[type="date"]') as HTMLInputElement;
}

const nextButton = () => screen.getByRole("button", { name: /create\.next/ }) as HTMLButtonElement;

describe("meeting wizard refuses a past date at the date step", () => {
    it("sets the input's min to today in Asia/Dubai", async () => {
        const input = await toDateStep();
        expect(input.min).toBe(businessTodayIso());
    });

    it("a typed 1900-01-01 shows a message, fetches no slots and cannot proceed", async () => {
        const input = await toDateStep();
        fireEvent.change(input, { target: { value: "1900-01-01" } });

        expect(await screen.findByText("create.dateInPast")).toBeTruthy();
        expect(slotCalls).toEqual([]);
        expect(screen.queryByText("create.availableSlots")).toBeNull();
        expect(nextButton().disabled).toBe(true);
        fireEvent.click(nextButton());
        expect(screen.getByText("create.preferredDate *")).toBeTruthy();
    });

    it("a future date still loads slots and can proceed once a slot is picked", async () => {
        const input = await toDateStep();
        fireEvent.change(input, { target: { value: "2030-01-01" } });
        expect(screen.queryByText("create.dateInPast")).toBeNull();
        await waitFor(() => expect(slotCalls.length).toBeGreaterThan(0));
        const slot = await screen.findByText((_, el) => el?.tagName === "BUTTON" && /\d{1,2}:\d{2}/.test(el.textContent ?? ""));
        fireEvent.click(slot);
        await waitFor(() => expect(nextButton().disabled).toBe(false));
    });

    it("a picked slot for today stops counting once Dubai passes midnight", async () => {
        vi.useFakeTimers({ toFake: ["Date"] });
        try {
            vi.setSystemTime(new Date("2030-01-01T10:00:00+04:00"));
            const input = await toDateStep();
            fireEvent.change(input, { target: { value: "2030-01-01" } });
            const slot = await screen.findByText((_, el) => el?.tagName === "BUTTON" && /\d{1,2}:\d{2}/.test(el.textContent ?? ""));
            fireEvent.click(slot);
            await waitFor(() => expect(nextButton().disabled).toBe(false));

            vi.setSystemTime(new Date("2030-01-02T00:05:00+04:00"));
            rerenderModal();
            expect(nextButton().disabled).toBe(true);
            expect(screen.getByText("create.dateInPast")).toBeTruthy();
        } finally {
            vi.useRealTimers();
        }
    });
});
