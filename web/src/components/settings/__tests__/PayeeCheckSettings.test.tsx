import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
import PayeeCheckSettings from "../PayeeCheckSettings";

// Owner ruling 2026-09-29: Settings › Organisation has an on/off switch and a
// list of valid payee names the Company Admin types; the organisation's own
// name is not added for them.
let current: { enabled: boolean; validNames: string[] };
const puts: unknown[] = [];

beforeEach(() => {
    current = { enabled: false, validNames: [] };
    puts.length = 0;
    global.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        expect(String(input)).toBe("/api/proxy/v1/settings/org/payee-check");
        if (init?.method === "PUT") {
            const body = JSON.parse(String(init.body));
            puts.push(body);
            current = body;
            return { ok: true, status: 200, json: async () => body } as unknown as Response;
        }
        return { ok: true, status: 200, json: async () => current } as unknown as Response;
    }) as unknown as typeof fetch;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("PayeeCheckSettings", () => {
    it("turns the check on with the names typed, one per line, and adds nothing of its own", async () => {
        render(<PayeeCheckSettings />);
        const toggle = await screen.findByRole("switch", { name: "Check the payee name on scanned cheques" });
        expect(toggle).toHaveAttribute("aria-checked", "false");

        fireEvent.click(toggle);
        fireEvent.change(screen.getByLabelText("Valid payee names (one per line)"), {
            target: { value: "Palm Ridge Properties LLC\n\n  بالم ريدج للعقارات  \n" },
        });
        fireEvent.click(screen.getByRole("button", { name: "Save" }));

        await waitFor(() => expect(puts).toHaveLength(1));
        expect(puts[0]).toEqual({ enabled: true, validNames: ["Palm Ridge Properties LLC", "بالم ريدج للعقارات"] });
        expect(await screen.findByText("Saved.")).toBeInTheDocument();
    });

    it("shows the saved settings and warns when the list is empty", async () => {
        current = { enabled: true, validNames: [] };
        render(<PayeeCheckSettings />);
        expect(await screen.findByText("No names are listed, so no cheque is checked until you add at least one.")).toBeInTheDocument();

        cleanup();
        current = { enabled: true, validNames: ["Alpha", "Beta"] };
        render(<PayeeCheckSettings />);
        await waitFor(() => expect(screen.getByLabelText("Valid payee names (one per line)")).toHaveValue("Alpha\nBeta"));
        expect(screen.getByRole("switch")).toHaveAttribute("aria-checked", "true");
    });

    it("says so when saving fails", async () => {
        global.fetch = vi.fn(async (_i: RequestInfo | URL, init?: RequestInit) =>
            init?.method === "PUT"
                ? ({ ok: false, status: 400, json: async () => ({ message: "x" }) } as unknown as Response)
                : ({ ok: true, status: 200, json: async () => current } as unknown as Response)) as unknown as typeof fetch;
        render(<PayeeCheckSettings />);
        fireEvent.click(await screen.findByRole("button", { name: "Save" }));
        expect(await screen.findByText("Could not save the payee name check.")).toBeInTheDocument();
    });
});
