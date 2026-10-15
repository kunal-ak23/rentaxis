import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const push = vi.fn();
const signIn = vi.fn();
const fb = vi.hoisted(() => ({
    config: { apiKey: "k", authDomain: "d", projectId: "p", appId: "a", emulatorHost: null } as unknown,
    send: vi.fn(),
    confirm: vi.fn(),
}));
vi.mock("next-auth/react", () => ({ signIn: (...a: unknown[]) => signIn(...a) }));
vi.mock("next-intl", () => ({
    useTranslations: () => (k: string, v?: Record<string, unknown>) => (v ? `${k}:${JSON.stringify(v)}` : k),
    useLocale: () => "en",
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children }: { href: string; children: React.ReactNode }) => <a href={href}>{children}</a>,
    useRouter: () => ({ push }),
}));
vi.mock("next/image", () => ({ default: ({ alt }: { alt: string }) => <span>{alt}</span> }));
vi.mock("@/lib/guardAuth/firebasePhone", () => ({
    loadGuardAuthConfig: async () => fb.config,
    sendGuardCode: (...a: unknown[]) => fb.send(...a),
    confirmGuardCode: (...a: unknown[]) => fb.confirm(...a),
    firebaseErrorCode: (e: { code?: string }) => e?.code ?? null,
}));

import GuardSignInPage from "../page";

beforeEach(() => {
    fb.config = { apiKey: "k", authDomain: "d", projectId: "p", appId: "a", emulatorHost: null };
    fb.send.mockReset().mockResolvedValue({ confirm: vi.fn() });
    fb.confirm.mockReset().mockResolvedValue("id-token");
    signIn.mockReset().mockResolvedValue({ ok: true, error: null });
    push.mockReset();
});
afterEach(() => { cleanup(); });

async function sendCodeFor(number: string) {
    render(<GuardSignInPage />);
    fireEvent.change(await screen.findByTestId("guard-phone"), { target: { value: number } });
    fireEvent.click(screen.getByTestId("guard-send-code"));
}

describe("Guard sign-in on the web", () => {
    it("texts a code to the E.164 number, then signs in with the verified token and opens the gate desk", async () => {
        await sendCodeFor("050 123 4567");
        await waitFor(() => expect(fb.send).toHaveBeenCalledTimes(1));
        expect(fb.send.mock.calls[0][1]).toBe("+971501234567");

        fireEvent.change(await screen.findByTestId("guard-code"), { target: { value: "123 456" } });
        fireEvent.click(screen.getByTestId("guard-verify"));
        await waitFor(() => expect(signIn).toHaveBeenCalledWith("guard-phone", { idToken: "id-token", redirect: false }));
        expect(fb.confirm.mock.calls[0][1]).toBe("123456");
        await waitFor(() => expect(push).toHaveBeenCalledWith("/dashboard/gatepass/gate"));
    });

    it("refuses a short number without contacting Firebase", async () => {
        await sendCodeFor("50123");
        expect((await screen.findByTestId("guard-error")).textContent).toContain("errInvalidPhone");
        expect(fb.send).not.toHaveBeenCalled();
    });

    it("holds the resend button for the cooldown after a code is sent", async () => {
        await sendCodeFor("501234567");
        const resend = await screen.findByTestId("guard-resend") as HTMLButtonElement;
        expect(resend.disabled).toBe(true);
        expect(resend.textContent).toContain("resendIn");
    });

    it("says so when Firebase rate-limits the number", async () => {
        fb.send.mockRejectedValue({ code: "auth/too-many-requests" });
        await sendCodeFor("501234567");
        expect((await screen.findByTestId("guard-error")).textContent).toContain("errTooManyRequests");
    });

    it("a verified phone that is not an active guard is refused and stays on the page", async () => {
        signIn.mockResolvedValue({ ok: false, error: "CredentialsSignin" });
        await sendCodeFor("501234567");
        fireEvent.change(await screen.findByTestId("guard-code"), { target: { value: "123456" } });
        fireEvent.click(screen.getByTestId("guard-verify"));
        expect((await screen.findByTestId("guard-error")).textContent).toContain("errNotAGuard");
        expect(push).not.toHaveBeenCalled();
    });

    it("says phone sign-in is not set up when the deployment has no Firebase config", async () => {
        fb.config = null;
        render(<GuardSignInPage />);
        expect(await screen.findByTestId("guard-signin-unavailable")).toBeTruthy();
        expect(screen.queryByTestId("guard-phone")).toBeNull();
    });
});
