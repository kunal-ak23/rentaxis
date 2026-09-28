import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import ar from "../../../../../../../messages/ar.json";
import { leftoverLatinWords, visibleText } from "@/test/latinText";

/**
 * Break-it R2 pass 3: the Key Contacts section and its form were English-only
 * under /ar (heading, Add Contact, category names, labels, the delete confirm).
 */
const role = { current: "TENANT_ADMIN" };
vi.mock("next/navigation", () => ({
    useParams: () => ({ id: "p1" }),
    useRouter: () => ({ push: vi.fn(), refresh: vi.fn() }),
    usePathname: () => "/en/dashboard/properties/p1",
    useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
    useRouter: () => ({ push: vi.fn() }),
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: role.current, name: "U", tenantId: "t1" } } }) }));

import PropertyDetailPage from "../page";

const contact = { id: "c1", name: "Carla Contact", phone: "+971500000000", category: "SECURITY", email: "carla@x.com", address: null, notes: null };
beforeEach(() => {
    role.current = "TENANT_ADMIN";
    global.fetch = vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.endsWith("/v1/properties/p1")) {
            return { ok: true, status: 200, json: async () => ({ id: "p1", nameEn: "Belle Vue", emirate: "DUBAI", type: "RESIDENTIAL" }) } as unknown as Response;
        }
        if (url.endsWith("/contacts")) return { ok: true, status: 200, json: async () => ([contact]) } as unknown as Response;
        return { ok: true, status: 200, json: async () => ([]) } as unknown as Response;
    }) as unknown as typeof fetch;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const renderAr = () => render(<NextIntlClientProvider locale="ar" messages={ar}><PropertyDetailPage /></NextIntlClientProvider>);
const DATA = ["Carla Contact", "+971500000000", "carla@x.com", "+971 50 123 4567", "email@example.com"];

describe("property detail — Key Contacts in Arabic", () => {
    it("the section and its card carry no English", async () => {
        renderAr();
        const heading = await screen.findByText(ar.PropertyContacts.title);
        await screen.findByText("Carla Contact");
        const section = heading.closest("div.bg-background") as HTMLElement;
        expect(section.textContent).toContain(ar.PropertyContacts.categories.SECURITY);
        expect(leftoverLatinWords(visibleText(section), DATA)).toEqual([]);
    });

    it("the add form carries no English, category names included", async () => {
        renderAr();
        fireEvent.click(await screen.findByText(ar.PropertyContacts.add));
        const form = (await screen.findByText(ar.PropertyContacts.save)).closest("form") as HTMLElement;
        const options = [...form.querySelectorAll("option")].map(o => o.textContent ?? "").join(" ");
        fireEvent.change(form.querySelector("select")!, { target: { value: "OTHER" } });
        expect(leftoverLatinWords(visibleText(form) + " " + options, DATA)).toEqual([]);
    });

    it("asks to confirm a delete in Arabic", async () => {
        const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
        renderAr();
        await screen.findByText("Carla Contact");
        fireEvent.click(screen.getByLabelText(ar.PropertyContacts.delete));
        expect(confirm).toHaveBeenCalledWith(ar.PropertyContacts.deleteConfirm);
    });
});
