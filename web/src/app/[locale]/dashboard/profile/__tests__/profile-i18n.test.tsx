import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";
import ar from "../../../../../../messages/ar.json";
import { leftoverLatinWords, visibleText } from "@/test/latinText";

/**
 * Break-it R2 portal2, pass 2: the profile page was English-only chrome, its
 * refused-save line appended the backend's English sentence under /ar, and a
 * junk phone ("notaphone!!!123") went straight to the server.
 */

vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { role: "RENTER", name: "Renter" } } }),
}));

import ProfilePage from "../page";

const profile = { id: "u1", email: "r@example.invalid", name: "Renter One", role: "RENTER", phoneNumber: "+971501234567" };
let put: () => Response;
let puts: RequestInit[];

beforeEach(() => {
    puts = [];
    global.fetch = vi.fn(async (_url: unknown, init?: RequestInit) => {
        if (init?.method === "PUT") { puts.push(init); return put(); }
        return new Response(JSON.stringify(profile), { status: 200, headers: { "Content-Type": "application/json" } });
    }) as unknown as typeof fetch;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

function renderPage(locale: "en" | "ar") {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}>
            <ProfilePage />
        </NextIntlClientProvider>,
    );
}

async function saveWith(locale: "en" | "ar", fields: { name?: string; phone?: string }) {
    renderPage(locale);
    const name = await screen.findByDisplayValue("Renter One");
    if (fields.name !== undefined) fireEvent.change(name, { target: { value: fields.name } });
    if (fields.phone !== undefined) fireEvent.change(screen.getByDisplayValue("+971501234567"), { target: { value: fields.phone } });
    fireEvent.submit(name.closest("form")!);
}

describe("ProfilePage — Arabic", () => {
    it("carries no English chrome, the password form included", async () => {
        const { container } = renderPage("ar");
        await screen.findByDisplayValue("Renter One");
        fireEvent.click(screen.getByText(ar.Profile.changePassword));
        expect(leftoverLatinWords(visibleText(container), ["Renter One", "r@example.invalid", "+971501234567", "+971 50 123 4567"])).toEqual([]);
    });

    it("uses logical (RTL-safe) insets, never left/right paddings", async () => {
        const { container } = renderPage("ar");
        await screen.findByDisplayValue("Renter One");
        expect(container.innerHTML).not.toMatch(/\b(?:left|right)-\d|\b(?:pl|pr|ml|mr)-\d/);
    });

    it("maps a too-long refusal to a localized sentence, no English appended", async () => {
        put = () => json(400, { error: true, status: 400, message: "Validation failed: name: Name must be at most 255 characters" });
        await saveWith("ar", { name: "New" });
        expect((await screen.findByRole("alert")).textContent).toBe(ar.Profile.nameTooLong);
    });

    it("maps the column-overflow 400 the same way", async () => {
        put = () => json(400, { error: true, status: 400, message: "One of the values is too long for its field. Shorten it and try again." });
        await saveWith("ar", { name: "New" });
        expect((await screen.findByRole("alert")).textContent).toBe(ar.Profile.nameTooLong);
    });

    it("shows only the localized sentence for an unknown refusal", async () => {
        put = () => json(409, { message: "This action conflicts with existing related records." });
        await saveWith("ar", { name: "New" });
        expect((await screen.findByRole("alert")).textContent).toBe(ar.Profile.saveFailed);
    });

    it("says a mismatched password confirmation in Arabic", async () => {
        renderPage("ar");
        await screen.findByDisplayValue("Renter One");
        fireEvent.click(screen.getByText(ar.Profile.changePassword));
        const [current, next, confirm] = Array.from(document.querySelectorAll<HTMLInputElement>('input[type="password"]'));
        fireEvent.change(current, { target: { value: "old-password" } });
        fireEvent.change(next, { target: { value: "new-password-1" } });
        fireEvent.change(confirm, { target: { value: "new-password-2" } });
        fireEvent.submit(current.closest("form")!);
        expect(await screen.findByText(ar.Profile.passwordsDoNotMatch)).toBeInTheDocument();
    });

    it("says a wrong current password in Arabic", async () => {
        put = () => json(400, { error: "Current password is incorrect" });
        renderPage("ar");
        await screen.findByDisplayValue("Renter One");
        fireEvent.click(screen.getByText(ar.Profile.changePassword));
        const [current, next, confirm] = Array.from(document.querySelectorAll<HTMLInputElement>('input[type="password"]'));
        fireEvent.change(current, { target: { value: "wrong-password" } });
        fireEvent.change(next, { target: { value: "new-password-1" } });
        fireEvent.change(confirm, { target: { value: "new-password-1" } });
        fireEvent.submit(current.closest("form")!);
        expect(await screen.findByText(ar.Profile.currentPasswordIncorrect)).toBeInTheDocument();
    });
});

describe("ProfilePage — phone", () => {
    it("refuses a phone that is not one, before sending anything", async () => {
        await saveWith("en", { phone: "notaphone!!!123" });
        expect((await screen.findByRole("alert")).textContent).toBe(en.Profile.phoneInvalid);
        expect(puts).toHaveLength(0);
    });

    it.each(["050 8831786", "+971-4-555-0100", "04 123 9911", "", "+971 (4) 555 0100"])("accepts %j", async (phone) => {
        put = () => json(200, { ...profile, phoneNumber: phone });
        await saveWith("en", { phone });
        expect(await screen.findByText(en.Profile.saved)).toBeInTheDocument();
        expect(puts).toHaveLength(1);
    });

    it("maps the server's phone refusal to the localized sentence in Arabic", async () => {
        put = () => json(400, { message: "Validation failed: phoneNumber: Phone number may contain only digits, spaces, +, - and parentheses (7-15 digits)" });
        await saveWith("ar", { phone: "+971501234567" });
        expect((await screen.findByRole("alert")).textContent).toBe(ar.Profile.phoneInvalid);
    });
});
