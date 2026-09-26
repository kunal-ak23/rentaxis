import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
}));
import ActionsMenu from "../ActionsMenu";
import SideDrawer from "../SideDrawer";

const items = (onA = vi.fn()) => [
    { id: "a", label: "Extend Contract", testId: "item-a", onSelect: onA },
    { id: "b", label: "Ledger", testId: "item-b", href: "/dashboard/finance/tenant-ledger" },
];
afterEach(cleanup);

describe("ActionsMenu", () => {
    it("keeps items mounted but hidden until opened", () => {
        render(<ActionsMenu label="More actions" items={items()} testId="m" triggerTestId="t" />);
        expect(screen.getByTestId("m")).not.toBeVisible();
        expect(screen.getByTestId("item-a")).toBeInTheDocument();
        fireEvent.click(screen.getByTestId("t"));
        expect(screen.getByTestId("m")).toBeVisible();
        expect(screen.getByTestId("t")).toHaveAttribute("aria-expanded", "true");
    });

    it("runs the item and closes", () => {
        const onA = vi.fn();
        render(<ActionsMenu label="More actions" items={items(onA)} testId="m" triggerTestId="t" />);
        fireEvent.click(screen.getByTestId("t"));
        fireEvent.click(screen.getByTestId("item-a"));
        expect(onA).toHaveBeenCalled();
        expect(screen.getByTestId("m")).not.toBeVisible();
    });

    it("closes on Escape (focus back on the trigger) and moves focus with the arrow keys", () => {
        render(<ActionsMenu label="More actions" items={items()} testId="m" triggerTestId="t" />);
        fireEvent.click(screen.getByTestId("t"));
        fireEvent.keyDown(screen.getByTestId("m"), { key: "ArrowDown" });
        expect(document.activeElement).toBe(screen.getByTestId("item-a"));
        fireEvent.keyDown(screen.getByTestId("m"), { key: "ArrowDown" });
        expect(document.activeElement).toBe(screen.getByTestId("item-b"));
        fireEvent.keyDown(screen.getByTestId("m"), { key: "ArrowDown" });
        expect(document.activeElement).toBe(screen.getByTestId("item-a"));
        fireEvent.keyDown(screen.getByTestId("m"), { key: "Escape" });
        expect(screen.getByTestId("m")).not.toBeVisible();
        expect(document.activeElement).toBe(screen.getByTestId("t"));
    });

    it("closes on a click outside", () => {
        render(<div><span data-testid="outside" /><ActionsMenu label="x" items={items()} testId="m" triggerTestId="t" /></div>);
        fireEvent.click(screen.getByTestId("t"));
        fireEvent.mouseDown(screen.getByTestId("outside"));
        expect(screen.getByTestId("m")).not.toBeVisible();
    });

    it("anchors the menu to the inline end (RTL-safe) and renders nothing without items", () => {
        render(<ActionsMenu label="x" items={items()} testId="m" triggerTestId="t" />);
        expect(screen.getByTestId("m").className).toMatch(/\bend-0\b/);
        expect(screen.getByTestId("m").className).not.toMatch(/\b(left|right)-/);
        cleanup();
        const empty = render(<ActionsMenu label="x" items={[]} testId="m" triggerTestId="t" />);
        expect(empty.container).toBeEmptyDOMElement();
    });

    it("labels the icon trigger for screen readers", () => {
        render(<ActionsMenu label="Actions for A-101" items={items()} testId="m" triggerTestId="t" variant="icon" />);
        expect(screen.getByTestId("t")).toHaveAttribute("aria-label", "Actions for A-101");
    });
});

describe("SideDrawer", () => {
    it("renders only while open, from the inline end, and closes on Esc and the close button", () => {
        const onClose = vi.fn();
        const { rerender } = render(<SideDrawer open={false} onClose={onClose} title="Assignment" closeLabel="Close" testId="d"><p>body</p></SideDrawer>);
        expect(screen.queryByTestId("d")).toBeNull();
        rerender(<SideDrawer open onClose={onClose} title="Assignment" closeLabel="Close" testId="d"><p>body</p></SideDrawer>);
        expect(screen.getByTestId("d").className).toMatch(/\bend-0\b/);
        expect(document.activeElement).toBe(screen.getByTestId("d-close"));
        fireEvent.keyDown(window, { key: "Escape" });
        fireEvent.click(screen.getByTestId("d-close"));
        expect(onClose).toHaveBeenCalledTimes(2);
    });
});
