import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// Break round 1 (P1): a fast double-click on "Create" in Add Tenant created
// two renter records. The submit must be single-flight — a second click (or a
// second Enter) while the first request is in flight posts nothing.

vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }),
}));
vi.mock("next-intl", () => ({
    useTranslations: () => Object.assign((key: string) => key, { has: () => false }),
    useLocale: () => "en",
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));

import RentersPage from "../page";

const jsonRes = (body: unknown, ok = true, status = 200) =>
    ({ ok, status, json: async () => body, text: async () => JSON.stringify(body) }) as unknown as Response;

let posts: number;
let releasePost: (() => void) | null;

beforeEach(() => {
    posts = 0;
    releasePost = null;
    global.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
        const u = String(url);
        if (u.endsWith("/v1/renters") && init?.method === "POST") {
            posts++;
            // Hold the POST open so the second click lands while it is in flight.
            await new Promise<void>((resolve) => { releasePost = resolve; });
            return jsonRes({ id: "r1", invitePending: false }, true, 201);
        }
        if (u.includes("/v1/renters/paged")) {
            return jsonRes({ content: [], totalElements: 0, totalPages: 1, number: 0, size: 25 });
        }
        return jsonRes({}, false, 404);
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

async function openAndFill() {
    render(<RentersPage />);
    fireEvent.click((await screen.findAllByText("addRenter"))[0]);
    fireEvent.change(screen.getByPlaceholderText("John Doe"), { target: { value: "Double Click" } });
    fireEvent.change(screen.getByPlaceholderText("john@example.com"), { target: { value: "dbl@x.com" } });
}

describe("RentersPage create is single-flight", () => {
    it("a double-click on Create posts exactly once", async () => {
        await openAndFill();
        const create = screen.getByText("create");
        fireEvent.click(create);
        fireEvent.click(create);
        await waitFor(() => expect(posts).toBe(1));
        // Still in flight: a third click also does nothing.
        fireEvent.click(create);
        releasePost?.();
        expect(await screen.findByText("noInviteBody")).toBeTruthy();
        expect(posts).toBe(1);
    });

    it("two rapid form submits (Enter twice) post exactly once", async () => {
        await openAndFill();
        const form = screen.getByPlaceholderText("John Doe").closest("form")!;
        fireEvent.submit(form);
        fireEvent.submit(form);
        await waitFor(() => expect(posts).toBe(1));
        fireEvent.submit(form);
        releasePost?.();
        expect(await screen.findByText("noInviteBody")).toBeTruthy();
        expect(posts).toBe(1);
    });

    it("disables the Create button while the request is in flight", async () => {
        await openAndFill();
        const create = screen.getByText("create").closest("button")!;
        fireEvent.click(create);
        await waitFor(() => expect(posts).toBe(1));
        expect(create.disabled).toBe(true);
        releasePost?.();
        expect(await screen.findByText("noInviteBody")).toBeTruthy();
    });
});
