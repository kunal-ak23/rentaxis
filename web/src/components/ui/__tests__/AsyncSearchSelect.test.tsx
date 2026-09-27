import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import { AsyncSearchSelect, type AsyncOption } from "@/components/ui/AsyncSearchSelect";

type Deferred = { resolve: (o: AsyncOption[]) => void; reject: (e: unknown) => void };

function renderSelect(props: Partial<Parameters<typeof AsyncSearchSelect>[0]> & { search: (q: string) => Promise<AsyncOption[]> }) {
    const onChange = vi.fn();
    const utils = render(
        <NextIntlClientProvider locale="en" messages={en}>
            <AsyncSearchSelect value="" onChange={onChange} placeholder="Pick one" testId="sel" {...props} />
        </NextIntlClientProvider>,
    );
    return { onChange, ...utils };
}

const flush = () => act(async () => {
    await Promise.resolve();
});

const trigger = () => screen.getByTestId("sel");
const searchBox = () => screen.getByPlaceholderText("Find") as HTMLInputElement;

// jsdom has no layout, so no scrollIntoView; the components call it on highlight.
Element.prototype.scrollIntoView = vi.fn();

beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
});

afterEach(() => {
    cleanup();
    vi.useRealTimers();
});

describe("AsyncSearchSelect", () => {
    it("searches once with '' on open, then once 250 ms after typing, with the trimmed text", async () => {
        const search = vi.fn(async () => [] as AsyncOption[]);
        renderSelect({ search, searchPlaceholder: "Find" });

        fireEvent.click(trigger());
        await flush();
        expect(search).toHaveBeenCalledTimes(1);
        expect(search).toHaveBeenLastCalledWith("");

        fireEvent.change(searchBox(), { target: { value: " 0" } });
        fireEvent.change(searchBox(), { target: { value: " 07 " } });
        act(() => {
            vi.advanceTimersByTime(249);
        });
        expect(search).toHaveBeenCalledTimes(1);
        act(() => {
            vi.advanceTimersByTime(1);
        });
        await flush();
        expect(search).toHaveBeenCalledTimes(2);
        expect(search).toHaveBeenLastCalledWith("07");
        expect(await screen.findByText(en.Pickers.noMatches)).toBeInTheDocument();
    });

    it("drops a stale response that resolves after a newer one", async () => {
        const pending = new Map<string, Deferred>();
        const search = vi.fn(
            (q: string) => new Promise<AsyncOption[]>((resolve, reject) => pending.set(q, { resolve, reject })),
        );
        renderSelect({ search, searchPlaceholder: "Find" });

        fireEvent.click(trigger());
        fireEvent.change(searchBox(), { target: { value: "a" } });
        act(() => {
            vi.advanceTimersByTime(250);
        });
        fireEvent.change(searchBox(), { target: { value: "ab" } });
        act(() => {
            vi.advanceTimersByTime(250);
        });
        expect(search.mock.calls.map((c) => c[0])).toEqual(["", "a", "ab"]);

        await act(async () => {
            pending.get("ab")!.resolve([{ value: "2", label: "Second" }]);
        });
        await act(async () => {
            pending.get("a")!.resolve([{ value: "1", label: "First" }]);
            pending.get("")!.resolve([{ value: "0", label: "Zeroth" }]);
        });

        expect(screen.getByText("Second")).toBeInTheDocument();
        expect(screen.queryByText("First")).not.toBeInTheDocument();
        expect(screen.queryByText("Zeroth")).not.toBeInTheDocument();
    });

    it("keeps the value when a search fails and shows the failure row", async () => {
        const search = vi.fn(async () => {
            throw new Error("boom");
        });
        const { onChange } = renderSelect({ search, value: "u-9", selectedLabel: "Unit 909" });

        expect(trigger()).toHaveTextContent("Unit 909");
        fireEvent.click(trigger());
        expect(await screen.findByText(en.Pickers.searchFailed)).toBeInTheDocument();
        expect(onChange).not.toHaveBeenCalled();
        expect(trigger()).toHaveTextContent("Unit 909");
    });

    it("shows selectedLabel on the trigger when the value is not among the options", async () => {
        const search = vi.fn(async () => [{ value: "other", label: "Other" }]);
        renderSelect({ search, value: "u-1", selectedLabel: "Unit 101" });

        expect(trigger()).toHaveTextContent("Unit 101");
        fireEvent.click(trigger());
        await screen.findByText("Other");
        expect(trigger()).toHaveTextContent("Unit 101");
    });

    it("selects with ArrowDown + Enter, and Escape closes returning focus to the trigger", async () => {
        const opts = [
            { value: "a", label: "Alpha", sublabel: "first" },
            { value: "b", label: "Beta" },
        ];
        const search = vi.fn(async () => opts);
        const { onChange } = renderSelect({ search, searchPlaceholder: "Find" });

        fireEvent.click(trigger());
        await screen.findByText("Beta");
        fireEvent.keyDown(searchBox(), { key: "ArrowDown" });
        fireEvent.keyDown(searchBox(), { key: "ArrowDown" });
        fireEvent.keyDown(searchBox(), { key: "Enter" });
        expect(onChange).toHaveBeenCalledWith("b", opts[1]);
        expect(screen.queryByPlaceholderText("Find")).not.toBeInTheDocument();
        expect(document.activeElement).toBe(trigger());

        fireEvent.click(trigger());
        await screen.findByText("Beta");
        fireEvent.keyDown(searchBox(), { key: "Escape" });
        expect(screen.queryByPlaceholderText("Find")).not.toBeInTheDocument();
        expect(document.activeElement).toBe(trigger());
        expect(onChange).toHaveBeenCalledTimes(1);
    });

    it("the clear row calls onChange('', null)", async () => {
        const search = vi.fn(async () => [{ value: "a", label: "Alpha" }]);
        const { onChange } = renderSelect({ search, value: "a", selectedLabel: "Alpha" });

        fireEvent.click(trigger());
        await screen.findAllByText("Alpha");
        fireEvent.click(screen.getAllByRole("option")[0]);
        expect(onChange).toHaveBeenCalledWith("", null);
    });

    it("a disabled select does not open", () => {
        const search = vi.fn(async () => []);
        renderSelect({ search, disabled: true });
        fireEvent.click(trigger());
        expect(search).not.toHaveBeenCalled();
    });

});
