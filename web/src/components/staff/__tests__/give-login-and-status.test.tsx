import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import { fetchRouter } from "@/test/fetchRouter";

/**
 * Tutorial 22: staff records could not be assigned tickets and nothing said
 * why; and the Edit Staff form must open with the member's real status.
 */
vi.mock("next/navigation", () => ({
    useParams: () => ({}),
    useRouter: () => ({ push: vi.fn(), refresh: vi.fn(), replace: vi.fn() }),
    usePathname: () => "/en/dashboard",
    useSearchParams: () => new URLSearchParams(),
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { id: "u", role: "TENANT_ADMIN", tenantId: "t1" } } }) }));

import StaffManager from "../StaffManager";

const KHALID = { id: "s1", nameEn: "Khalid Rahman", nameAr: null, designation: "Maintenance Supervisor", active: false,
    property: null, salaryAccount: null, monthlySalary: 0, userId: null };
const OMAR = { id: "s2", nameEn: "Omar Linked", nameAr: null, designation: "Electrician", active: true,
    property: null, salaryAccount: null, monthlySalary: 0, userId: "user-9" };

let api: ReturnType<typeof fetchRouter>;
beforeEach(() => {
    api = fetchRouter();
    api.on("GET", /\/v1\/staff$/, { body: [KHALID, OMAR] });
    api.on("GET", "/v1/properties", { body: [] });
    api.on("GET", "/v1/finance/accounts", { body: [] });
    api.on("POST", "/v1/staff/s1/login", { body: { ...KHALID, userId: "user-1" } });
    api.on("PUT", "/v1/staff/s1", c => ({ body: { ...KHALID, ...(c.body as object) } }));
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const renderEn = () => render(<NextIntlClientProvider locale="en" messages={en}><StaffManager /></NextIntlClientProvider>);

describe("StaffManager — logins and status", () => {
    it("marks who has a login and offers Give login only to those without", async () => {
        renderEn();
        expect(await screen.findByTestId("staff-has-login-s2")).toBeTruthy();
        expect(screen.queryByTestId("staff-give-login-s2")).toBeNull();
        expect(screen.getByTestId("staff-give-login-s1")).toBeTruthy();
    });

    it("Give login sends the email and says the member can now be assigned", async () => {
        renderEn();
        fireEvent.click(await screen.findByTestId("staff-give-login-s1"));
        fireEvent.change(await screen.findByTestId("staff-login-email"), { target: { value: "bad" } });
        fireEvent.click(screen.getByTestId("staff-give-login-confirm"));
        expect(await screen.findByText(en.Staff.giveLoginInvalidEmail)).toBeTruthy();
        expect(api.callsTo("POST", "/login")).toHaveLength(0);

        fireEvent.change(screen.getByTestId("staff-login-email"), { target: { value: " khalid@oasis.test " } });
        fireEvent.click(screen.getByTestId("staff-give-login-confirm"));
        await waitFor(() => expect(api.callsTo("POST", "/v1/staff/s1/login")).toHaveLength(1));
        expect(api.callsTo("POST", "/v1/staff/s1/login")[0].body).toEqual({ email: "khalid@oasis.test" });
        expect((await screen.findByTestId("staff-login-notice")).textContent).toContain("Khalid Rahman");
    });

    it("opens Edit Staff for an inactive member with Active unticked, and saving keeps them inactive", async () => {
        renderEn();
        await screen.findAllByText("Khalid Rahman");
        fireEvent.click(screen.getAllByLabelText(en.Staff.editStaff)[0]);
        const box = await screen.findByRole("checkbox", { name: en.Staff.active }) as HTMLInputElement;
        expect(box.checked).toBe(false);
        fireEvent.submit(box.closest("form")!);
        await waitFor(() => expect(api.callsTo("PUT", "/v1/staff/s1")).toHaveLength(1));
        expect((api.callsTo("PUT", "/v1/staff/s1")[0].body as { active: boolean }).active).toBe(false);
    });
});
