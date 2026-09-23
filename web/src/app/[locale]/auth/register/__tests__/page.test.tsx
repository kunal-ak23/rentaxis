import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const { push } = vi.hoisted(() => ({ push: vi.fn() }));

vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...props }: React.AnchorHTMLAttributes<HTMLAnchorElement>) => (
        <a href={String(href)} {...props}>{children}</a>
    ),
    useRouter: () => ({ push }),
}));
vi.mock("next-intl", () => ({ useTranslations: () => (key: string) => key }));
vi.mock("next/image", () => ({
    default: ({ alt }: { alt: string }) => <span role="img" aria-label={alt} />,
}));
vi.mock("framer-motion", () => ({
    motion: {
        div: ({ children, ...props }: React.HTMLAttributes<HTMLDivElement>) => <div {...props}>{children}</div>,
    },
}));

import RegisterPage from "../page";

const response = (body: unknown, ok = true, status = 200) => ({
    ok,
    status,
    json: async () => body,
}) as Response;

function completeForm() {
    fireEvent.change(screen.getByLabelText("Full Name"), { target: { value: "Aisha Admin" } });
    fireEvent.change(screen.getByLabelText("Company Name"), { target: { value: "Tutorial Towers" } });
    fireEvent.change(screen.getByLabelText("Email Address"), { target: { value: "aisha@example.com" } });
    fireEvent.change(screen.getByLabelText("Password", { exact: true }), { target: { value: "correct-horse" } });
    fireEvent.click(screen.getByRole("checkbox"));
}

beforeEach(() => {
    push.mockReset();
    global.fetch = vi.fn();
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("RegisterPage", () => {
    it("creates the organisation through the public proxy before redirecting", async () => {
        (global.fetch as ReturnType<typeof vi.fn>).mockResolvedValue(response({ id: "user-1" }));
        render(<RegisterPage />);
        completeForm();

        fireEvent.click(screen.getByRole("button", { name: /create organization/i }));

        await waitFor(() => expect(push).toHaveBeenCalledWith("/auth/login?registered=true"));
        expect(global.fetch).toHaveBeenCalledWith("/api/proxy/auth/register", expect.objectContaining({
            method: "POST",
            headers: { "Content-Type": "application/json" },
        }));
        const request = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0][1] as RequestInit;
        expect(JSON.parse(String(request.body))).toEqual({
            fullName: "Aisha Admin",
            companyName: "Tutorial Towers",
            email: "aisha@example.com",
            password: "correct-horse",
        });
    });

    it("surfaces the backend message and stays on the form when registration fails", async () => {
        (global.fetch as ReturnType<typeof vi.fn>).mockResolvedValue(
            response({ message: "Registration is temporarily unavailable." }, false, 503),
        );
        render(<RegisterPage />);
        completeForm();

        fireEvent.click(screen.getByRole("button", { name: /create organization/i }));

        expect(await screen.findByText("Registration is temporarily unavailable.")).toBeVisible();
        expect(push).not.toHaveBeenCalled();
    });

    it("rejects a short password without calling the backend", async () => {
        render(<RegisterPage />);
        completeForm();
        fireEvent.change(screen.getByLabelText("Password", { exact: true }), { target: { value: "short" } });

        fireEvent.click(screen.getByRole("button", { name: /create organization/i }));

        expect(await screen.findByText("Password must be at least 8 characters")).toBeVisible();
        expect(global.fetch).not.toHaveBeenCalled();
    });
});
