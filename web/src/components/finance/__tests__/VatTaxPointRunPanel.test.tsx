import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";

const api = vi.hoisted(() => ({ run: vi.fn() }));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, vatApi: { ...m.vatApi, ...api } };
});

import VatTaxPointRunPanel from "../VatTaxPointRunPanel";

const result = (preview: boolean) => ({
    preview, posted: preview ? 0 : 2, wouldPost: 2, vatAmount: 3000, points: [],
    skippedLocked: 1, booksLockedThrough: "2026-04-30", errors: [],
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("VatTaxPointRunPanel", () => {
    it("previews first, then declares — the Declare button is dead until a preview found something", async () => {
        api.run.mockImplementation((_to: string, dryRun: boolean) => Promise.resolve(result(dryRun)));
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <VatTaxPointRunPanel />
            </NextIntlClientProvider>,
        );
        expect(screen.getByTestId("vat-run-post")).toBeDisabled();

        fireEvent.change(screen.getByTestId("vat-run-to"), { target: { value: "2026-08-01" } });
        fireEvent.click(screen.getByTestId("vat-run-preview"));
        await waitFor(() => expect(screen.getByTestId("vat-run-result")).toHaveTextContent("VAT 3,000.00"));
        expect(api.run).toHaveBeenLastCalledWith("2026-08-01", true);
        expect(screen.getByTestId("vat-run-result")).toHaveTextContent("locked period");

        fireEvent.click(screen.getByTestId("vat-run-post"));
        await waitFor(() => expect(api.run).toHaveBeenLastCalledWith("2026-08-01", false));
        expect(await screen.findByText(/Declared: 2 tax points/)).toBeInTheDocument();
    });
});
