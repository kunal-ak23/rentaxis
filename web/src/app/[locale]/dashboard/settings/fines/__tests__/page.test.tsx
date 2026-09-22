import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";

/**
 * FineConfigDTO's own three penalty-module fields (spec §7.3) — nullable on
 * the wire, but this screen always has a value to send once loaded. Pins that
 * the PUT payload carries them, not just the original five amounts.
 */

vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { role: "TENANT_ADMIN", name: "Admin" } } }),
}));

import FinesSettingsPage from "../page";

function renderPage() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <FinesSettingsPage />
        </NextIntlClientProvider>,
    );
}

const serverConfig = {
    bounceAmount: 500, signatureMismatchAmount: 500, accountClosedAmount: 1000,
    graceDays: 7, perDayRate: 25,
    bouncesBeforePenalty: 3, autoProposeChequeReturn: true, autoProposeLatePayment: false,
};

beforeEach(() => {
    global.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
        if (init?.method === "PUT") {
            return { ok: true, json: async () => JSON.parse(init.body as string) } as Response;
        }
        return { ok: true, json: async () => serverConfig } as Response;
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("FinesSettingsPage — penalty module fields", () => {
    it("loads the bounces-before-penalty threshold and the two auto-propose toggles", async () => {
        renderPage();
        const input = await screen.findByDisplayValue("3");
        expect(input).toBeInTheDocument();
        const checkboxes = await screen.findAllByRole("checkbox");
        expect(checkboxes).toHaveLength(2);
        expect(checkboxes[0]).toBeChecked();
        expect(checkboxes[1]).not.toBeChecked();
    });

    it("sends the penalty-module fields on save, not just the five amounts", async () => {
        renderPage();
        await screen.findByDisplayValue("3");

        screen.getByText("Save").click();

        await waitFor(() => {
            const putCall = (fetch as ReturnType<typeof vi.fn>).mock.calls.find(
                (c: unknown[]) => (c[1] as RequestInit | undefined)?.method === "PUT",
            );
            expect(putCall).toBeTruthy();
            const body = JSON.parse((putCall![1] as RequestInit).body as string);
            expect(body).toMatchObject({
                bouncesBeforePenalty: 3,
                autoProposeChequeReturn: true,
                autoProposeLatePayment: false,
            });
        });
    });
});
