import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/**
 * Break round 1, F6: a user whose session ended is sent to sign in with the
 * page they were on as ?callbackUrl=; signing in must bring them back there —
 * but only to a same-origin path.
 */
const { push, search, signIn, getSession } = vi.hoisted(() => ({
    push: vi.fn(),
    search: { value: "" },
    signIn: vi.fn(),
    getSession: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams(search.value) }));
vi.mock("next-auth/react", () => ({ signIn, getSession }));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...props }: React.AnchorHTMLAttributes<HTMLAnchorElement>) => <a href={String(href)} {...props}>{children}</a>,
    useRouter: () => ({ push }),
}));
// The guard sign-in link is the only translated line on this page.
vi.mock("next-intl", () => ({ useTranslations: () => (k: string) => k }));
vi.mock("next/image", () => ({ default: ({ alt }: { alt: string }) => <span role="img" aria-label={alt} /> }));
vi.mock("framer-motion", () => ({
    motion: { div: ({ children, ...props }: React.HTMLAttributes<HTMLDivElement>) => <div {...props}>{children}</div> },
}));

import LoginPage from "../page";
import { isSameOriginPath, safeCallbackUrl } from "@/lib/session/proxyFetchGuard";

const realLocation = window.location;
let assign: ReturnType<typeof vi.fn>;

beforeEach(() => {
    push.mockReset();
    signIn.mockResolvedValue({ ok: true, error: null });
    getSession.mockResolvedValue({ user: { role: "TENANT_ADMIN" } });
    assign = vi.fn();
    Object.defineProperty(window, "location", { configurable: true, value: { ...realLocation, assign } });
});
afterEach(() => {
    cleanup();
    Object.defineProperty(window, "location", { configurable: true, value: realLocation });
});

async function signInNow() {
    render(<LoginPage />);
    fireEvent.change(screen.getByLabelText("Email Address"), { target: { value: "a@b.c" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "secret" } });
    fireEvent.submit(screen.getByLabelText("Email Address").closest("form")!);
}

describe("LoginPage callbackUrl (F6)", () => {
    it("returns to the page the session ended on", async () => {
        search.value = `callbackUrl=${encodeURIComponent("/ar/dashboard/leases?status=ACTIVE")}`;
        await signInNow();
        await waitFor(() => expect(assign).toHaveBeenCalledWith("/ar/dashboard/leases?status=ACTIVE"));
        expect(push).not.toHaveBeenCalled();
    });

    it("ignores an off-site callbackUrl and goes to the dashboard", async () => {
        search.value = `callbackUrl=${encodeURIComponent("https://evil.example/x")}`;
        await signInNow();
        await waitFor(() => expect(push).toHaveBeenCalledWith("/dashboard"));
        expect(assign).not.toHaveBeenCalled();
    });
});

describe("safeCallbackUrl", () => {
    const ORIGIN = "http://localhost:3000";

    // Review fix 1 (open redirect): URL parsing strips TAB/LF, so a check on
    // the leading characters alone let "/\t/evil.example" through to
    // location.assign, which lands on https://evil.example/.
    it.each([
        ["/\t/evil.example"],
        ["/\n/evil.example"],
        ["/\r/evil.example"],
        ["/\x00/evil.example"],
        ["/\x7f/evil.example"],
        ["//evil.example"],
        ["/\\evil.example"],
        ["\\\\evil.example"],
        ["https://evil.example"],
        ["javascript:alert(1)"],
        // Fix round 2: dot segments collapse during parsing, so the OUTPUT
        // could start with "//" even though the input did not.
        ["/..//x"],
        ["/.//x"],
        ["/%2e%2e//x"],
        ["/%2E%2E//x"],
        ["/..//evil.example"],
        ["/en/dashboard/%2e%2e/%2e%2e//evil.example/x?y#z"],
        ["/.."],
        ["/./"],
        ["/en/auth/login"],
        // ...and the auth exclusion runs on the decoded, lower-cased path.
        ["/en/%61uth/login"],
        ["/EN/AUTH/login"],
        ["/api/auth/signout"],
        ["/api/auth/signout?callbackUrl=/x"],
        ["/ar/auth/set-password?x=1"],
        [""],
        [null],
    ])("rejects %j", (raw) => {
        expect(safeCallbackUrl(raw as string | null, ORIGIN)).toBeNull();
    });

    it("keeps a same-origin path with its query and hash", () => {
        expect(safeCallbackUrl("/en/dashboard/leases?x=1#y", ORIGIN)).toBe("/en/dashboard/leases?x=1#y");
    });

    it("keeps an encoded-slash path on this origin (it cannot leave it)", () => {
        const out = safeCallbackUrl("/%2F%2Fevil.example", ORIGIN);
        expect(out).not.toBeNull();
        expect(new URL(out!, ORIGIN).origin).toBe(ORIGIN);
    });

    it("returns null without an origin to check against (server render)", () => {
        expect(safeCallbackUrl("/en/dashboard", null)).toBeNull();
    });
});

describe("LoginPage refuses an encoded control-character callbackUrl", () => {
    it("goes to the dashboard instead of the TAB-smuggled host", async () => {
        search.value = "callbackUrl=%2F%09%2Fevil.example";
        await signInNow();
        await waitFor(() => expect(push).toHaveBeenCalledWith("/dashboard"));
        expect(assign).not.toHaveBeenCalled();
    });
});

describe("isSameOriginPath — the output gate (fix round 2)", () => {
    const ORIGIN = "http://localhost:3000";
    it.each(["//evil.example", "//evil.example/x?y#z", "/\\evil.example", "https://evil.example", "evil.example", ""])("refuses %j", (p) => {
        expect(isSameOriginPath(p, ORIGIN)).toBe(false);
    });
    it("accepts an ordinary path", () => {
        expect(isSameOriginPath("/en/dashboard/leases?x=1#y", ORIGIN)).toBe(true);
    });
});
