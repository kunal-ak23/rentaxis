import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";

import ar from "../../../../../../messages/ar.json";
import { leftoverLatinWords, visibleText } from "@/test/latinText";

/**
 * #81: the renters list kept its Table/Cards toggle, its column headers and
 * the language badge (EN/AR) in English under /ar.
 */

vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }),
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));

import RentersPage from "../page";

const RENTERS = [
    { id: "r1", nameEn: "Rajesh Kumar", nameAr: "راجيش كومار", email: "rajesh@example.com", phone: "+971501234567", primaryLanguage: "EN", invitePending: false, userId: null },
    { id: "r2", nameEn: "Sara Ali", nameAr: "سارة علي", email: null, phone: null, primaryLanguage: "AR", invitePending: false, userId: null },
];
// Data: the English names shown under the Arabic ones, contact details and
// the create form's example values for the English-name, email and phone fields.
const DATA = ["Rajesh Kumar", "Sara Ali", "rajesh@example.com", "+971501234567", "John Doe", "john@example.com"];

beforeEach(() => {
    global.fetch = vi.fn(async () => ({
        ok: true, status: 200, json: async () => RENTERS, text: async () => JSON.stringify(RENTERS),
    }) as unknown as Response) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

const renderAr = () =>
    render(<NextIntlClientProvider locale="ar" messages={ar}><RentersPage /></NextIntlClientProvider>);

describe("renters list in Arabic", () => {
    it("table view: toggle, headers and language badges carry no English", async () => {
        const { container } = renderAr();
        await screen.findByText("راجيش كومار");

        expect(screen.getByText(ar.MasterData.table)).toBeInTheDocument();
        expect(screen.getByText(ar.MasterData.language)).toBeInTheDocument();
        expect(screen.getByText(ar.MasterData.languageEN)).toBeInTheDocument();
        expect(leftoverLatinWords(visibleText(container), DATA)).toEqual([]);
    });

    it("cards view and the create form carry no English", async () => {
        const { container } = renderAr();
        await screen.findByText("راجيش كومار");
        fireEvent.click(screen.getByText(ar.MasterData.cards));
        expect(leftoverLatinWords(visibleText(container), DATA)).toEqual([]);

        fireEvent.click(screen.getAllByText(ar.MasterData.addRenter)[0]);
        expect(leftoverLatinWords(visibleText(container), DATA)).toEqual([]);
    });
});
