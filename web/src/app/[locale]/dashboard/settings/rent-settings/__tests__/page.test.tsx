import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());

vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: null }),
}));

import RentSettingsPage from "../page";

const sampleProperties = [
    { property: { id: "p1", nameEn: "Belle Vue", nameAr: "بيل فيو" } },
];

/** What GET /v1/rent-settings/{propertyId} answers with. */
let settingsResponse: { status: number; body?: unknown } = { status: 204 };

function stubFetch() {
    global.fetch = vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.includes("/v1/properties")) {
            return { ok: true, status: 200, json: async () => sampleProperties } as unknown as Response;
        }
        if (url.includes("/v1/settings/fines")) {
            return { ok: false, status: 404, json: async () => ({}) } as unknown as Response;
        }
        if (url.includes("/v1/rent-settings/")) {
            const { status, body } = settingsResponse;
            return {
                ok: status >= 200 && status < 300,
                status,
                json: async () => {
                    // A real 204 has an empty body — res.json() rejects.
                    if (body === undefined) throw new SyntaxError("Unexpected end of JSON input");
                    return body;
                },
            } as unknown as Response;
        }
        return { ok: true, status: 200, json: async () => [] } as unknown as Response;
    }) as unknown as typeof fetch;
}

beforeEach(() => {
    settingsResponse = { status: 204 };
    stubFetch();
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

async function selectProperty() {
    render(<RentSettingsPage />);
    const select = (await screen.findAllByRole("combobox"))[0];
    await screen.findByText("Belle Vue");
    fireEvent.change(select, { target: { value: "p1" } });
}

describe("RentSettingsPage fetchSettings", () => {
    it("treats the backend's 204 No Content as 'no settings yet' without parsing the body", async () => {
        await selectProperty();

        // The settings form (headed by the "Settings" card title) renders with defaults.
        expect(await screen.findByText("Settings")).toBeTruthy();
        expect(screen.queryByText("Failed to load rent settings.")).toBeNull();
    });

    it("hydrates the form from a 200 response body", async () => {
        settingsResponse = {
            status: 200,
            body: {
                id: "rs-1",
                propertyId: "p1",
                dueDayOfMonth: 15,
                gracePeriodDays: 3,
                penaltyType: "NONE",
                penaltyAmount: 0,
                onlinePaymentEnabled: false,
            },
        };
        await selectProperty();

        expect(await screen.findByText("Settings")).toBeTruthy();
        expect(screen.getByDisplayValue("15")).toBeTruthy();
    });

    /** Spec §4a: the renewal notice threshold loads and is saved with the rest. */
    it("loads and saves the renewal increase notice threshold", async () => {
        settingsResponse = { status: 200, body: { id: "rs-1", propertyId: "p1", dueDayOfMonth: 1, gracePeriodDays: 5,
            penaltyType: "NONE", penaltyAmount: 0, onlinePaymentEnabled: false, renewalIncreaseWarnPercent: 5 } };
        await selectProperty();
        const input = (await screen.findByTestId("renewal-warn-percent")) as HTMLInputElement;
        expect(input.value).toBe("5");
        fireEvent.change(input, { target: { value: "7.5" } });
        const fetchMock = global.fetch as unknown as ReturnType<typeof vi.fn>;
        fireEvent.click(screen.getByRole("button", { name: /save/i }));
        await vi.waitFor(() => expect(fetchMock.mock.calls.some(c => (c[1] as RequestInit | undefined)?.method === "POST")).toBe(true));
        const post = fetchMock.mock.calls.find(c => (c[1] as RequestInit | undefined)?.method === "POST")!;
        expect(JSON.parse(String((post[1] as RequestInit).body)).renewalIncreaseWarnPercent).toBe(7.5);
    });

    it("surfaces an error (instead of silently showing defaults) when the load fails", async () => {
        settingsResponse = { status: 500, body: { message: "boom" } };
        await selectProperty();

        expect(await screen.findByText("Failed to load rent settings.")).toBeTruthy();
        expect(screen.queryByText("Settings")).toBeNull();
    });

    it("surfaces an error on a network failure instead of pretending there are no settings", async () => {
        global.fetch = vi.fn(async (input: RequestInfo | URL) => {
            const url = String(input);
            if (url.includes("/v1/properties")) {
                return { ok: true, status: 200, json: async () => sampleProperties } as unknown as Response;
            }
            if (url.includes("/v1/rent-settings/")) {
                throw new TypeError("Failed to fetch");
            }
            return { ok: false, status: 404, json: async () => ({}) } as unknown as Response;
        }) as unknown as typeof fetch;

        render(<RentSettingsPage />);
        const select = (await screen.findAllByRole("combobox"))[0];
        await screen.findByText("Belle Vue");
        fireEvent.change(select, { target: { value: "p1" } });

        expect(await screen.findByText("Failed to load rent settings.")).toBeTruthy();
        expect(screen.queryByText("Settings")).toBeNull();
    });
});
