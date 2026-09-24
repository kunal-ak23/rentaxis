import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";

import en from "../../../../messages/en.json";

/** F14-27: a heads-up on the main dashboard when month-end recognition is behind. */

vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));

const status = vi.fn();
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, recognitionApi: { ...m.recognitionApi, status: (...a: unknown[]) => status(...(a as [])) } };
});

import RecognitionBehindWidget from "../RecognitionBehindWidget";

function renderWidget() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <RecognitionBehindWidget />
        </NextIntlClientProvider>,
    );
}

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("RecognitionBehindWidget", () => {
    it("shows nothing when the close is not behind and the last run did not fail", async () => {
        status.mockResolvedValue({
            behind: 0, behindAmount: 0, oldestPeriodEnd: null, lastRunFor: null,
            lastRunFinishedAt: null, lastRunPosted: 5, lastRunFailed: 0, lastRunErrors: [],
        });
        const { container } = renderWidget();
        await waitFor(() => expect(status).toHaveBeenCalled());
        expect(container).toBeEmptyDOMElement();
    });

    it("names the count, amount and oldest period when behind, and links to the recognition page", async () => {
        status.mockResolvedValue({
            behind: 2, behindAmount: 8000, oldestPeriodEnd: "2026-07-31", lastRunFor: null,
            lastRunFinishedAt: null, lastRunPosted: 0, lastRunFailed: 0, lastRunErrors: [],
        });
        renderWidget();
        expect(await screen.findByTestId("dashboard-recognition-behind")).toHaveTextContent(
            "2 ended periods (8,000.00) not recognised yet, oldest ending 31/07/2026.",
        );
        expect(screen.getByRole("link", { name: /view all/i })).toHaveAttribute("href", "/dashboard/finance/recognition");
    });

    it("names the failed-entry count when the last run failed on something", async () => {
        status.mockResolvedValue({
            behind: 0, behindAmount: 0, oldestPeriodEnd: null, lastRunFor: "2026-08-31",
            lastRunFinishedAt: "2026-09-01T02:00:00Z", lastRunPosted: 10, lastRunFailed: 3, lastRunErrors: ["x", "y", "z"],
        });
        renderWidget();
        expect(await screen.findByTestId("dashboard-recognition-behind")).toHaveTextContent(
            "The last run failed on 3 entries.",
        );
    });
});
