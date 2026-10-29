import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());

import PromoterApiKeysManager from "../PromoterApiKeysManager";

const base = {
    tenantId: "t1", promoterUserId: "u1", createdAt: "2026-10-01T10:00:00Z", lastUsedAt: null,
    expiresAt: null, revokedAt: null, revokedReason: null,
};
const ROWS = [
    { ...base, id: "k1", orgName: "Acme Realty", promoterName: "Pia Promoter", promoterEmail: "pia@x.test", name: "CRM sync", keyPrefix: "mfk_live_AbCd", status: "active" },
    { ...base, id: "k2", orgName: "Beta Homes", promoterName: "Bo Promoter", promoterEmail: "bo@x.test", name: "Old key", keyPrefix: "mfk_live_ZzZz", status: "revoked", revokedAt: "2026-10-02T10:00:00Z", revokedReason: "leak" },
    { ...base, id: "k3", orgName: "Gamma", promoterName: "Gus", promoterEmail: "gus@x.test", name: "Rotating", keyPrefix: "mfk_live_QqQq", status: "expiring", expiresAt: "2026-11-01T10:00:00Z" },
];

const page = (content: unknown[]) => new Response(JSON.stringify({ content, totalElements: content.length, totalPages: 1, number: 0, size: 25 }));
let urls: string[] = [];
let posts: { url: string; body: string }[] = [];
let listAnswer: () => Response;
let revokeAnswer: () => Response;

beforeEach(() => {
    urls = []; posts = [];
    listAnswer = () => page(ROWS);
    revokeAnswer = () => new Response(JSON.stringify({ ...ROWS[0], status: "revoked", revokedReason: "leaked on GitHub" }));
    global.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (init?.method === "POST") { posts.push({ url, body: String(init.body) }); return revokeAnswer(); }
        urls.push(url);
        return listAnswer();
    }) as unknown as typeof fetch;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const row = (text: string) => screen.getByText(text).closest("tr")!;

describe("PromoterApiKeysManager", () => {
    it("lists keys with org, promoter, prefix and status", async () => {
        render(<PromoterApiKeysManager />);
        await screen.findByText("Acme Realty");
        expect(within(row("Acme Realty")).getByText("Pia Promoter")).toBeInTheDocument();
        expect(within(row("Acme Realty")).getByText("pia@x.test")).toBeInTheDocument();
        expect(within(row("Acme Realty")).getByText("mfk_live_AbCd")).toBeInTheDocument();
        expect(within(row("Acme Realty")).getByTestId("api-key-status")).toHaveTextContent("Active");
        expect(within(row("Beta Homes")).getByTestId("api-key-status")).toHaveTextContent("Revoked");
        expect(urls[0]).toContain("/api/proxy/admin/promoter-api-keys?");
    });

    it("offers Revoke only on active and expiring keys", async () => {
        render(<PromoterApiKeysManager />);
        await screen.findByText("Acme Realty");
        expect(within(row("Acme Realty")).getByTestId("api-key-revoke")).toBeInTheDocument();
        expect(within(row("Gamma")).getByTestId("api-key-revoke")).toBeInTheDocument();
        expect(within(row("Beta Homes")).queryByTestId("api-key-revoke")).toBeNull();
    });

    it("sends the search text as q", async () => {
        render(<PromoterApiKeysManager />);
        await screen.findByText("Acme Realty");
        fireEvent.change(screen.getByTestId("api-keys-search"), { target: { value: "mfk_live_AbCd" } });
        await waitFor(() => expect(urls.some(u => u.includes("q=mfk_live_AbCd"))).toBe(true));
    });

    it("sends the status filter", async () => {
        render(<PromoterApiKeysManager />);
        await screen.findByText("Acme Realty");
        fireEvent.change(screen.getByTestId("api-keys-status-filter"), { target: { value: "revoked" } });
        await waitFor(() => expect(urls.some(u => u.includes("status=revoked"))).toBe(true));
    });

    it("requires a reason, names the key, then revokes and updates the row", async () => {
        render(<PromoterApiKeysManager />);
        await screen.findByText("Acme Realty");
        fireEvent.click(within(row("Acme Realty")).getByTestId("api-key-revoke"));
        const dialog = await screen.findByTestId("api-key-revoke-dialog");
        expect(dialog).toHaveTextContent("Acme Realty");
        expect(dialog).toHaveTextContent("Pia Promoter");
        expect(dialog).toHaveTextContent("mfk_live_AbCd");
        const confirm = screen.getByTestId("api-key-revoke-confirm");
        expect(confirm).toBeDisabled();
        fireEvent.change(screen.getByTestId("api-key-revoke-reason"), { target: { value: "   " } });
        expect(confirm).toBeDisabled();
        fireEvent.change(screen.getByTestId("api-key-revoke-reason"), { target: { value: "  leaked on GitHub " } });
        expect(confirm).toBeEnabled();
        fireEvent.click(confirm);
        await waitFor(() => expect(within(row("Acme Realty")).getByTestId("api-key-status")).toHaveTextContent("Revoked"));
        expect(posts).toHaveLength(1);
        expect(posts[0].url).toBe("/api/proxy/admin/promoter-api-keys/k1/revoke");
        expect(JSON.parse(posts[0].body)).toEqual({ reason: "leaked on GitHub" });
        expect(within(row("Acme Realty")).queryByTestId("api-key-revoke")).toBeNull();
    });

    it("shows a translated error, not the server text, when revoke fails", async () => {
        revokeAnswer = () => new Response(JSON.stringify({ message: "SQL exploded" }), { status: 500 });
        render(<PromoterApiKeysManager />);
        await screen.findByText("Acme Realty");
        fireEvent.click(within(row("Acme Realty")).getByTestId("api-key-revoke"));
        fireEvent.change(await screen.findByTestId("api-key-revoke-reason"), { target: { value: "leak" } });
        fireEvent.click(screen.getByTestId("api-key-revoke-confirm"));
        const err = await screen.findByTestId("api-key-revoke-error");
        expect(err).not.toHaveTextContent("SQL exploded");
        expect(err.textContent!.length).toBeGreaterThan(5);
        // Row 0 is the header; the dialog repeats the org name, so address the row by position.
        expect(within(screen.getAllByRole("row")[1]).getByTestId("api-key-status")).toHaveTextContent("Active");
    });

    it("renders an error state, not an empty list, when the read fails", async () => {
        listAnswer = () => new Response("{}", { status: 500 });
        render(<PromoterApiKeysManager />);
        expect(await screen.findByTestId("page-load-failed")).toBeInTheDocument();
        expect(screen.queryByTestId("api-keys-empty")).toBeNull();
    });

    it("renders access denied on a 403", async () => {
        listAnswer = () => new Response("{}", { status: 403 });
        render(<PromoterApiKeysManager />);
        expect(await screen.findByTestId("page-access-denied")).toBeInTheDocument();
    });

    it("shows the empty state for a genuinely empty page", async () => {
        listAnswer = () => page([]);
        render(<PromoterApiKeysManager />);
        expect(await screen.findByTestId("api-keys-empty")).toBeInTheDocument();
    });
});
