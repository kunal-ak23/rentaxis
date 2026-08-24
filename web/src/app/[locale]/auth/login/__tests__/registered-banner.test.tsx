import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const { push, search } = vi.hoisted(() => ({
    push: vi.fn(),
    search: { value: "" },
}));

vi.mock("next/navigation", () => ({
    useSearchParams: () => new URLSearchParams(search.value),
}));
vi.mock("next-auth/react", () => ({
    signIn: vi.fn(),
    getSession: vi.fn(),
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...props }: React.AnchorHTMLAttributes<HTMLAnchorElement>) => (
        <a href={String(href)} {...props}>{children}</a>
    ),
    useRouter: () => ({ push }),
}));
vi.mock("next/image", () => ({
    default: ({ alt }: { alt: string }) => <span role="img" aria-label={alt} />,
}));
vi.mock("framer-motion", () => ({
    motion: {
        div: ({ children, ...props }: React.HTMLAttributes<HTMLDivElement>) => <div {...props}>{children}</div>,
    },
}));

import LoginPage from "../page";

beforeEach(() => {
    push.mockReset();
    search.value = "";
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("LoginPage registration confirmation", () => {
    it("announces successful organization creation after registration", () => {
        search.value = "registered=true";

        render(<LoginPage />);

        expect(screen.getByRole("status")).toHaveTextContent(
            "Organization created successfully. Sign in with your new administrator account.",
        );
    });

    it("does not show a stale confirmation on an ordinary sign-in visit", () => {
        render(<LoginPage />);

        expect(screen.queryByRole("status")).not.toBeInTheDocument();
    });
});
