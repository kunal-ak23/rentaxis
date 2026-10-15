import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import en from "../../../../../../messages/en.json";

/** Sign-in page: the "Forgot password?" entry, the translated org picker and the after-reset note. */
const { search, signIn } = vi.hoisted(() => ({ search: { value: "" }, signIn: vi.fn() }));

vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams(search.value) }));
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("next-auth/react", () => ({ signIn, getSession: vi.fn() }));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...props }: React.AnchorHTMLAttributes<HTMLAnchorElement>) => <a href={String(href)} {...props}>{children}</a>,
    useRouter: () => ({ push: vi.fn() }),
}));
vi.mock("next/image", () => ({ default: ({ alt }: { alt: string }) => <span role="img" aria-label={alt} /> }));
vi.mock("framer-motion", () => ({
    motion: { div: ({ children, ...props }: React.HTMLAttributes<HTMLDivElement>) => <div {...props}>{children}</div> },
}));

import LoginPage from "../page";

beforeEach(() => { search.value = ""; signIn.mockReset(); });
afterEach(cleanup);

describe("LoginPage", () => {
    it("links to Forgot password", () => {
        render(<LoginPage />);
        const link = screen.getByTestId("login-forgot");
        expect(link.textContent).toBe(en.Auth.forgotLink);
        expect(link.getAttribute("href")).toBe("/auth/forgot-password");
    });

    it("asks which organisation to open, in the catalogue's words", async () => {
        signIn.mockResolvedValue({
            ok: false,
            error: "LOGIN_AMBIGUOUS:" + JSON.stringify([
                { tenantId: "a", tenantName: "Palm Ridge" }, { tenantId: "b", tenantName: "Al Ashram" }]),
        });
        render(<LoginPage />);
        fireEvent.change(screen.getByLabelText(en.Auth.emailLabel), { target: { value: "p@x.com" } });
        fireEvent.change(screen.getByLabelText(en.Auth.passwordLabel), { target: { value: "pw-123456" } });
        fireEvent.submit(screen.getByLabelText(en.Auth.emailLabel).closest("form")!);

        expect(await screen.findByText(en.Auth.pickOrganisation)).toBeTruthy();
        expect(screen.getByText("Palm Ridge")).toBeTruthy();
        expect(screen.getByText("Al Ashram")).toBeTruthy();
    });

    it("says the password is set after a link was used", () => {
        search.value = "welcome=1";
        render(<LoginPage />);
        expect(screen.getByTestId("login-password-set").textContent).toBe(en.Auth.passwordSet);
    });
});
