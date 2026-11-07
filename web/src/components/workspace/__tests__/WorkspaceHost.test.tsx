import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { useEffect } from "react";

import en from "../../../../messages/en.json";

const nav = vi.hoisted(() => ({ pathname: "/en/dashboard/leases", search: "", push: vi.fn(), replace: vi.fn() }));
vi.mock("next/navigation", () => ({
    usePathname: () => nav.pathname,
    useSearchParams: () => new URLSearchParams(nav.search),
    useRouter: () => ({ push: nav.push, replace: nav.replace }),
}));

import WorkspaceHost from "../WorkspaceHost";
import { useTabTitle, useWorkspace } from "../WorkspaceContext";
import { sessionKey } from "@/lib/workspace/persist";
import { useUnsavedChangesWarning } from "@/hooks/useUnsavedChangesWarning";

let mounts = 0;
function Page({ title }: { title?: string }) {
    const ws = useWorkspace();
    useTabTitle(title ?? null);
    useEffect(() => { mounts += 1; }, []);
    return (
        <div>
            <span data-testid="page">{nav.pathname}</span>
            <button onClick={() => ws?.open("/dashboard/leases/L1", "record")}>open L1</button>
        </div>
    );
}

function host(enabled: boolean, title?: string, extra?: React.ReactNode) {
    return (
        <NextIntlClientProvider locale="en" messages={en}>
            <WorkspaceHost enabled={enabled} userId="u1" orgId="o1" role="TENANT_ADMIN" rail={[]} mainClassName="m">
                <Page title={title} />
                {extra}
            </WorkspaceHost>
        </NextIntlClientProvider>
    );
}

/** Workspace tabs Phase 1 (spec 2026-10-30): the strip over one mounted page, keyed per tab. */
describe("WorkspaceHost", () => {
    beforeEach(() => { nav.pathname = "/en/dashboard/leases"; nav.search = ""; mounts = 0; });
    afterEach(() => { cleanup(); vi.clearAllMocks(); sessionStorage.clear(); localStorage.clear(); });

    it("renders no strip and navigates in place while off, but still titles the browser tab", () => {
        render(host(false, "C-7"));
        expect(screen.queryByTestId("workspace-strip")).toBeNull();
        fireEvent.click(screen.getByText("open L1"));
        expect(nav.push).toHaveBeenCalledWith("/en/dashboard/leases/L1");
        expect(document.title).toBe("C-7 — Miftah");
    });

    it("opens a record in a second tab, switches back with one click and remounts the page per tab", () => {
        const { rerender } = render(host(true));
        expect(screen.getAllByTestId("workspace-tab")).toHaveLength(1);

        fireEvent.click(screen.getByText("open L1"));
        expect(nav.push).toHaveBeenLastCalledWith("/en/dashboard/leases/L1");
        nav.pathname = "/en/dashboard/leases/L1";
        rerender(host(true, "C-1"));
        const tabs = screen.getAllByTestId("workspace-tab");
        expect(tabs).toHaveLength(2);
        expect(tabs[1]).toHaveAttribute("aria-selected", "true");
        expect(tabs[1]).toHaveTextContent("C-1");
        const before = mounts;

        fireEvent.click(tabs[0]);
        expect(nav.push).toHaveBeenLastCalledWith("/en/dashboard/leases");
        nav.pathname = "/en/dashboard/leases";
        rerender(host(true));
        expect(screen.getAllByTestId("workspace-tab")[0]).toHaveAttribute("aria-selected", "true");
        expect(mounts).toBeGreaterThan(before);

        const stored = JSON.parse(sessionStorage.getItem(sessionKey("u1", "o1"))!);
        expect(stored.tabs.map((t: { href: string }) => t.href)).toEqual(["/dashboard/leases", "/dashboard/leases/L1"]);
    });

    it("does not give the record title of the page being left to the tab switched to", () => {
        const { rerender } = render(host(true));
        fireEvent.click(screen.getByText("open L1"));
        nav.pathname = "/en/dashboard/leases/L1";
        rerender(host(true, "C-1"));
        fireEvent.click(screen.getAllByTestId("workspace-tab")[0]);
        rerender(host(true, "C-1"));
        nav.pathname = "/en/dashboard/leases";
        rerender(host(true));
        const tabs = screen.getAllByTestId("workspace-tab");
        expect(tabs[0]).not.toHaveTextContent("C-1");
        expect(tabs[1]).toHaveTextContent("C-1");
    });

    it("asks before leaving a tab with unsaved work, and stays when the user declines", () => {
        function Dirty() {
            useUnsavedChangesWarning(true);
            return null;
        }
        const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
        const { rerender } = render(host(true));
        fireEvent.click(screen.getByText("open L1"));
        nav.pathname = "/en/dashboard/leases/L1";
        rerender(host(true, "C-1", <Dirty />));
        nav.push.mockClear();

        fireEvent.click(screen.getAllByTestId("workspace-tab")[0]);
        expect(confirm).toHaveBeenCalled();
        expect(nav.push).not.toHaveBeenCalled();
        expect(screen.getAllByTestId("workspace-tab")[1]).toHaveAttribute("aria-selected", "true");

        act(() => { fireEvent.keyDown(window, { code: "KeyW", altKey: true }); });
        expect(screen.getAllByTestId("workspace-tab")).toHaveLength(2);

        confirm.mockReturnValue(true);
        fireEvent.click(screen.getAllByTestId("workspace-tab")[0]);
        expect(nav.push).toHaveBeenLastCalledWith("/en/dashboard/leases");
        confirm.mockRestore();
    });

    it("keeps a tab for the home page when the last tab is closed", () => {
        render(host(true));
        fireEvent.click(screen.getByTestId("workspace-tab-close"));
        const tabs = screen.getAllByTestId("workspace-tab");
        expect(tabs).toHaveLength(1);
        expect(nav.push).toHaveBeenLastCalledWith("/en/dashboard");
    });

    it("comes back after a reload with every tab, and closes a tab with Alt+W", () => {
        sessionStorage.setItem(sessionKey("u1", "o1"), JSON.stringify({
            activeId: "b",
            closed: [],
            tabs: [
                { id: "a", href: "/dashboard/leases", title: null, openedAt: 1, usedAt: 1 },
                { id: "b", href: "/dashboard/finance/journals", title: null, openedAt: 2, usedAt: 2 },
            ],
        }));
        // A sign-in or organisation switch lands on the bare home page: the stored active tab comes back.
        nav.pathname = "/en/dashboard";
        render(host(true));
        expect(nav.replace).toHaveBeenCalledWith("/en/dashboard/finance/journals");
        expect(screen.getAllByTestId("workspace-tab")).toHaveLength(2);

        act(() => { fireEvent.keyDown(window, { code: "KeyW", altKey: true }); });
        expect(screen.getAllByTestId("workspace-tab")).toHaveLength(1);
        expect(nav.push).toHaveBeenLastCalledWith("/en/dashboard/leases");
    });
});
