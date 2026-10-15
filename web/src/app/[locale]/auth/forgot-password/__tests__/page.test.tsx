import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import en from "../../../../../../messages/en.json";

vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...props }: React.AnchorHTMLAttributes<HTMLAnchorElement>) => <a href={String(href)} {...props}>{children}</a>,
}));
vi.mock("next/image", () => ({ default: ({ alt }: { alt: string }) => <span role="img" aria-label={alt} /> }));

import ForgotPasswordPage from "../page";

let status: number;
let bodies: unknown[];

beforeEach(() => {
    status = 202;
    bodies = [];
    global.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
        expect(String(url)).toBe("/api/proxy/auth/forgot-password");
        bodies.push(JSON.parse(String(init?.body)));
        return { ok: status < 300, status, json: async () => ({}) } as unknown as Response;
    }) as unknown as typeof fetch;
});
afterEach(cleanup);

const ask = (email: string) => {
    render(<ForgotPasswordPage />);
    fireEvent.change(screen.getByLabelText(en.Auth.emailLabel), { target: { value: email } });
    fireEvent.click(screen.getByText(en.Auth.sendLink));
};

describe("ForgotPasswordPage", () => {
    it("sends the email and says only that a link was sent if an account exists", async () => {
        ask(" someone@x.com ");
        expect((await screen.findByTestId("forgot-sent")).textContent).toBe(en.Auth.forgotSent);
        expect(bodies).toEqual([{ email: "someone@x.com" }]);
    });

    it("says to wait when the address is rate-limited, and nothing about the account", async () => {
        status = 429;
        ask("someone@x.com");
        expect((await screen.findByRole("alert")).textContent).toBe(en.Auth.forgotRateLimited);
        expect(screen.queryByTestId("forgot-sent")).toBeNull();
    });

    it("offers the way back to sign in", () => {
        render(<ForgotPasswordPage />);
        expect(screen.getByText(en.Auth.backToSignIn).closest("a")?.getAttribute("href")).toBe("/auth/login");
    });
});
