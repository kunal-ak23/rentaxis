import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { Toast, useToast } from "../Toast";

function Harness({ ms }: { ms?: number }) {
    const { toast, showToast } = useToast(ms);
    return (
        <>
            <button onClick={() => showToast("Deleted")}>delete</button>
            <button onClick={() => showToast("Archived")}>archive</button>
            <button onClick={() => showToast("Nope", "error")}>fail</button>
            <Toast toast={toast} />
        </>
    );
}

beforeEach(() => { vi.useFakeTimers(); });
afterEach(() => { cleanup(); vi.useRealTimers(); });

const press = (name: string) => act(() => { screen.getByRole("button", { name }).click(); });

describe("useToast / Toast", () => {
    it("shows a message and takes it down after the duration", () => {
        render(<Harness ms={1000} />);
        expect(screen.queryByTestId("toast")).toBeNull();
        press("delete");
        expect(screen.getByRole("status")).toHaveTextContent("Deleted");
        act(() => { vi.advanceTimersByTime(999); });
        expect(screen.getByTestId("toast")).toBeInTheDocument();
        act(() => { vi.advanceTimersByTime(2); });
        expect(screen.queryByTestId("toast")).toBeNull();
    });

    it("a new toast replaces the one showing and restarts the clock", () => {
        render(<Harness ms={1000} />);
        press("delete");
        act(() => { vi.advanceTimersByTime(800); });
        press("archive");
        expect(screen.getByTestId("toast")).toHaveTextContent("Archived");
        // 800ms after the first would have cleared it; the second one still has 800ms to run.
        act(() => { vi.advanceTimersByTime(800); });
        expect(screen.getByTestId("toast")).toHaveTextContent("Archived");
        act(() => { vi.advanceTimersByTime(300); });
        expect(screen.queryByTestId("toast")).toBeNull();
    });

    it("marks an error toast as one", () => {
        render(<Harness />);
        press("fail");
        expect(screen.getByTestId("toast")).toHaveAttribute("data-tone", "error");
    });

    it("sits at the inline end, so it moves to the left in Arabic", () => {
        render(<Harness />);
        press("delete");
        const cls = screen.getByTestId("toast").className;
        expect(cls).toContain("end-4");
        expect(cls).not.toMatch(/\b(right|left)-/);
    });

    it("does not set state after the page is left", () => {
        const { unmount } = render(<Harness ms={1000} />);
        press("delete");
        unmount();
        expect(() => act(() => { vi.advanceTimersByTime(2000); })).not.toThrow();
    });
});
