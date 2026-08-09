import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// A failed POST /v1/renters (400 validation, duplicate portal email aborting
// the transaction, ...) must surface the backend's {message} body in the
// create form instead of silently doing nothing.

vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }),
}));
vi.mock("next-intl", () => ({
    useTranslations: () => (key: string) => key,
    useLocale: () => "en",
}));

import RentersPage from "../page";

const jsonRes = (body: unknown, ok = true, status = 200) =>
    ({
        ok,
        status,
        json: async () => body,
        text: async () => JSON.stringify(body),
    }) as unknown as Response;

let postResponse: { ok: boolean; status: number; body: unknown };

beforeEach(() => {
    postResponse = { ok: true, status: 200, body: { id: "r1" } };
    global.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
        const u = String(url);
        if (u.includes("/v1/renters") && init?.method === "POST") {
            return jsonRes(postResponse.body, postResponse.ok, postResponse.status);
        }
        if (u.includes("/v1/renters")) {
            return jsonRes([]);
        }
        return jsonRes({}, false, 404);
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("RentersPage create form", () => {
    it("surfaces the backend message when creating a renter fails", async () => {
        postResponse = {
            ok: false,
            status: 400,
            body: { error: true, message: "A portal account with this email already exists.", status: 400 },
        };
        render(<RentersPage />);

        // Empty state and header both render an addRenter button.
        fireEvent.click((await screen.findAllByText("addRenter"))[0]);
        fireEvent.change(screen.getByPlaceholderText("John Doe"), { target: { value: "New Renter" } });
        fireEvent.click(screen.getByText("create"));

        expect(
            await screen.findByText("A portal account with this email already exists.")
        ).toBeTruthy();
    });
});
