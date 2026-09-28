import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
import RentSettings from "../RentSettings";

/**
 * Break round 3, F1 (stale response): picking property A then quickly B used
 * to let A's slower settings read land last and fill B's form — and Save then
 * wrote A's settings onto B. The mock ignores the abort signal (worst case:
 * the response was already in hand), so the isCurrent guard is what is tested.
 */
const posted: { url: string; body: Record<string, unknown> }[] = [];
const held: Record<string, (body: unknown) => void> = {};

beforeEach(() => {
    posted.length = 0;
    global.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (url.includes("/v1/properties")) return new Response(JSON.stringify([
            { property: { id: "pA", nameEn: "Alpha" } }, { property: { id: "pB", nameEn: "Bravo" } },
        ]));
        if (url.includes("/v1/settings/fines")) return new Response("{}", { status: 404 });
        if (url.includes("/v1/rent-settings/") && init?.method === "POST") {
            posted.push({ url, body: JSON.parse(String(init.body)) });
            return new Response(String(init.body));
        }
        const m = url.match(/rent-settings\/(\w+)/);
        if (m) return new Promise<Response>(resolve => { held[m[1]] = body => resolve(new Response(JSON.stringify(body))); });
        return new Response("[]");
    }) as unknown as typeof fetch;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("RentSettings — a slow read for the previous property never fills the form", () => {
    it("A picked, then B: A's late answer is ignored and Save writes B's own settings to B", async () => {
        render(<RentSettings embedded />);
        await screen.findByText("Alpha");
        const picker = screen.getAllByRole("combobox")[0];
        fireEvent.change(picker, { target: { value: "pA" } });
        await waitFor(() => expect(held.pA).toBeDefined());
        fireEvent.change(picker, { target: { value: "pB" } });
        await waitFor(() => expect(held.pB).toBeDefined());
        await act(async () => { held.pB({ propertyId: "pB", dueDayOfMonth: 9 }); });
        await screen.findByRole("button", { name: /save/i });
        await act(async () => { held.pA({ propertyId: "pA", dueDayOfMonth: 3 }); });
        await act(async () => { await new Promise(r => setTimeout(r, 0)); });
        fireEvent.click(screen.getByRole("button", { name: /save/i }));
        await waitFor(() => expect(posted).toHaveLength(1));
        expect(posted[0].url).toContain("/rent-settings/pB");
        expect(posted[0].body).toMatchObject({ dueDayOfMonth: 9 });
    });
});

describe("RentSettings under StrictMode (review M3)", () => {
    it("the aborted first effect run does not end the initial load before the real one answers", async () => {
        const { StrictMode } = await import("react");
        const heldProps: ((body: unknown) => void)[] = [];
        global.fetch = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
            const url = String(input);
            if (url.includes("/v1/properties")) {
                return new Promise<Response>((resolve, reject) => {
                    init?.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
                    heldProps.push(body => resolve(new Response(JSON.stringify(body))));
                });
            }
            if (url.includes("/v1/settings/fines")) {
                return new Promise<Response>((resolve, reject) => {
                    init?.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
                    setTimeout(() => resolve(new Response("{}", { status: 404 })), 50);
                });
            }
            return Promise.resolve(new Response("[]"));
        }) as unknown as typeof fetch;

        render(<StrictMode><RentSettings embedded /></StrictMode>);
        await waitFor(() => expect(heldProps.length).toBe(2)); // one per (double-invoked) effect run
        await act(async () => { await new Promise(r => setTimeout(r, 80)); }); // first run aborted, fines answered
        expect(screen.queryByRole("heading", { name: /rent collection settings/i })).toBeNull(); // still the skeleton
        await act(async () => { heldProps[1]([{ property: { id: "pA", nameEn: "Alpha" } }]); });
        expect(await screen.findByText("Alpha")).toBeTruthy();
    });
});
