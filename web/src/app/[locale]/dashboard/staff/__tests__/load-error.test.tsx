import { cleanup, render, screen, fireEvent, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";

import en from "../../../../../../messages/en.json";

vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }),
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children }: { href: string; children: React.ReactNode }) => <a href={href}>{children}</a>,
    useRouter: () => ({ push: vi.fn() }),
}));
vi.mock("@/components/ui/Pagination", () => ({ Pagination: () => null }));
vi.mock("@/components/ui/confirm-dialog", () => ({ ConfirmDialog: () => null }));

import StaffPage from "../page";

/**
 * A failed load must not look like an empty list.
 *
 * Roughly a third of the dashboard's pages had no failure path on their initial
 * GET: `if (res.ok) { setState(data) }` with no else left the state at its
 * initial `[]`, so a 401, a 403 or a backend outage rendered identically to
 * "there are no staff records" — with nothing to click.
 *
 * Staff stands in for the pattern here. The other pages were fixed the same
 * way, and a source-level sweep in this file guards the whole directory.
 */

function renderPage() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <StaffPage />
        </NextIntlClientProvider>,
    );
}

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("staff page load failure", () => {
    it("shows an error with a retry when the request fails", async () => {
        global.fetch = vi.fn(async () => ({ ok: false, status: 500, json: async () => ({}) })) as unknown as typeof fetch;

        renderPage();

        await waitFor(() =>
            expect(screen.getByText(en.Common.loadFailedStaff)).toBeInTheDocument(),
        );
        expect(screen.getByRole("button", { name: en.Common.retry })).toBeInTheDocument();
    });

    it("shows no error banner when the list is genuinely empty", async () => {
        global.fetch = vi.fn(async () => ({ ok: true, json: async () => [] })) as unknown as typeof fetch;

        renderPage();

        // The distinction the fix exists for: empty is not an error.
        await waitFor(() => expect(screen.queryByText(/loading/i)).toBeNull());
        expect(screen.queryByText(en.Common.loadFailedStaff)).toBeNull();
    });

    it("retry re-issues the request and clears the banner on success", async () => {
        let failNext = true;
        global.fetch = vi.fn(async () => (failNext
            ? { ok: false, status: 500, json: async () => ({}) }
            : { ok: true, json: async () => [] })) as unknown as typeof fetch;

        renderPage();
        await waitFor(() =>
            expect(screen.getByText(en.Common.loadFailedStaff)).toBeInTheDocument(),
        );

        failNext = false;
        fireEvent.click(screen.getByRole("button", { name: en.Common.retry }));

        await waitFor(() =>
            expect(screen.queryByText(en.Common.loadFailedStaff)).toBeNull(),
        );
    });
});
