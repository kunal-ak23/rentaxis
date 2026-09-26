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

    it("places the open panel with fixed coordinates at the trigger's end, flipping or opening upward to stay on screen", () => {
        const rect = (r: Partial<DOMRect>) => () => ({ x: 0, y: 0, width: 32, height: 28, top: 0, left: 0, right: 0, bottom: 0, toJSON: () => ({}), ...r }) as DOMRect;
        Object.defineProperty(window, "innerWidth", { configurable: true, value: 390 });
        Object.defineProperty(window, "innerHeight", { configurable: true, value: 800 });
        render(<ActionsMenu label="x" items={items()} testId="m" triggerTestId="t" />);
        const t = screen.getByTestId("t");
        // A row menu at the end of a row: the panel's right edge meets the trigger's.
        t.getBoundingClientRect = rect({ left: 340, right: 372, top: 100, bottom: 128 });
        fireEvent.click(t);
        expect(screen.getByTestId("m").style.position).toBe("fixed");
        expect(screen.getByTestId("m").style.right).toBe("18px");
        expect(screen.getByTestId("m").style.top).toBe("132px");
        fireEvent.click(t);
        // A trigger near the start edge: 200px to its left would leave the screen, so it opens rightward.
        t.getBoundingClientRect = rect({ left: 16, right: 130, top: 760, bottom: 788 });
        fireEvent.click(t);
        expect(screen.getByTestId("m").style.left).toBe("16px");
        // …and near the bottom it opens upward.
        expect(screen.getByTestId("m").style.bottom).toBe("44px");
    });

    it("closes on Escape with focus still on the trigger", () => {
        render(<ActionsMenu label="x" items={items()} testId="m" triggerTestId="t" />);
        fireEvent.click(screen.getByTestId("t"));
        fireEvent.keyDown(document.body, { key: "Escape" });
        expect(screen.getByTestId("m")).not.toBeVisible();
    });

    it("closes when the page scrolls under it", () => {
        render(<ActionsMenu label="x" items={items()} testId="m" triggerTestId="t" />);
        fireEvent.click(screen.getByTestId("t"));
        fireEvent.scroll(window);
        expect(screen.getByTestId("m")).not.toBeVisible();
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
