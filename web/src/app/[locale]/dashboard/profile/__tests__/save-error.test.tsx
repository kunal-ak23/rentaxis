import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";
import ar from "../../../../../../messages/ar.json";

/**
 * Break-it round 2 (portal2) F2: a renter saved a 500-character name, the
 * backend refused it (users.name is varchar(255)), and handleSave had no
 * branch for a non-OK response — the spinner stopped and nothing was said.
 * A refused save must be visible, in the user's language, with the server's
 * reason when it gives one.
 */

vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { role: "RENTER", name: "Renter" } } }),
}));

import ProfilePage from "../page";

const profile = { id: "u1", email: "r@example.invalid", name: "Renter One", role: "RENTER", phoneNumber: null };
let putResponse: () => Response;

beforeEach(() => {
    global.fetch = vi.fn(async (_url: unknown, init?: RequestInit) => {
        if (init?.method === "PUT") return putResponse();
        return new Response(JSON.stringify(profile), { status: 200, headers: { "Content-Type": "application/json" } });
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

function renderPage(locale: "en" | "ar" = "en") {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}>
            <ProfilePage />
        </NextIntlClientProvider>,
    );
}

async function save(locale: "en" | "ar" = "en") {
    renderPage(locale);
    const input = await screen.findByDisplayValue("Renter One");
    fireEvent.change(input, { target: { value: "New name" } });
    fireEvent.submit(input.closest("form")!);
}

describe("ProfilePage — a refused save is shown", () => {
    it("shows the server's message after the generic line (EN) when the save is refused", async () => {
        putResponse = () => new Response(JSON.stringify({ error: true, status: 400, message: "Something unexpected happened." }),
            { status: 400, headers: { "Content-Type": "application/json" } });
        await save();
        const alert = await screen.findByRole("alert");
        expect(alert).toHaveTextContent(en.Profile.saveFailed);
        expect(alert).toHaveTextContent("Something unexpected happened.");
        expect(screen.queryByText(/^saved$/i)).toBeNull();
    });

    it("shows a generic reason when the body has none (e.g. a proxy 409), in Arabic too", async () => {
        putResponse = () => new Response("", { status: 409 });
        await save("ar");
        const alert = await screen.findByRole("alert");
        expect(alert).toHaveTextContent(ar.Profile.saveFailed);
    });

    it("shows an error when the request itself fails", async () => {
        putResponse = () => { throw new TypeError("Failed to fetch"); };
        await save();
        expect(await screen.findByRole("alert")).toHaveTextContent(en.Profile.saveFailed);
    });

    it("clears the error once a later save succeeds", async () => {
        putResponse = () => new Response("", { status: 500 });
        await save();
        await screen.findByRole("alert");
        putResponse = () => new Response(JSON.stringify({ ...profile, name: "New name" }), { status: 200, headers: { "Content-Type": "application/json" } });
        fireEvent.submit(screen.getByDisplayValue("New name").closest("form")!);
        await screen.findByText(en.Profile.saved);
        expect(screen.queryByRole("alert")).toBeNull();
    });

    it("caps the name at the column's 255 characters", async () => {
        renderPage();
        const input = await screen.findByDisplayValue("Renter One");
        expect(input).toHaveAttribute("maxLength", "255");
    });
});
